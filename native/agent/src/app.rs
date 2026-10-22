use std::time::{Duration, Instant};

use crossterm::event::{
    Event, KeyCode, KeyEvent, KeyEventKind, KeyModifiers, MouseButton, MouseEvent, MouseEventKind,
};

use crate::hub::SessionEntry;
use crate::input::Input;
use crate::model::{Entry, Message, Role};
use crate::model_info::{ModelCatalog, ModelInfo};

mod events;
mod popup;
mod prompt;
mod view;

pub use popup::Popup;
use popup::{PopupAction, PopupKind};

pub const HELP: &str = "agent - terminal AI chat configured in the file manager

Enter: send   Shift+Enter / Ctrl+J: newline   Ctrl+C: cancel request / exit   Ctrl+Q: exit
Left/Right/Home/End: move   Up/Down: previous/next line; at first/last line: prompt history   Ctrl+U: clear input   Ctrl+W: delete word
PageUp/PageDown, Ctrl/Shift+Up/Down, mouse wheel: scroll   click a Thought header: toggle thinking
Ctrl+P: commands   /models: switch model   /variants: reasoning effort
/compact: summarize   /clear: clear   /undo: revert last message   /redo: restore it
/sessions: switch conversation   /help: shortcuts   /exit: exit

Configure an AI provider in the app's settings first.";

const SCROLL_LINES: usize = 3;
const WAITING_NOTICE: &str = "Waiting for the reply · Ctrl+C cancels · Ctrl+Q exits";
const INPUT_LIMIT: &str = "Message reached the 64 KiB input limit.";
pub(super) const GENERATING_CALL: &str = "~ Generating call";
const MAX_LIVE_TOOL_BYTES: usize = 1024 * 1024;

pub enum Action {
    None,
    Quit,
    Cancel,
    Submit(Vec<Message>),
    Compact(Vec<Message>),
    PersistSelection,
    PersistHistory(Vec<Message>),
    ListSessions,
    SelectSession { id: String, title: String },
    NewSession,
}

struct Pending {
    started: Instant,
}

struct UndoState {
    history: Vec<Message>,
    transcript: Vec<Entry>,
    input: String,
}

pub struct App {
    provider: String,
    model: String,
    effort: String,
    models: Vec<String>,
    infos: std::collections::HashMap<String, ModelInfo>,
    context: String,
    title: String,
    transcript: Vec<Entry>,
    history: Vec<Message>,
    prompts: Vec<String>,
    prompt_index: Option<usize>,
    prompt_draft: String,
    input: Input,
    pending: Option<Pending>,
    notice: String,
    revision: u64,
    follow: bool,
    scroll: usize,
    content_height: usize,
    viewport_height: usize,
    reasoning_active: Option<usize>,
    streaming_answer: bool,
    answer_streamed: bool,
    transcript_top: u16,
    transcript_height: u16,
    reasoning_headers: Vec<(usize, usize)>,
    tool_headers: Vec<(usize, usize)>,
    thinking_frame: usize,
    active_tool: Option<usize>,
    calling_tool: Option<usize>,
    undo: Vec<UndoState>,
    redo: Vec<UndoState>,
    compact_snapshot: Option<UndoState>,
    cwd: String,
    context_tokens: u64,
    prompt_tokens: u64,
    completion_tokens: u64,
    requests: u64,
    popup: Option<Popup>,
}

fn tool_line(name: &str, detail: &str) -> String {
    let label = tool_label(name);
    let arrow = tool_arrow(name);
    if detail.is_empty() {
        format!("{arrow} {label}")
    } else {
        format!("{arrow} {label} {detail}")
    }
}

fn tool_arrow(name: &str) -> &'static str {
    crate::tool::arrow(name)
}

fn tool_label(name: &str) -> String {
    if let Some(rest) = name.strip_prefix("mcp__") {
        let (server, tool) = rest.split_once("__").unwrap_or((rest, ""));
        if !tool.is_empty() {
            return format!("MCP {server}: {}", tool.replace('_', " "));
        }
    }
    let mut chars = name.chars();
    let Some(first) = chars.next() else {
        return String::new();
    };
    let mut label = first.to_uppercase().collect::<String>();
    label.push_str(&chars.as_str().replace('_', " "));
    label
}

fn transcript_from_history(history: &[Message]) -> Vec<Entry> {
    history
        .iter()
        .filter_map(|message| {
            let content = message.content.as_deref()?.to_owned();
            match message.role.as_str() {
                "user" => Some(Entry::user(content)),
                "assistant" => Some(Entry::assistant(content)),
                _ => None,
            }
        })
        .collect()
}

