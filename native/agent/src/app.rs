use std::time::{Duration, Instant};

use crossterm::event::{
    Event, KeyCode, KeyEvent, KeyEventKind, KeyModifiers, MouseButton, MouseEvent, MouseEventKind,
};

use crate::input::Input;
use crate::model::{AgentEvent, Entry, Message, Role};
use crate::model_info::{ModelCatalog, ModelInfo};

pub const HELP: &str = "agent - terminal AI chat configured in the file manager

Enter: send   Shift+Enter / Ctrl+J: newline   Ctrl+C: cancel request / exit   Ctrl+Q: exit
Left/Right/Home/End: move   Up/Down: previous/next line; at first/last line: prompt history   Ctrl+U: clear input   Ctrl+W: delete word
PageUp/PageDown, Ctrl/Shift+Up/Down, mouse wheel: scroll   click a Thought header: toggle thinking
Ctrl+P: commands   /models: switch model   /variants: reasoning effort
/compact: summarize the conversation   /clear: clear conversation   /help: shortcuts   /exit: exit

Configure an AI provider in the app's settings first.";

const SCROLL_LINES: usize = 3;
const WAITING_NOTICE: &str = "Waiting for the reply · Ctrl+C cancels · Ctrl+Q exits";
const INPUT_LIMIT: &str = "Message reached the 64 KiB input limit.";
const MAX_LIVE_TOOL_BYTES: usize = 1024 * 1024;

pub enum Action {
    None,
    Quit,
    Cancel,
    Submit(Vec<Message>),
    Compact(Vec<Message>),
    PersistSelection,
}

struct Pending {
    submitted: String,
    started: Instant,
}

pub const EFFORTS: [&str; 4] = ["auto", "low", "medium", "high"];

pub enum PopupKind {
    Commands,
    Models,
    Variants,
}

pub struct Popup {
    pub title: &'static str,
    pub kind: PopupKind,
    pub items: Vec<PopupItem>,
    pub selected: usize,
}

pub struct PopupItem {
    pub label: String,
    pub hint: String,
    action: Option<PopupAction>,
}

#[derive(Clone, Copy)]
enum PopupAction {
    Clear,
    Compact,
    Models,
    Variants,
    Help,
    Exit,
}

impl Popup {
    fn commands() -> Self {
        Self {
            title: "Commands",
            kind: PopupKind::Commands,
            items: vec![
                PopupItem {
                    label: "/compact".to_owned(),
                    hint: "Summarize the conversation".to_owned(),
                    action: Some(PopupAction::Compact),
                },
                PopupItem {
                    label: "/models".to_owned(),
                    hint: "Switch model".to_owned(),
                    action: Some(PopupAction::Models),
                },
                PopupItem {
                    label: "/variants".to_owned(),
                    hint: "Switch reasoning effort".to_owned(),
                    action: Some(PopupAction::Variants),
                },
                PopupItem {
                    label: "/clear".to_owned(),
                    hint: "Clear the conversation".to_owned(),
                    action: Some(PopupAction::Clear),
                },
                PopupItem {
                    label: "/help".to_owned(),
                    hint: "Show keyboard shortcuts".to_owned(),
                    action: Some(PopupAction::Help),
                },
                PopupItem {
                    label: "/exit".to_owned(),
                    hint: "Exit the agent".to_owned(),
                    action: Some(PopupAction::Exit),
                },
            ],
            selected: 0,
        }
    }

    fn models(models: &[String], current: &str, provider: &str) -> Self {
        let selected = models
            .iter()
            .position(|model| model == current)
            .unwrap_or(0);
        Self {
            title: "Models",
            kind: PopupKind::Models,
            items: models
                .iter()
                .map(|model| PopupItem {
                    label: model.clone(),
                    hint: provider.to_owned(),
                    action: None,
                })
                .collect(),
            selected,
        }
    }

