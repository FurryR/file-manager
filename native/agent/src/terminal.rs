//! Terminal setup and the input source.
//!
//! Output goes through ratatui's crossterm backend. Input deliberately does not
//! use crossterm's reader: that one blocks, and on a hung-up pty it parks inside
//! a `read(2)` loop that only leaves on `WouldBlock`, so EOF (`0`) and `EIO` are
//! both ignored and the loop spins forever. Driven from an async `select!` it
//! would wedge the executor instead of just a thread.
//!
//! Here the terminal is a tokio `AsyncFd`: readiness is awaited, bytes are read
//! once, and `0`/`EIO` is simply "input is over". Bytes go through termwiz's
//! incremental `InputParser` and are converted to crossterm events, so the rest
//! of the UI keeps speaking one event type and never sees a byte stream.
use std::collections::VecDeque;
use std::io::{self, IsTerminal, Stdout};
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd};

use crossterm::cursor::{Hide, Show};
use crossterm::event::{
    DisableBracketedPaste, DisableMouseCapture, EnableBracketedPaste, EnableMouseCapture, Event,
    KeyCode, KeyEvent, KeyModifiers, MouseButton, MouseEvent, MouseEventKind,
};
use crossterm::execute;
use crossterm::terminal::{
    disable_raw_mode, enable_raw_mode, EnterAlternateScreen, LeaveAlternateScreen,
};
use ratatui::backend::CrosstermBackend;
use ratatui::{Frame, Terminal as RatatuiTerminal};
use termwiz::input::{
    InputEvent, InputParser, KeyCode as TwKeyCode, Modifiers as TwModifiers,
    MouseButtons as TwButtons,
};
use tokio::io::unix::AsyncFd;

/// One read(2) worth of input. A paste can be larger; it then arrives over
/// several reads and `InputParser` stitches it back together.
const INPUT_BUFFER: usize = 4096;

pub struct Terminal {
    terminal: RatatuiTerminal<CrosstermBackend<Stdout>>,
    input: AsyncFd<OwnedFd>,
    parser: InputParser,
    /// Parsed events waiting for the UI loop. One read can produce several.
    queue: VecDeque<Event>,
    /// Mouse buttons held down at the last report, so a button bit that clears
    /// can be reported as `Up` and one that stays set as `Drag`.
    pressed: TwButtons,
    buffer: Box<[u8; INPUT_BUFFER]>,
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
            EnableMouseCapture,
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

        let input = match input_source() {
            Ok(input) => input,
            Err(error) => {
                restore();
                return Err(error);
            }
        };

        Ok(Self {
            terminal,
            input,
            parser: InputParser::new(),
            queue: VecDeque::new(),
            pressed: TwButtons::NONE,
            buffer: Box::new([0u8; INPUT_BUFFER]),
        })
    }

    pub fn draw<F>(&mut self, render: F) -> io::Result<()>
    where
        F: FnOnce(&mut Frame),
    {
        self.terminal.draw(render)?;
        Ok(())
    }

    /// Next input event, or `None` once the terminal is gone.
    pub async fn next_event(&mut self) -> Option<Event> {
        loop {
            if let Some(event) = self.queue.pop_front() {
                return Some(event);
            }

            let mut guard = self.input.readable().await.ok()?;
            let count = match guard.try_io(|fd| read_once(fd.get_ref(), &mut self.buffer[..])) {
                Ok(Ok(count)) => count,
                // EIO and friends: the pty is gone, and so is our input.
                Ok(Err(_)) => return None,
                // Spurious readiness; `readable()` re-registers on the next call.
                Err(_would_block) => continue,
            };
            if count == 0 {
                return None;
            }

            // `maybe_more` stays false: a complete sequence is written in one
            // go, and waiting for more would swallow a lone Escape.
            for event in self.parser.parse_as_vec(&self.buffer[..count], false) {
                if let Some(event) = self.convert(event) {
                    self.queue.push_back(event);
                }
            }
        }
    }

    fn convert(&mut self, event: InputEvent) -> Option<Event> {
        match event {
            InputEvent::Key(key) => {
                let code = key_code(key.key)?;
                let modifiers = key_modifiers(key.modifiers);
                // Shift-Tab arrives as `CSI Z`, which termwiz decodes as
                // Tab+SHIFT and crossterm names BackTab; keep the UI's spelling.
                let code = if code == KeyCode::Tab && modifiers.contains(KeyModifiers::SHIFT) {
                    KeyCode::BackTab
                } else {
                    code
                };
                Some(Event::Key(KeyEvent::new(code, modifiers)))
            }
            InputEvent::Mouse(mouse) => self.mouse_event(mouse),
            InputEvent::Paste(text) => Some(Event::Paste(text)),
            InputEvent::Resized { cols, rows } => Some(Event::Resize(cols as u16, rows as u16)),
            _ => None,
        }
    }

    fn mouse_event(&mut self, mouse: termwiz::input::MouseEvent) -> Option<Event> {
        let buttons = mouse.mouse_buttons;
        let previous = std::mem::replace(&mut self.pressed, buttons.clone());
        let kind = if buttons.contains(TwButtons::VERT_WHEEL) {
            if buttons.contains(TwButtons::WHEEL_POSITIVE) {
                MouseEventKind::ScrollUp
            } else {
                MouseEventKind::ScrollDown
            }
        } else if buttons.contains(TwButtons::HORZ_WHEEL) {
            if buttons.contains(TwButtons::WHEEL_POSITIVE) {
                MouseEventKind::ScrollRight
            } else {
                MouseEventKind::ScrollLeft
            }
        } else {
            // termwiz reports the buttons that are down, not press/release, so
            // the transition is what distinguishes the two. Motion with a
            // button held is a drag; with none it is a move.
            match (button(previous), button(buttons)) {
                (None, Some(button)) => MouseEventKind::Down(button),
                (Some(button), None) => MouseEventKind::Up(button),
                (Some(button), Some(_)) => MouseEventKind::Drag(button),
                (None, None) => MouseEventKind::Moved,
            }
        };
        Some(Event::Mouse(MouseEvent {
            kind,
            // SGR reports cells counted from 1; crossterm and the UI from 0.
            column: mouse.x.saturating_sub(1),
            row: mouse.y.saturating_sub(1),
            modifiers: key_modifiers(mouse.modifiers),
        }))
    }
}

