mod api;
mod app;
mod config;
mod hub;
mod input;
mod markdown;
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
use std::time::Duration;

use tokio::signal::unix::{signal, SignalKind};
use tokio::sync::Mutex;

use crate::api::Api;
use crate::app::{Action, App, HELP};
use crate::hub::Hub;
use crate::model::AgentEvent;
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
    let (config, hub) = config::load().await?;
    let initial_provider = config.provider.clone();
    let initial_model = config.model.clone();
    let initial_effort = config.effort.clone();
    let api = Api::new(config)?;
    let context = hub.caller.clone();
    let hub = Arc::new(Mutex::new(hub));

    let mut terminate = signal(SignalKind::terminate())?;
    let mut interrupt = signal(SignalKind::interrupt())?;
    let mut hangup = signal(SignalKind::hangup())?;
    let mut quit = signal(SignalKind::quit())?;
    let mut suspend = signal(SignalKind::from_raw(libc::SIGTSTP))?;

    let (info_sender, mut info_receiver) = tokio::sync::mpsc::unbounded_channel();
    let info_api = api.clone();
    tokio::spawn(async move {
        let _ = info_sender.send(info_api.catalog().await);
    });
    let mut info_pending = true;

    let mut terminal = Terminal::enter()?;
    let mut app = App::new(context, initial_provider, initial_model, initial_effort);
    let mut state = UiState::default();
    let mut pending: Option<tokio::task::JoinHandle<Result<Outcome, runner::Failure>>> = None;
    let mut pending_kind = TaskKind::Chat;
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
                        }
                        events = None;
                    }
                    Action::Submit(messages) => {
                        if let Some(handle) = pending.take() {
                            handle.abort();
                        }
                        let (sender, receiver) = tokio::sync::mpsc::unbounded_channel();
                        events = Some(receiver);
                        pending_kind = TaskKind::Chat;
                        let api = api.clone();
                        let hub = Arc::clone(&hub);
                        let model = app.model().to_owned();
                        let effort = app.effort().to_owned();
                        pending = Some(tokio::spawn(async move {
                            runner::run(api, model, effort, hub, messages, sender).await
                        }));
                    }
                    Action::Compact(messages) => {
                        if let Some(handle) = pending.take() {
                            handle.abort();
                        }
                        events = None;
                        pending_kind = TaskKind::Compact;
                        let api = api.clone();
                        let model = app.model().to_owned();
                        let effort = app.effort().to_owned();
                        pending = Some(tokio::spawn(async move {
                            runner::compact(api, model, effort, messages)
                                .await
                                .map_err(|error| runner::Failure::new(error, &[]))
                        }));
                    }
                    Action::PersistSelection => {
                        persist_selection(&hub, app.model(), app.effort());
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
                        app.complete_error(failure.error, Some(failure.messages))
                    }
                    (TaskKind::Compact, Ok(Ok(outcome))) => app.complete_compact(outcome.answer),
                    (TaskKind::Compact, Ok(Err(failure))) => {
                        app.complete_error(failure.error, None)
                    }
                    (_, Err(error)) if error.is_cancelled() => {}
                    (_, Err(_)) => {
                        app.complete_error("Request failed unexpectedly.".to_owned(), None)
                    }
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