impl App {
    pub fn new(
        context: String,
        provider: String,
        model: String,
        effort: String,
        history: Vec<Message>,
        title: String,
    ) -> Self {
        let cwd = std::env::current_dir()
            .map(|path| path.display().to_string())
            .unwrap_or_else(|_| context.clone());
        let transcript = transcript_from_history(&history);
        Self {
            provider,
            model,
            effort: if effort.is_empty() {
                "high".to_owned()
            } else {
                effort
            },
            models: Vec::new(),
            infos: std::collections::HashMap::new(),
            context,
            title: if title.is_empty() {
                "New conversation".to_owned()
            } else {
                title
            },
            transcript,
            history,
            prompts: Vec::new(),
            prompt_index: None,
            prompt_draft: String::new(),
            input: Input::default(),
            pending: None,
            notice: String::new(),
            revision: 0,
            follow: true,
            scroll: 0,
            content_height: 0,
            viewport_height: 0,
            reasoning_active: None,
            streaming_answer: false,
            answer_streamed: false,
            transcript_top: 0,
            transcript_height: 0,
            reasoning_headers: Vec::new(),
            tool_headers: Vec::new(),
            thinking_frame: 0,
            active_tool: None,
            calling_tool: None,
            undo: Vec::new(),
            redo: Vec::new(),
            compact_snapshot: None,
            cwd,
            context_tokens: 0,
            prompt_tokens: 0,
            completion_tokens: 0,
            requests: 0,
            popup: None,
        }
    }

    pub fn cwd(&self) -> &str {
        &self.cwd
    }

    pub fn title(&self) -> &str {
        &self.title
    }

    pub fn set_title(&mut self, title: String) {
        let title = title.trim();
        if title.is_empty() || title == self.title {
            return;
        }
        self.title = title.to_owned();
        self.bump();
    }

    pub fn context_tokens(&self) -> u64 {
        self.context_tokens
    }

    pub fn effort(&self) -> &str {
        &self.effort
    }

    fn info(&self) -> Option<&ModelInfo> {
        self.infos.get(&self.model)
    }

    pub fn context_window(&self) -> Option<u64> {
        self.info().and_then(|info| info.context_window)
    }

    pub fn cost(&self) -> Option<f64> {
        let info = self.info()?;
        if let Some(price) = info.request_price {
            return Some(price * self.requests as f64);
        }
        let input = info.input_per_million?;
        let output = info.output_per_million.unwrap_or(input);
        Some(
            self.prompt_tokens as f64 / 1_000_000.0 * input
                + self.completion_tokens as f64 / 1_000_000.0 * output,
        )
    }

    pub fn set_catalog(&mut self, catalog: ModelCatalog) {
        self.models = catalog.models;
        self.infos = catalog.info;
        if !self.models.is_empty() && !self.models.iter().any(|model| model == &self.model) {
            self.model = self.models[0].clone();
        }
        self.bump();
    }

    pub fn popup(&self) -> Option<&Popup> {
        self.popup.as_ref()
    }

    pub fn handle_event(&mut self, event: Event) -> Action {
        match event {
            Event::Key(key) => self.handle_key(key),
            Event::Mouse(mouse) => self.handle_mouse(mouse),
            Event::Paste(value) => self.insert_text(&value),
            _ => Action::None,
        }
    }

    fn handle_mouse(&mut self, mouse: MouseEvent) -> Action {
        match mouse.kind {
            MouseEventKind::ScrollUp => {
                self.scroll_lines(true, SCROLL_LINES);
                Action::None
            }
            MouseEventKind::ScrollDown => {
                self.scroll_lines(false, SCROLL_LINES);
                Action::None
            }
            MouseEventKind::Down(MouseButton::Left) => {
                self.toggle_at(mouse.row);
                Action::None
            }
            _ => Action::None,
        }
    }

