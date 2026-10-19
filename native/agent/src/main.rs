mod api;
mod app;
mod config;
mod input;
mod markdown;
mod model;
mod terminal;
mod text;
mod theme;
mod ui;

use std::env;
use std::io;
use std::time::Duration;

use tokio::signal::unix::{signal, SignalKind};

use crate::api::Api;
use crate::app::{Action, App, HELP};
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

async fn run() -> io::Result<()> {
    let (config, context) = config::load()?;
    let api = Api::new(config)?;

    let mut terminate = signal(SignalKind::terminate())?;
    let mut interrupt = signal(SignalKind::interrupt())?;
    let mut hangup = signal(SignalKind::hangup())?;
    let mut quit = signal(SignalKind::quit())?;
    let mut suspend = signal(SignalKind::from_raw(libc::SIGTSTP))?;

    let mut terminal = Terminal::enter()?;
    let mut app = App::new(api.model().to_owned(), context.container_id);
    let mut state = UiState::default();
    let mut pending: Option<tokio::task::JoinHandle<Result<String, String>>> = None;
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
                    }
                    Action::Submit(messages) => {
                        if let Some(handle) = pending.take() {
                            handle.abort();
                        }
                        let api = api.clone();
                        pending = Some(tokio::spawn(async move { api.complete(messages).await }));
                    }
                }
            }
            result = async { pending.as_mut().unwrap().await }, if pending.is_some() => {
                pending = None;
                match result {
                    Ok(Ok(answer)) => app.complete_success(answer),
                    Ok(Err(error)) => app.complete_error(error),
                    Err(error) if error.is_cancelled() => {}
                    Err(_) => app.complete_error("Request failed unexpectedly.".to_owned()),
                }
            }
            _ = ticker.tick(), if pending.is_some() => {}
        }
    }

    if let Some(handle) = pending.take() {
        handle.abort();
    }
    Ok(())
}
