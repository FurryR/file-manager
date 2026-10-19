use std::io::{self, IsTerminal, Stdout};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::thread::{self, JoinHandle};
use std::time::Duration;

use crossterm::cursor::{Hide, Show};
use crossterm::event::{self, DisableBracketedPaste, EnableBracketedPaste, Event};
use crossterm::execute;
use crossterm::terminal::{
    disable_raw_mode, enable_raw_mode, EnterAlternateScreen, LeaveAlternateScreen,
};
use ratatui::backend::CrosstermBackend;
use ratatui::{Frame, Terminal as RatatuiTerminal};
use tokio::sync::mpsc::{self, UnboundedReceiver};

pub struct Terminal {
    terminal: RatatuiTerminal<CrosstermBackend<Stdout>>,
    events: UnboundedReceiver<Event>,
    stop: Arc<AtomicBool>,
    reader: Option<JoinHandle<()>>,
}

impl Terminal {
    pub fn enter() -> io::Result<Self> {
        if !io::stdin().is_terminal() || !io::stdout().is_terminal() {
            return Err(io::Error::other("an interactive terminal is required"));
        }

        enable_raw_mode()?;
        let mut stdout = io::stdout();
        if let Err(error) = execute!(
            &mut stdout,
            EnterAlternateScreen,
            EnableBracketedPaste,
            Hide
        ) {
            let _ = disable_raw_mode();
            return Err(error);
        }

        let backend = CrosstermBackend::new(stdout);
        let mut terminal = match RatatuiTerminal::new(backend) {
            Ok(terminal) => terminal,
            Err(error) => {
                restore();
                return Err(error);
            }
        };
        if let Err(error) = terminal.clear() {
            restore();
            return Err(error);
        }

        let (sender, events) = mpsc::unbounded_channel();
        let stop = Arc::new(AtomicBool::new(false));
        let reader_stop = Arc::clone(&stop);
        let reader = match thread::Builder::new()
            .name("agent-input".to_owned())
            .spawn(move || {
                while !reader_stop.load(Ordering::Relaxed) {
                    match event::poll(Duration::from_millis(50)) {
                        Ok(true) => match event::read() {
                            Ok(event) => {
                                if sender.send(event).is_err() {
                                    break;
                                }
                            }
                            Err(_) => break,
                        },
                        Ok(false) => {}
                        Err(_) => break,
                    }
                }
            }) {
            Ok(reader) => reader,
            Err(error) => {
                restore();
                return Err(error);
            }
        };

        Ok(Self {
            terminal,
            events,
            stop,
            reader: Some(reader),
        })
    }

    pub fn draw<F>(&mut self, render: F) -> io::Result<()>
    where
        F: FnOnce(&mut Frame),
    {
        self.terminal.draw(render)?;
        Ok(())
    }

    pub async fn next_event(&mut self) -> Option<Event> {
        self.events.recv().await
    }
}

impl Drop for Terminal {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        if let Some(reader) = self.reader.take() {
            let _ = reader.join();
        }
        restore();
    }
}

fn restore() {
    let _ = execute!(
        io::stdout(),
        Show,
        DisableBracketedPaste,
        LeaveAlternateScreen
    );
    let _ = disable_raw_mode();
}
