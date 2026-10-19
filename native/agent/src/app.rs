use std::time::{Duration, Instant};

use crossterm::event::{Event, KeyCode, KeyEvent, KeyEventKind, KeyModifiers};

use crate::input::Input;
use crate::model::{Entry, Message};

pub const HELP: &str = "agent - terminal AI chat configured in the file manager

Enter: send   Shift+Enter / Ctrl+J: newline   Ctrl+C: cancel request / exit   Ctrl+Q: exit
Left/Right/Home/End: move   Up/Down: previous/next line   Ctrl+U: clear input   Ctrl+W: delete word
PageUp/PageDown: scroll   /clear: clear conversation   /help: shortcuts   /exit: exit

Set the API base URL, API key and model in the app's AI settings first.";

const DEFAULT_NOTICE: &str = "Enter send · Shift+Enter newline · PgUp/PgDn scroll · Ctrl+Q exit";
const WAITING_NOTICE: &str = "Waiting for the reply · Ctrl+C cancels · Ctrl+Q exits";
const INPUT_LIMIT: &str = "Message reached the 64 KiB input limit.";

pub enum Action {
    None,
    Quit,
    Cancel,
    Submit(Vec<Message>),
}

struct Pending {
    submitted: String,
    started: Instant,
}

pub struct App {
    model: String,
    context: String,
    transcript: Vec<Entry>,
    history: Vec<Message>,
    input: Input,
    pending: Option<Pending>,
    notice: String,
    revision: u64,
    follow: bool,
    scroll: usize,
    content_height: usize,
    viewport_height: usize,
}

impl App {
    pub fn new(model: String, context: String) -> Self {
        let transcript = vec![Entry::notice(format!(
            "Ready. Context: {}. Type a message; /help shows shortcuts.",
            context
        ))];
        Self {
            model,
            context,
            transcript,
            history: Vec::new(),
            input: Input::default(),
            pending: None,
            notice: DEFAULT_NOTICE.to_owned(),
            revision: 0,
            follow: true,
            scroll: 0,
            content_height: 0,
            viewport_height: 0,
        }
    }

    pub fn handle_event(&mut self, event: Event) -> Action {
        match event {
            Event::Key(key) => self.handle_key(key),
            Event::Paste(value) => self.insert_text(&value),
            _ => Action::None,
        }
    }

    fn handle_key(&mut self, key: KeyEvent) -> Action {
        if key.kind != KeyEventKind::Press {
            return Action::None;
        }
        let control = key.modifiers.contains(KeyModifiers::CONTROL);
        let alt = key.modifiers.contains(KeyModifiers::ALT);
        let shift = key.modifiers.contains(KeyModifiers::SHIFT);
        match key.code {
            KeyCode::Char(c) if control => match c.to_ascii_lowercase() {
                'c' => self.interrupt(),
                'q' | 'd' => Action::Quit,
                'u' => {
                    self.input.clear();
                    Action::None
                }
                'w' => {
                    self.input.delete_word();
                    Action::None
                }
                'a' => {
                    self.input.home();
                    Action::None
                }
                'e' => {
                    self.input.end();
                    Action::None
                }
                'j' => self.insert_text("\n"),
                _ => Action::None,
            },
            KeyCode::Enter if control || alt || shift => self.insert_text("\n"),
            KeyCode::Enter => self.submit(),
            KeyCode::Backspace if control || alt => {
                self.input.delete_word();
                Action::None
            }
            KeyCode::Backspace => {
                self.input.backspace();
                Action::None
            }
            KeyCode::Delete => {
                self.input.delete();
                Action::None
            }
            KeyCode::Left => {
                self.input.left();
                Action::None
            }
            KeyCode::Right => {
                self.input.right();
                Action::None
            }
            KeyCode::Home => {
                self.input.home();
                Action::None
            }
            KeyCode::End => {
                self.input.end();
                Action::None
            }
            KeyCode::Up => {
                self.input.up();
                Action::None
            }
            KeyCode::Down => {
                self.input.down();
                Action::None
            }
            KeyCode::PageUp => {
                self.scroll_page(true);
                Action::None
            }
            KeyCode::PageDown => {
                self.scroll_page(false);
                Action::None
            }
            KeyCode::Esc => {
                if self.pending.is_some() {
                    self.cancel();
                    Action::Cancel
                } else {
                    Action::None
                }
            }
            KeyCode::Tab => self.insert_text("    "),
            KeyCode::Char(c) if !control && !alt => self.insert_text(&c.to_string()),
            _ => Action::None,
        }
    }