    fn handle_key(&mut self, key: KeyEvent) -> Action {
        if key.kind != KeyEventKind::Press {
            return Action::None;
        }
        if self.popup.is_some() {
            return self.handle_popup_key(key);
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
                    self.detach_prompt_browse();
                    Action::None
                }
                'w' => {
                    self.input.delete_word();
                    self.detach_prompt_browse();
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
                'p' => {
                    self.open_commands();
                    Action::None
                }
                _ => Action::None,
            },
            KeyCode::Enter if control || alt || shift => self.insert_text("\n"),
            KeyCode::Enter => self.submit(),
            KeyCode::Backspace if control || alt => {
                self.input.delete_word();
                self.detach_prompt_browse();
                Action::None
            }
            KeyCode::Backspace => {
                self.input.backspace();
                self.detach_prompt_browse();
                Action::None
            }
            KeyCode::Delete => {
                self.input.delete();
                self.detach_prompt_browse();
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
            KeyCode::Up if control || alt || shift => {
                self.scroll_lines(true, SCROLL_LINES);
                Action::None
            }
            KeyCode::Down if control || alt || shift => {
                self.scroll_lines(false, SCROLL_LINES);
                Action::None
            }
            KeyCode::Up => {
                if !self.input.up() {
                    self.previous_prompt();
                }
                Action::None
            }
            KeyCode::Down => {
                if !self.input.down() {
                    self.next_prompt();
                }
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

    fn handle_popup_key(&mut self, key: KeyEvent) -> Action {
        let control = key.modifiers.contains(KeyModifiers::CONTROL);
        match key.code {
            KeyCode::Esc => {
                self.popup = None;
                Action::None
            }
            KeyCode::Char('p') if control => {
                self.popup = None;
                Action::None
            }
            KeyCode::Up | KeyCode::Char('k') => {
                if let Some(popup) = self.popup.as_mut() {
                    popup.selected = popup.selected.saturating_sub(1);
                }
                Action::None
            }
            KeyCode::Down | KeyCode::Char('j') => {
                if let Some(popup) = self.popup.as_mut() {
                    popup.selected = (popup.selected + 1) % popup.items.len().max(1);
                }
                Action::None
            }
            KeyCode::Enter => {
                let Some(popup) = self.popup.take() else {
                    return Action::None;
                };
                let Some(item) = popup.items.get(popup.selected) else {
                    return Action::None;
                };
                match popup.kind {
                    PopupKind::Commands => match item.action {
                        Some(PopupAction::Compact) => self.begin_compact().unwrap_or(Action::None),
                        Some(PopupAction::Models) => {
                            self.open_models();
                            Action::None
                        }
                        Some(PopupAction::Variants) => {
                            self.open_variants();
                            Action::None
                        }
                        Some(PopupAction::Sessions) => Action::ListSessions,
                        Some(PopupAction::Clear) => {
                            if self.pending.is_some() {
                                self.notice = "Cancel the request before clearing.".to_owned();
                            } else {
                                self.clear_conversation();
                            }
                            Action::None
                        }
                        Some(PopupAction::Help) => {
                            self.show_help();
                            Action::None
                        }
                        Some(PopupAction::Exit) => Action::Quit,
                        None | Some(PopupAction::NewSession) => Action::None,
                    },
                    PopupKind::Models => {
                        self.model = item.label.clone();
                        self.bump();
                        Action::PersistSelection
                    }
                    PopupKind::Variants => {
                        self.effort = item.label.clone();
                        self.bump();
                        Action::PersistSelection
                    }
                    PopupKind::Sessions => match item.session.clone() {
                        Some(id) => Action::SelectSession {
                            id,
                            title: item.label.clone(),
                        },
                        None => Action::NewSession,
                    },
                }
            }
            _ => Action::None,
        }
    }

    fn open_commands(&mut self) {
        self.popup = Some(Popup::commands());
    }

    pub(super) fn open_sessions(&mut self, sessions: &[SessionEntry], current: &str) {
        self.popup = Some(Popup::sessions(sessions, current));
    }

    pub(super) fn switch_session(&mut self, history: Vec<Message>, title: &str) {
        self.title = if title.is_empty() {
            "New conversation".to_owned()
        } else {
            title.to_owned()
        };
        self.transcript = transcript_from_history(&history);
        self.history = history;
        self.undo.clear();
        self.redo.clear();
        self.compact_snapshot = None;
        self.detach_prompt_browse();
        self.input.clear();
        self.reasoning_active = None;
        self.active_tool = None;
        self.calling_tool = None;
        self.streaming_answer = false;
        self.answer_streamed = false;
        self.follow = true;
        self.scroll = 0;
        self.notice = format!("Session: {}", self.title);
        self.bump();
    }

    fn open_models(&mut self) {
        if self.models.is_empty() {
            self.notice = "No models available yet.".to_owned();
            return;
        }
        self.popup = Some(Popup::models(&self.models, &self.model, &self.provider));
    }

    fn open_variants(&mut self) {
        self.popup = Some(Popup::variants(&self.effort));
    }

    fn begin_compact(&mut self) -> Option<Action> {
        if self.pending.is_some() {
            self.notice = "Wait for the current request to finish.".to_owned();
            return None;
        }
        if self.model.is_empty() {
            self.notice = "No model selected yet.".to_owned();
            return None;
        }
        if self.history.is_empty() {
            self.notice = "Nothing to compact.".to_owned();
            return None;
        }
        self.compact_snapshot = Some(UndoState {
            history: self.history.clone(),
            transcript: self.transcript.clone(),
            input: self.input.text().to_owned(),
        });
        self.pending = Some(Pending {
            started: Instant::now(),
        });
        self.notice = "Compacting conversation...".to_owned();
        self.follow = true;
        self.bump();
        Some(Action::Compact(self.history.clone()))
    }

    pub fn tick(&mut self) {
        if self.reasoning_active.is_some() {
            self.thinking_frame = self.thinking_frame.wrapping_add(1);
            self.bump();
        }
    }

    pub fn thinking_frame(&self) -> usize {
        self.thinking_frame
    }

    fn toggle_reasoning(&mut self, index: usize) {
        if let Some(entry) = self.transcript.get_mut(index) {
            if entry.role == Role::Reasoning {
                entry.expanded = !entry.expanded;
                self.bump();
            }
        }
    }

    fn toggle_tool(&mut self, index: usize) {
        if let Some(entry) = self.transcript.get_mut(index) {
            if entry.role == Role::Tool && entry.tool.is_some() {
                entry.expanded = !entry.expanded;
                self.bump();
            }
        }
    }

    pub fn model(&self) -> &str {
        &self.model
    }

    pub fn provider(&self) -> &str {
        &self.provider
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