    fn variants(current: &str) -> Self {
        let selected = EFFORTS
            .iter()
            .position(|effort| *effort == current)
            .unwrap_or(EFFORTS.len() - 1);
        let hints = [
            "Use the provider default",
            "Faster, less reasoning",
            "Balanced reasoning",
            "More reasoning",
        ];
        Self {
            title: "Reasoning effort",
            kind: PopupKind::Variants,
            items: EFFORTS
                .iter()
                .zip(hints)
                .map(|(effort, hint)| PopupItem {
                    label: (*effort).to_owned(),
                    hint: hint.to_owned(),
                    action: None,
                })
                .collect(),
            selected,
        }
    }
}

pub struct App {
    provider: String,
    model: String,
    effort: String,
    models: Vec<String>,
    infos: std::collections::HashMap<String, ModelInfo>,
    context: String,
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
    match name {
        "read" | "glob" | "list_containers" => "→",
        _ => "←",
    }
}

fn tool_label(name: &str) -> String {
    let mut chars = name.chars();
    let Some(first) = chars.next() else {
        return String::new();
    };
    let mut label = first.to_uppercase().collect::<String>();
    label.push_str(&chars.as_str().replace('_', " "));
    label
}

impl App {
    pub fn new(context: String, provider: String, model: String, effort: String) -> Self {
        let cwd = std::env::current_dir()
            .map(|path| path.display().to_string())
            .unwrap_or_else(|_| context.clone());
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
            transcript: Vec::new(),
            history: Vec::new(),
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
                        None => Action::None,
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
                }
            }
            _ => Action::None,
        }
    }

    fn open_commands(&mut self) {
        self.popup = Some(Popup::commands());
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
        self.pending = Some(Pending {
            submitted: String::new(),
            started: Instant::now(),
        });
        self.notice = "Compacting conversation...".to_owned();
        self.follow = true;
        self.bump();
        Some(Action::Compact(self.history.clone()))
    }

    fn clear_conversation(&mut self) {
        self.history.clear();
        self.transcript.clear();
        self.input.clear();
        self.prompt_index = None;
        self.prompt_draft.clear();
        self.reasoning_active = None;
        self.active_tool = None;
        self.calling_tool = None;
        self.streaming_answer = false;
        self.answer_streamed = false;
        self.follow = true;
        self.scroll = 0;
        self.notice = "Conversation cleared.".to_owned();
        self.bump();
    }

    fn show_help(&mut self) {
        self.transcript.push(Entry::notice(HELP));
        self.input.clear();
        self.follow = true;
        self.notice = String::new();
        self.bump();
    }

    fn insert_text(&mut self, value: &str) -> Action {
        self.detach_prompt_browse();
        if !self.input.insert(value) {
            self.notice = INPUT_LIMIT.to_owned();
        }
        Action::None
    }

    fn detach_prompt_browse(&mut self) {
        self.prompt_index = None;
        self.prompt_draft.clear();
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
        self.finish_reasoning();
        self.finish_tool(Some("cancelled"));
        self.finish_calling(Some("cancelled"));
        self.streaming_answer = false;
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
                self.clear_conversation();
                return Action::None;
            }
            "/help" => {
                self.show_help();
                return Action::None;
            }
            "/models" => {
                self.input.clear();
                self.open_models();
                return Action::None;
            }
            "/variants" => {
                self.input.clear();
                self.open_variants();
                return Action::None;
            }
            "/compact" => {
                self.input.clear();
                return self.begin_compact().unwrap_or(Action::None);
            }
            _ => {}
        }
        if self.model.is_empty() {
            self.notice = "No model selected yet.".to_owned();
            return Action::None;
        }
        let submitted = self.input.take();
        self.remember_prompt(&submitted);
        self.transcript.push(Entry::user(submitted.clone()));
        self.reasoning_active = None;
        self.active_tool = None;
        self.calling_tool = None;
        self.streaming_answer = false;
        self.answer_streamed = false;
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

    fn remember_prompt(&mut self, prompt: &str) {
        if self.prompts.last().map_or(true, |last| last != prompt) {
            self.prompts.push(prompt.to_owned());
        }
        self.detach_prompt_browse();
    }

    fn previous_prompt(&mut self) {
        let Some(last) = self.prompts.len().checked_sub(1) else {
            return;
        };
        let index = match self.prompt_index {
            Some(0) => return,
            Some(index) => index - 1,
            None => {
                self.prompt_draft = self.input.text().to_owned();
                last
            }
        };
        let text = self.prompts[index].clone();
        self.input.set(text);
        self.prompt_index = Some(index);
        self.bump();
    }

    fn next_prompt(&mut self) {
        let Some(index) = self.prompt_index else {
            return;
        };
        if index + 1 < self.prompts.len() {
            let text = self.prompts[index + 1].clone();
            self.input.set(text);
            self.prompt_index = Some(index + 1);
        } else {
            let draft = std::mem::take(&mut self.prompt_draft);
            self.input.set(draft);
            self.prompt_index = None;
        }
        self.bump();
    }

    pub fn complete_success(&mut self, messages: Vec<Message>, answer: String) {
        if self.pending.take().is_none() {
            return;
        }
        self.finish_reasoning();
        self.finish_tool(None);
        self.finish_calling(None);
        self.streaming_answer = false;
        self.history = messages;
        if !self.answer_streamed {
            self.transcript.push(Entry::assistant(answer));
        }
        self.notice = String::new();
        self.bump();
    }

    pub fn complete_compact(&mut self, summary: String) {
        if self.pending.take().is_none() {
            return;
        }
        self.finish_reasoning();
        self.finish_tool(None);
        self.finish_calling(None);
        self.streaming_answer = false;
        self.history = vec![Message::user(format!(
            "Summary of the earlier conversation (compacted):\n{summary}"
        ))];
        self.transcript.clear();
        self.transcript
            .push(Entry::notice("Conversation compacted."));
        self.notice = String::new();
        self.follow = true;
        self.bump();
    }

    pub fn agent_event(&mut self, event: AgentEvent) {
        match event {
            AgentEvent::StepStart => {
                self.finish_reasoning();
                self.finish_tool(None);
                self.finish_calling(None);
                self.streaming_answer = false;
                self.answer_streamed = false;
            }
            AgentEvent::ReasoningDelta(text) => {
                let index = match self.reasoning_active {
                    Some(index) if index < self.transcript.len() => index,
                    _ => {
                        let index = self.transcript.len();
                        self.transcript.push(Entry::reasoning());
                        self.reasoning_active = Some(index);
                        index
                    }
                };
                if let Some(entry) = self.transcript.get_mut(index) {
                    entry.content.push_str(&text);
                }
            }
            AgentEvent::AnswerDelta(text) => {
                self.finish_reasoning();
                self.answer_streamed = true;
                if !self.streaming_answer {
                    self.transcript.push(Entry::assistant(""));
                    self.streaming_answer = true;
                }
                if let Some(entry) = self.transcript.last_mut() {
                    entry.content.push_str(&text);
                }
            }
            AgentEvent::ToolCalling => {
                self.finish_reasoning();
                self.streaming_answer = false;
                if self.calling_tool.is_none() {
                    let index = self.transcript.len();
                    self.transcript.push(Entry::tool("Calling tools..."));
                    self.calling_tool = Some(index);
                }
            }
            AgentEvent::ToolCall {
                name,
                detail,
                description,
                command,
            } => {
                self.finish_reasoning();
                self.finish_tool(None);
                self.streaming_answer = false;
                let index = match self.calling_tool.take() {
                    Some(index) => index,
                    None => {
                        let index = self.transcript.len();
                        self.transcript.push(Entry::tool("Calling tools..."));
                        index
                    }
                };
                if let Some(command) = command {
                    if let Some(entry) = self.transcript.get_mut(index) {
                        *entry = Entry::exec(description, command);
                    }
                } else if let Some(entry) = self.transcript.get_mut(index) {
                    entry.content = tool_line(&name, &detail);
                    entry.started = Some(Instant::now());
                }
                self.active_tool = Some(index);
                self.notice = "Calling tools · Ctrl+C cancels · Ctrl+Q exits".to_owned();
            }
            AgentEvent::ToolOutput(bytes) => {
                if let Some(index) = self.active_tool {
                    if let Some(entry) = self.transcript.get_mut(index) {
                        if let Some(tool) = entry.tool.as_mut() {
                            if tool.output.len() < MAX_LIVE_TOOL_BYTES {
                                let take =
                                    (MAX_LIVE_TOOL_BYTES - tool.output.len()).min(bytes.len());
                                tool.output.extend_from_slice(&bytes[..take]);
                            }
                        }
                    }
                }
            }
            AgentEvent::ToolResult { ok, .. } => {
                self.finish_tool((!ok).then_some("failed"));
                self.notice = WAITING_NOTICE.to_owned();
            }
            AgentEvent::Usage(usage) => {
                self.context_tokens = usage.prompt_tokens;
                self.prompt_tokens += usage.prompt_tokens;
                self.completion_tokens += usage.completion_tokens;
                self.requests += 1;
            }
        }
        self.bump();
    }

    pub fn complete_error(&mut self, error: String, messages: Option<Vec<Message>>) {
        if self.pending.take().is_none() {
            return;
        }
        self.finish_reasoning();
        self.finish_tool(Some("failed"));
        self.finish_calling(Some("failed"));
        self.streaming_answer = false;
        self.transcript.push(Entry::error(error));
        if let Some(messages) = messages {
            self.history = messages;
        }
        self.notice.clear();
        self.follow = true;
        self.bump();
    }

    fn finish_tool(&mut self, status: Option<&str>) {
        let Some(index) = self.active_tool.take() else {
            return;
        };
        if let Some(entry) = self.transcript.get_mut(index) {
            entry.started = None;
            if let Some(tool) = entry.tool.as_mut() {
                tool.failed = matches!(status, Some("failed" | "cancelled"));
            } else {
                entry.content = match status {
                    Some(status) => format!("{} ({status})", entry.content),
                    None => entry.content.clone(),
                };
            }
        }
    }

    fn finish_calling(&mut self, status: Option<&str>) {
        let Some(index) = self.calling_tool.take() else {
            return;
        };
        if let Some(entry) = self.transcript.get_mut(index) {
            entry.started = None;
            if entry.content == "Calling tools..." {
                entry.content = match status {
                    Some(status) => format!("← Tools ({status})"),
                    None => "← Tools".to_owned(),
                };
            }
        }
    }

    fn finish_reasoning(&mut self) {
        let Some(index) = self.reasoning_active.take() else {
            return;
        };
        if let Some(entry) = self.transcript.get_mut(index) {
            if entry.duration.is_none() {
                entry.duration = entry.started.map(|started| started.elapsed());
            }
            entry.started = None;
        }
    }

    fn toggle_at(&mut self, row: u16) {
        if row < self.transcript_top || row >= self.transcript_top + self.transcript_height {
            return;
        }
        let line = self.scroll + (row - self.transcript_top) as usize;
        if let Some(index) = self
            .reasoning_headers
            .iter()
            .find(|(header, _)| *header == line)
            .map(|(_, index)| *index)
        {
            self.toggle_reasoning(index);
            return;
        }
        if let Some(index) = self
            .tool_headers
            .iter()
            .find(|(header, _)| *header == line)
            .map(|(_, index)| *index)
        {
            self.toggle_tool(index);
        }
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

    pub fn set_transcript_map(
        &mut self,
        top: u16,
        height: u16,
        reasoning_headers: Vec<(usize, usize)>,
        tool_headers: Vec<(usize, usize)>,
    ) {
        self.transcript_top = top;
        self.transcript_height = height;
        self.reasoning_headers = reasoning_headers;
        self.tool_headers = tool_headers;
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
        let page = self.viewport_height.saturating_sub(1).max(1);
        self.scroll_by(page, up);
    }

    pub fn scroll_lines(&mut self, up: bool, lines: usize) {
        self.scroll_by(lines.max(1), up);
    }

    fn scroll_by(&mut self, amount: usize, up: bool) {
        let maximum = self.content_height.saturating_sub(self.viewport_height);
        if maximum == 0 {
            self.scroll = 0;
            self.follow = true;
            return;
        }
        if self.follow {
            self.scroll = maximum;
        }
        if up {
            self.follow = false;
            self.scroll = self.scroll.saturating_sub(amount);
        } else {
            self.scroll = (self.scroll + amount).min(maximum);
            if self.scroll >= maximum {
                self.follow = true;
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