    fn insert_text(&mut self, value: &str) -> Action {
        if !self.input.insert(value) {
            self.notice = INPUT_LIMIT.to_owned();
        }
        Action::None
    }

    fn interrupt(&mut self) -> Action {
        if self.pending.is_some() {
            self.cancel();
            Action::Cancel
        } else {
            Action::Quit
        }
    }

    fn cancel(&mut self) {
        let Some(pending) = self.pending.take() else {
            return;
        };
        if self.input.is_empty() {
            self.input.set(pending.submitted);
        }
        self.transcript.push(Entry::notice("Request cancelled."));
        self.notice = "Request cancelled. Enter retries; Ctrl+C exits.".to_owned();
        self.follow = true;
        self.bump();
    }

    fn submit(&mut self) -> Action {
        if self.pending.is_some() {
            self.notice = WAITING_NOTICE.to_owned();
            return Action::None;
        }
        let text = self.input.text().trim().to_owned();
        match text.as_str() {
            "" => return Action::None,
            "/exit" | "/quit" => return Action::Quit,
            "/clear" => {
                self.history.clear();
                self.transcript.clear();
                self.input.clear();
                self.follow = true;
                self.scroll = 0;
                self.notice = "Conversation cleared.".to_owned();
                self.bump();
                return Action::None;
            }
            "/help" => {
                self.transcript.push(Entry::notice(HELP));
                self.input.clear();
                self.follow = true;
                self.notice = DEFAULT_NOTICE.to_owned();
                self.bump();
                return Action::None;
            }
            _ => {}
        }
        let submitted = self.input.take();
        self.transcript.push(Entry::user(submitted.clone()));
        let mut messages = self.history.clone();
        messages.push(Message::user(submitted.clone()));
        self.pending = Some(Pending {
            submitted,
            started: Instant::now(),
        });
        self.notice = WAITING_NOTICE.to_owned();
        self.follow = true;
        self.bump();
        Action::Submit(messages)
    }

    pub fn complete_success(&mut self, answer: String) {
        let Some(pending) = self.pending.take() else {
            return;
        };
        self.history.push(Message::user(pending.submitted));
        self.history.push(Message::assistant(answer.clone()));
        self.transcript.push(Entry::assistant(answer));
        self.notice = DEFAULT_NOTICE.to_owned();
        self.follow = true;
        self.bump();
    }

    pub fn complete_error(&mut self, error: String) {
        let Some(pending) = self.pending.take() else {
            return;
        };
        self.transcript.push(Entry::error(error));
        if self.input.is_empty() {
            self.input.set(pending.submitted);
        }
        self.notice = "Request failed; edit your message and press Enter to retry.".to_owned();
        self.follow = true;
        self.bump();
    }

    pub fn sync_view(&mut self, content_height: usize, viewport_height: usize) {
        self.content_height = content_height;
        self.viewport_height = viewport_height;
        let maximum = content_height.saturating_sub(viewport_height);
        if self.follow {
            self.scroll = maximum;
        } else {
            self.scroll = self.scroll.min(maximum);
            if self.scroll == maximum {
                self.follow = true;
            }
        }
    }

    pub fn scroll_page(&mut self, up: bool) {
        let maximum = self.content_height.saturating_sub(self.viewport_height);
        if maximum == 0 {
            self.scroll = 0;
            self.follow = true;
            return;
        }
        let page = self.viewport_height.saturating_sub(1).max(1);
        if self.follow {
            self.scroll = maximum;
        }
        if up {
            self.follow = false;
            self.scroll = self.scroll.saturating_sub(page);
        } else {
            self.scroll = (self.scroll + page).min(maximum);
            if self.scroll >= maximum {
                self.follow = true;
            }
        }
    }

    pub fn model(&self) -> &str {
        &self.model
    }

    pub fn context(&self) -> &str {
        &self.context
    }

    pub fn transcript(&self) -> &[Entry] {
        &self.transcript
    }

    pub fn input(&self) -> &Input {
        &self.input
    }

    pub fn notice(&self) -> &str {
        &self.notice
    }

    pub fn revision(&self) -> u64 {
        self.revision
    }

    pub fn follow(&self) -> bool {
        self.follow
    }

    pub fn scroll(&self) -> usize {
        self.scroll
    }

    pub fn is_pending(&self) -> bool {
        self.pending.is_some()
    }

    pub fn pending_elapsed(&self) -> Option<Duration> {
        self.pending
            .as_ref()
            .map(|pending| pending.started.elapsed())
    }

    fn bump(&mut self) {
        self.revision = self.revision.wrapping_add(1);
    }
}
