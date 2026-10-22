mod app;
mod hub;
mod input;
mod markdown;
mod mcp;
mod model;
mod model_info;
mod runner;
mod terminal;
mod text;
mod theme;
mod tool;
mod ui;

use std::env;
use std::io;
use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use tokio::signal::unix::{signal, SignalKind};
use tokio::sync::Mutex;

use crate::app::{Action, App, HELP};
use crate::hub::Hub;
use crate::model::{AgentEvent, Message};
use crate::runner::Outcome;
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
    let active_session = hub.active_session().await.ok().flatten();
    let mut session_id = active_session.unwrap_or_else(|| {
        if terminal_id.is_empty() {
            "default".to_owned()
        } else {
            terminal_id.clone()
        }
    });
    let (history_json, initial_title) = hub.load_history(&session_id).await.unwrap_or_default();
    let history = serde_json::from_str::<Vec<Message>>(&history_json).unwrap_or_default();
    let context = hub.caller.clone();
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
        context,
        initial_provider,
        initial_model,
        initial_effort,
        history,
        initial_title,
    );
    let mut state = UiState::default();
    let mut pending: Option<tokio::task::JoinHandle<Result<Outcome, runner::Failure>>> = None;
    let mut pending_kind = TaskKind::Chat;
    let active_exec: tool::ActiveExec = Default::default();
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
                        events = None;
                        let exec_id = active_exec
                            .lock()
                            .unwrap_or_else(|error| error.into_inner())
                            .take();
                        if let Some(exec_id) = exec_id {
                            let hub = Arc::clone(&hub);
                            tokio::spawn(async move {
                                let _ = hub.lock().await.close_exec(exec_id).await;
                            });
                        }
                    }
                    Action::Submit(messages) => {
                        if let Some(handle) = pending.take() {
                            handle.abort();
                        }
                        let (sender, receiver) = tokio::sync::mpsc::unbounded_channel();
                        events = Some(receiver);
                        pending_kind = TaskKind::Chat;
                        let hub = Arc::clone(&hub);
                        let model = app.model().to_owned();
                        let effort = app.effort().to_owned();
                        let active = Arc::clone(&active_exec);
                        let session = session_id.clone();
                        pending = Some(tokio::spawn(async move {
                            runner::run(model, effort, hub, messages, sender, active, session).await
                        }));
                    }
                    Action::Compact(messages) => {
                        if let Some(handle) = pending.take() {
                            handle.abort();
                        }
                        events = None;
                        pending_kind = TaskKind::Compact;
                        let model = app.model().to_owned();
                        let effort = app.effort().to_owned();
                        pending = Some(tokio::spawn(async move {
                            runner::compact(model, effort, messages)
                                .await
                                .map_err(|error| runner::Failure::new(error, &[]))
                        }));
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
                    }
                    Action::ListSessions => {
                        let sessions = hub.lock().await.list_sessions().await.unwrap_or_default();
                        app.open_sessions(&sessions, &session_id);
                    }
                    Action::SelectSession { id, title } => {
                        let (history_json, stored_title) = hub
                            .lock()
                            .await
                            .load_history(&id)
                            .await
                            .unwrap_or_default();
                        let history =
                            serde_json::from_str::<Vec<Message>>(&history_json).unwrap_or_default();
                        let _ = hub.lock().await.set_active_session(&id).await;
                        session_id = id;
                        let title = if stored_title.is_empty() {
                            title
                        } else {
                            stored_title
                        };
                        app.switch_session(history, &title);
                    }
                    Action::NewSession => {
                        let id = new_session_id(&terminal_id);
                        let _ = hub.lock().await.set_active_session(&id).await;
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
                match (pending_kind, result) {
                    (TaskKind::Chat, Ok(Ok(outcome))) => {
                        app.complete_success(outcome.messages, outcome.answer)
                    }
                    (TaskKind::Chat, Ok(Err(failure))) => {
                        let _ = hub
                            .lock()
                            .await
                            .save_history(&session_id, &failure.messages)
                            .await;
                        app.complete_error(failure.error, Some(failure.messages))
                    }
                    (TaskKind::Compact, Ok(Ok(outcome))) => {
                        let _ = hub
                            .lock()
                            .await
                            .save_history(&session_id, &outcome.messages)
                            .await;
                        app.complete_compact(outcome.answer)
                    }
                    (TaskKind::Compact, Ok(Err(failure))) => {
                        app.complete_error(failure.error, None)
                    }
                    (_, Err(error)) if error.is_cancelled() => {}
                    (_, Err(_)) => {
                        app.complete_error("Request failed unexpectedly.".to_owned(), None)
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
