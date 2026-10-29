mod app;
mod hub;
mod input;
mod markdown;
mod mcp;
mod model;
mod model_info;
mod terminal;
mod text;
mod theme;
mod ui;

use std::env;
use std::io;
use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use tokio::signal::unix::{signal, SignalKind};
use tokio::sync::Mutex;

use crate::app::{Action, App, HELP};
use crate::hub::{AgentOutcome, Hub};
use crate::mcp::McpManager;
use crate::model::{AgentEvent, Message};
use crate::terminal::Terminal;
use crate::ui::UiState;

#[tokio::main(flavor = "current_thread")]
async fn main() {
    let args: Vec<_> = env::args_os().skip(1).collect();
    if args.len() == 1 && (args[0] == "--help" || args[0] == "-h") {
        println!("{HELP}");
        return;
    }
    if !args.is_empty() {
        eprintln!("Usage: agent [--help]");
        std::process::exit(2);
    }
    if let Err(error) = run().await {
        eprintln!("agent: {error}");
        std::process::exit(2);
    }
}

#[derive(Clone, Copy)]
enum TaskKind {
    Chat,
    Compact,
}

async fn run() -> io::Result<()> {
    let mut hub = Hub::connect().await?;
    let config = hub.ai_config().await.map_err(io::Error::other)?;
    let initial_provider = config.provider;
    let initial_model = config.model;
    let initial_effort = config.effort;
    let terminal_id = env::var("HUB_TERMINAL").unwrap_or_default();
    let mut titles = Hub::subscribe_titles().await.ok();
    // Every launch starts a new conversation. Nothing is registered on the
    // hub up front: the session materializes there with the first message.
    let mut session_id = new_session_id(&terminal_id);
    let context = hub.caller.clone();
    let cwd = env::current_dir()
        .map(|path| path.display().to_string())
        .unwrap_or_else(|_| "/".to_owned());
    let capabilities = hub.capabilities(&cwd).await.unwrap_or_default();
    let mcp_hub = Arc::new(Mutex::new(Hub::connect().await?));
    let mcp = Arc::new(McpManager::connect(&capabilities, mcp_hub, &cwd).await);
    let mut agent_hub = Hub::connect().await?;
    let attached = agent_hub
        .agent_attach(
            &session_id,
            &terminal_id,
            &cwd,
            &context,
            &initial_model,
            &initial_effort,
            capabilities.workspace_mcp,
        )
        .await
        .map_err(io::Error::other)?;
    let _ = attached;
    agent_hub
        .agent_set_mcp_tools(&mcp.definitions())
        .await
        .map_err(io::Error::other)?;
    let agent_hub = Arc::new(Mutex::new(agent_hub));
    let hub = Arc::new(Mutex::new(hub));

    let mut terminate = signal(SignalKind::terminate())?;
    let mut interrupt = signal(SignalKind::interrupt())?;
    let mut hangup = signal(SignalKind::hangup())?;
    let mut quit = signal(SignalKind::quit())?;
    let mut suspend = signal(SignalKind::from_raw(libc::SIGTSTP))?;

    let (info_sender, mut info_receiver) = tokio::sync::mpsc::unbounded_channel();
    tokio::spawn(async move {
        let catalog = match Hub::connect().await {
            Ok(mut hub) => hub.catalog().await.unwrap_or_default(),
            Err(_) => Default::default(),
        };
        let _ = info_sender.send(catalog);
    });
    let mut info_pending = true;

    let mut terminal = Terminal::enter()?;
    let mut app = App::new(
        context.clone(),
        initial_provider,
        initial_model,
        initial_effort,
        Vec::new(),
        String::new(),
    );
    let mut state = UiState::default();
    let mut pending: Option<tokio::task::JoinHandle<Result<AgentOutcome, String>>> = None;
    let mut pending_kind = TaskKind::Chat;
    let mut approvals: Option<tokio::sync::mpsc::UnboundedSender<(u64, bool)>> = None;
    let mut events: Option<tokio::sync::mpsc::UnboundedReceiver<AgentEvent>> = None;
    let mut ticker = tokio::time::interval(Duration::from_millis(100));
    ticker.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);

    loop {
        terminal.draw(|frame| ui::draw(frame, &mut app, &mut state))?;

        tokio::select! {
            _ = terminate.recv() => break,
            _ = interrupt.recv() => break,
            _ = hangup.recv() => break,
            _ = quit.recv() => break,
            _ = suspend.recv() => break,
            maybe_event = terminal.next_event() => {
                let Some(event) = maybe_event else { break };
                match app.handle_event(event) {
                    Action::None => {}
                    Action::Quit => break,
                    Action::Cancel => {
                        if let Some(handle) = pending.take() {
                            handle.abort();
                            let _ = handle.await;
                        }
                        if matches!(pending_kind, TaskKind::Chat) {
                            let _ = agent_hub.lock().await.agent_cancel().await;
                            tokio::time::sleep(Duration::from_millis(10)).await;
                            if let Ok((history_json, _)) = hub
                                .lock()
                                .await
                                .load_history(&session_id)
                                .await
                            {
                                let messages = serde_json::from_str::<Vec<Message>>(&history_json)
                                    .unwrap_or_default();
                                app.complete_cancelled(messages);
                            }
                        }
                        events = None;
                        approvals = None;
                    }
                    Action::Submit(messages) => {
                        if let Some(handle) = pending.take() {
                            handle.abort();
                            let _ = handle.await;
                        }
                        if let Some(text) = last_user(&messages) {
                            let (handle, receiver, approval) = spawn_agent(
                                &app,
                                &agent_hub,
                                Arc::clone(&mcp),
                                text,
                            );
                            pending = Some(handle);
                            events = Some(receiver);
                            approvals = Some(approval);
                            pending_kind = TaskKind::Chat;
                        }
                    }
                    Action::Compact(_messages) => {
                        if let Some(handle) = pending.take() {
                            handle.abort();
                        }
                        events = None;
                        approvals = None;
                        pending_kind = TaskKind::Compact;
                        let model = app.model().to_owned();
                        let effort = app.effort().to_owned();
                        let hub = Arc::clone(&agent_hub);
                        pending = Some(tokio::spawn(async move {
                            hub.lock().await.agent_compact(&model, &effort).await
                        }));
                    }
                    Action::Approval { id, allow } => {
                        if let Some(sender) = approvals.as_ref() {
                            let _ = sender.send((id, allow));
                        }
                    }
                    Action::PersistSelection => {
                        persist_selection(&hub, app.model(), app.effort());
                    }
                    Action::PersistHistory(messages) => {
                        let _ = hub
                            .lock()
                            .await
                            .save_history(&session_id, &messages)
                            .await;
                        let _ = agent_hub
                            .lock()
                            .await
                            .agent_attach(
                                &session_id,
                                &terminal_id,
                                &cwd,
                                &context,
                                app.model(),
                                app.effort(),
                                capabilities.workspace_mcp,
                            )
                            .await;
                        let _ = agent_hub
                            .lock()
                            .await
                            .agent_set_mcp_tools(&mcp.definitions())
                            .await;
                    }
                    Action::ListSessions => {
                        let sessions = hub.lock().await.list_sessions().await.unwrap_or_default();
                        app.open_sessions(&sessions, &session_id);
                    }
                    Action::SelectSession { id, title } => {
                        // The UI blocks switching while a request runs; abort any
                        // stray run so its result cannot land on the new session.
                        if let Some(handle) = pending.take() {
                            handle.abort();
                            let _ = handle.await;
                        }
                        events = None;
                        approvals = None;
                        let (history_json, stored_title) = hub
                            .lock()
                            .await
                            .load_history(&id)
                            .await
                            .unwrap_or_default();
                        let history =
                            serde_json::from_str::<Vec<Message>>(&history_json).unwrap_or_default();
                        // Kotlin agent repairs dangling tool calls when it loads the session.
                        let _ = hub.lock().await.set_active_session(&id).await;
                        let _ = agent_hub
                            .lock()
                            .await
                            .agent_attach(
                                &id,
                                &terminal_id,
                                &cwd,
                                &context,
                                app.model(),
                                app.effort(),
                                capabilities.workspace_mcp,
                            )
                            .await;
                        let _ = agent_hub
                            .lock()
                            .await
                            .agent_set_mcp_tools(&mcp.definitions())
                            .await;
                        session_id = id;
                        let title = if stored_title.is_empty() {
                            title
                        } else {
                            stored_title
                        };
                        app.switch_session(history, &title);
                    }
                    Action::NewSession => {
                        if let Some(handle) = pending.take() {
                            handle.abort();
                            let _ = handle.await;
                        }
                        events = None;
                        approvals = None;
                        let id = new_session_id(&terminal_id);
                        let _ = hub.lock().await.set_active_session(&id).await;
                        let _ = agent_hub
                            .lock()
                            .await
                            .agent_attach(
                                &id,
                                &terminal_id,
                                &cwd,
                                &context,
                                app.model(),
                                app.effort(),
                                capabilities.workspace_mcp,
                            )
                            .await;
                        let _ = agent_hub
                            .lock()
                            .await
                            .agent_set_mcp_tools(&mcp.definitions())
                            .await;
                        session_id = id;
                        app.switch_session(Vec::new(), "New conversation");
                    }
                }
            }
            event = async {
                match events.as_mut() {
                    Some(receiver) => receiver.recv().await,
                    None => None,
                }
            }, if events.is_some() => {
                if let Some(event) = event {
                    app.agent_event(event);
                    if let Some(receiver) = events.as_mut() {
                        while let Ok(event) = receiver.try_recv() {
                            app.agent_event(event);
                        }
                    }
                }
            }
            result = async { pending.as_mut().unwrap().await }, if pending.is_some() => {
                pending = None;
                events = None;
                approvals = None;
                match (pending_kind, result) {
                    (TaskKind::Chat, Ok(Ok(outcome))) => {
                        app.complete_success(outcome.messages, outcome.answer)
                    }
                    (TaskKind::Chat, Ok(Err(error))) => {
                        let messages = hub
                            .lock()
                            .await
                            .load_history(&session_id)
                            .await
                            .ok()
                            .and_then(|(json, _)| serde_json::from_str::<Vec<Message>>(&json).ok());
                        app.complete_error(error, messages)
                    }
                    (TaskKind::Compact, Ok(Ok(outcome))) => {
                        let _ = hub
                            .lock()
                            .await
                            .save_history(&session_id, &outcome.messages)
                            .await;
                        app.complete_compact(outcome.answer)
                    }
                    (TaskKind::Compact, Ok(Err(error))) => {
                        app.complete_error(error, None)
                    }
                    (_, Err(error)) if error.is_cancelled() => {}
                    (_, Err(_)) => {
                        app.complete_error("Request failed unexpectedly.".to_owned(), None)
                    }
                }
                let queued = app.queued_prompts();
                if let Some(messages) = app.resume_queued() {
                    let text = if queued.is_empty() {
                        last_user(&messages)
                    } else {
                        Some(queued.join("\n\n"))
                    };
                    if let Some(text) = text {
                        let (handle, receiver, approval) =
                            spawn_agent(&app, &agent_hub, Arc::clone(&mcp), text);
                        pending = Some(handle);
                        events = Some(receiver);
                        approvals = Some(approval);
                        pending_kind = TaskKind::Chat;
                    }
                }
            }
            title = async {
                match titles.as_mut() {
                    Some(stream) => stream.recv().await,
                    None => None,
                }
            }, if titles.is_some() => {
                match title {
                    Some((id, value)) => {
                        if id == session_id {
                            app.set_title(value);
                        }
                    }
                    None => titles = None,
                }
            }
            info = info_receiver.recv(), if info_pending => {
                info_pending = false;
                if let Some(catalog) = info {
                    app.set_catalog(catalog);
                    if !app.model().is_empty() {
                        persist_selection(&hub, app.model(), app.effort());
                    }
                }
            }
            _ = ticker.tick(), if pending.is_some() => app.tick(),
        }
    }

    if let Some(handle) = pending.take() {
        handle.abort();
        let _ = handle.await;
        if matches!(pending_kind, TaskKind::Chat) {
            let _ = agent_hub.lock().await.agent_cancel().await;
        }
    }
    Ok(())
}