/// Read end of the keyboard, registered with tokio's reactor.
///
/// A fresh `/dev/tty` description instead of a `dup` of stdin on purpose:
/// `O_NONBLOCK` lives on the open file description, and when the shell handed
/// the app one description for fds 0..2 a `dup` would also make stdout
/// non-blocking, so a full pty buffer would turn a draw into `EAGAIN`.
fn input_source() -> io::Result<AsyncFd<OwnedFd>> {
    let raw = unsafe {
        let path = c"/dev/tty";
        libc::open(
            path.as_ptr(),
            libc::O_RDONLY | libc::O_NONBLOCK | libc::O_CLOEXEC,
        )
    };
    if raw >= 0 {
        return AsyncFd::new(unsafe { OwnedFd::from_raw_fd(raw) });
    }
    // `is_terminal` said stdin is a tty, so this only happens for a terminal
    // that is not the controlling one (setsid). Fall back to stdin and accept
    // that `O_NONBLOCK` then also covers fd 0's description.
    let raw = unsafe { libc::dup(libc::STDIN_FILENO) };
    if raw < 0 {
        return Err(io::Error::last_os_error());
    }
    let fd = unsafe { OwnedFd::from_raw_fd(raw) };
    let flags = unsafe { libc::fcntl(fd.as_raw_fd(), libc::F_GETFL) };
    if flags < 0
        || unsafe { libc::fcntl(fd.as_raw_fd(), libc::F_SETFL, flags | libc::O_NONBLOCK) } < 0
    {
        return Err(io::Error::last_os_error());
    }
    AsyncFd::new(fd)
}

fn read_once(fd: &OwnedFd, buffer: &mut [u8]) -> io::Result<usize> {
    let count = unsafe { libc::read(fd.as_raw_fd(), buffer.as_mut_ptr().cast(), buffer.len()) };
    if count < 0 {
        Err(io::Error::last_os_error())
    } else {
        Ok(count as usize)
    }
}

fn button(buttons: TwButtons) -> Option<MouseButton> {
    if buttons.contains(TwButtons::LEFT) {
        Some(MouseButton::Left)
    } else if buttons.contains(TwButtons::MIDDLE) {
        Some(MouseButton::Middle)
    } else if buttons.contains(TwButtons::RIGHT) {
        Some(MouseButton::Right)
    } else {
        None
    }
}

fn key_code(code: TwKeyCode) -> Option<KeyCode> {
    Some(match code {
        TwKeyCode::Char(c) => KeyCode::Char(c),
        TwKeyCode::Backspace => KeyCode::Backspace,
        TwKeyCode::Tab => KeyCode::Tab,
        TwKeyCode::Enter => KeyCode::Enter,
        TwKeyCode::Escape => KeyCode::Esc,
        TwKeyCode::Delete => KeyCode::Delete,
        TwKeyCode::Insert => KeyCode::Insert,
        TwKeyCode::Home => KeyCode::Home,
        TwKeyCode::End => KeyCode::End,
        TwKeyCode::PageUp => KeyCode::PageUp,
        TwKeyCode::PageDown => KeyCode::PageDown,
        TwKeyCode::LeftArrow => KeyCode::Left,
        TwKeyCode::RightArrow => KeyCode::Right,
        TwKeyCode::UpArrow => KeyCode::Up,
        TwKeyCode::DownArrow => KeyCode::Down,
        TwKeyCode::Function(n) => KeyCode::F(n),
        _ => return None,
    })
}

fn key_modifiers(modifiers: TwModifiers) -> KeyModifiers {
    let mut result = KeyModifiers::NONE;
    if modifiers.contains(TwModifiers::SHIFT) {
        result |= KeyModifiers::SHIFT;
    }
    if modifiers.contains(TwModifiers::CTRL) {
        result |= KeyModifiers::CONTROL;
    }
    if modifiers.contains(TwModifiers::ALT) {
        result |= KeyModifiers::ALT;
    }
    if modifiers.contains(TwModifiers::SUPER) {
        result |= KeyModifiers::SUPER;
    }
    result
}

/// True once the controlling terminal is gone.
///
/// The pty master can outlive the session leader (the app holds a duplicate of
/// it), so a hang-up does not always surface as EOF on the input descriptor.
/// `tcgetpgrp` fails with `EIO` only after the kernel has dropped the terminal,
/// which is the condition a terminal UI can act on: nothing can be read or
/// drawn any more, so the process must exit instead of waiting forever.
pub fn hung_up() -> bool {
    let foreground = unsafe { libc::tcgetpgrp(libc::STDIN_FILENO) };
    foreground < 0 && io::Error::last_os_error().raw_os_error() == Some(libc::EIO)
}

impl Drop for Terminal {
    fn drop(&mut self) {
        restore();
    }
}

fn restore() {
    let _ = execute!(
        io::stdout(),
        Show,
        DisableMouseCapture,
        DisableBracketedPaste,
        LeaveAlternateScreen
    );
    let _ = disable_raw_mode();
}