fn new_session_id(terminal: &str) -> String {
    let base = if terminal.is_empty() {
        "agent"
    } else {
        terminal
    };
    let millis = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_millis())
        .unwrap_or(0);
    format!("{base}-{}-{millis}", std::process::id())
}

fn last_user(messages: &[Message]) -> Option<String> {
    messages
        .iter()
        .rev()
        .find(|message| message.role == "user")
        .map(|message| message.content_text())
        .map(str::trim)
        .filter(|text| !text.is_empty())
        .map(str::to_owned)
}

fn spawn_agent(
    app: &App,
    agent_hub: &Arc<Mutex<Hub>>,
    mcp: Arc<McpManager>,
    text: String,
) -> (
    tokio::task::JoinHandle<Result<AgentOutcome, String>>,
    tokio::sync::mpsc::UnboundedReceiver<AgentEvent>,
    tokio::sync::mpsc::UnboundedSender<(u64, bool)>,
) {
    let (sender, receiver) = tokio::sync::mpsc::unbounded_channel();
    let (approval_sender, mut approval_receiver) = tokio::sync::mpsc::unbounded_channel();
    let hub = Arc::clone(agent_hub);
    let model = app.model().to_owned();
    let effort = app.effort().to_owned();
    let handle = tokio::spawn(async move {
        let mut hub = hub.lock().await;
        hub.agent_run(&text, &model, &effort, mcp, &mut approval_receiver, &sender)
            .await
    });
    (handle, receiver, approval_sender)
}

fn persist_selection(hub: &Arc<Mutex<Hub>>, model: &str, effort: &str) {
    if model.is_empty() {
        return;
    }
    let hub = Arc::clone(hub);
    let model = model.to_owned();
    let effort = effort.to_owned();
    tokio::spawn(async move {
        let _ = hub.lock().await.set_ai_selection(&model, &effort).await;
    });
}
