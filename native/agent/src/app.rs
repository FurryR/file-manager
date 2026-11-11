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

Enter: send   Shift+Enter / Ctrl+J: newline   Esc: stop the run and its commands   Ctrl+C: cancel request / exit   Ctrl+Q: exit
Enter while a request runs: hand it to the running turn, inserted after the current tool call
Left/Right/Home/End: move   Up/Down: previous/next line; at first/last line: prompt history   Ctrl+U: clear input   Ctrl+W: delete word
PageUp/PageDown, Ctrl/Shift+Up/Down, mouse wheel: scroll   click a Thought header: toggle thinking
Ctrl+P: commands   /models: switch model (type to search)   /effort: reasoning effort
/compact: summarize without hiding history   /clear: clear   /undo: interrupt and revert
/redo: restore it   /new: new conversation   /sessions: switch conversation
/yolo: auto-approve every tool in this terminal
/help: shortcuts   /exit: exit

Configure an AI provider in the app's settings first.";

pub const COMMANDS: [(&str, &str); 11] = [
    ("/compact", "Summarize the conversation into a new context"),
    ("/effort", "Switch reasoning effort"),
    ("/models", "Switch model"),
    ("/new", "Start a new conversation"),
    ("/sessions", "Switch conversation"),
    ("/undo", "Revert the last message"),
    ("/redo", "Restore the reverted message"),
    ("/clear", "Clear the conversation"),
    ("/yolo", "Auto-approve every tool in this terminal"),
    ("/help", "Show keyboard shortcuts"),
    ("/exit", "Exit the agent"),
];

const SCROLL_LINES: usize = 3;
pub(super) const GENERATING_CALL: &str = "~ Generating call";
const MAX_LIVE_TOOL_BYTES: usize = 1024 * 1024;

pub enum Action {
    None,
    Quit,
    Cancel,
    /// Cancel the current run, then persist the restored history.
    CancelAndPersist(Vec<Message>),
    Submit(Vec<Message>),
    /// A prompt typed while a run is working. It goes to the agent loop right
    /// away and is spliced into the conversation at the next gap, instead of
    /// waiting for the run to finish.
    Steer(String),
    Compact(Vec<Message>),
    PersistSelection,
    PersistHistory(Vec<Message>),
    ListSessions,
    SelectSession { id: String, title: String },
    NewSession,
    Approval { id: u64, allow: bool },
}

/// The input block becomes this confirmation while a tool call is waiting.
pub struct ApprovalState {
    pub id: u64,
    pub name: String,
    pub detail: String,
    pub reason: String,
    pub selected: usize,
    pub confirm_always: bool,
}

/// Session token accounting, persisted by the app and restored on attach.
#[derive(Clone, Copy, Default)]
pub struct UsageState {
    pub context_tokens: u64,
    pub prompt_tokens: u64,
    pub completion_tokens: u64,
    pub requests: u64,
}

pub const APPROVAL_BUTTONS: [&str; 3] = ["Allow multiple", "Allow once", "Deny"];

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
    revision: u64,
    follow: bool,
    scroll: usize,
    content_height: usize,
    viewport_height: usize,
    reasoning_active: Option<usize>,
    streaming_index: Option<usize>,
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
    /// Auto-approve every approval request in this terminal. Never sent to the
    /// hub; the TUI answers requests as they arrive.
    yolo: bool,
    /// Rules accepted with "Allow multiple": tool name plus the path/command it
    /// asked about, so the same access is not asked twice in this session.
    allow_rules: Vec<String>,
    pub approval: Option<ApprovalState>,
    /// Prompts typed while a run is working and not yet inserted by the agent.
    /// They are drawn below the newest transcript line with a grey marker.
    queued: Vec<String>,
    esc_armed: bool,
    command_selection: usize,
    command_done: bool,
}

pub(super) fn approval_rule(name: &str, detail: &str) -> String {
    format!("{name}\u{0}{detail}")
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
        "read" | "glob" | "list_targets" => "→",
        _ => "←",
    }
}

fn tool_info(name: &str, arguments: &str) -> (String, Option<String>, Option<String>) {
    let args = serde_json::from_str::<serde_json::Value>(arguments).unwrap_or_default();
    let command = args.get("command").and_then(|value| value.as_str()).map(str::to_owned);
    let detail = match name {
        "read" | "write" => args.get("path").and_then(|value| value.as_str()).unwrap_or("?").to_owned(),
        "glob" => args.get("pattern").and_then(|value| value.as_str()).unwrap_or("?").to_owned(),
        "copy" => format!(
            "{} -> {}",
            args.get("from").and_then(|value| value.as_str()).unwrap_or("?"),
            args.get("to").and_then(|value| value.as_str()).unwrap_or("?"),
        ),
        "exec" => command.clone().unwrap_or_default(),
        "list_targets" => String::new(),
        _ => arguments.replace('\n', " ").chars().take(120).collect(),
    };
    let description = args
        .get("description")
        .and_then(|value| value.as_str())
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .map(str::to_owned);
    (detail, description, command)
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
    let mut transcript = Vec::new();
    let mut tool_entries = std::collections::HashMap::new();
    for message in history {
        match message.role.as_str() {
            "user" => {
                if let Some(content) = message
                    .content
                    .as_deref()
                    .filter(|text| !text.trim().is_empty())
                {
                    if message.compaction_summary == Some(true) {
                        transcript.push(Entry::compaction(compaction_body(content)));
                    } else {
                        transcript.push(Entry::user(content.to_owned()));
                    }
                }
            }
            "assistant" => {
                if let Some(reasoning) = message
                    .reasoning_content
                    .as_deref()
                    .filter(|text| !text.trim().is_empty())
                {
                    transcript.push(Entry::saved_reasoning(reasoning.to_owned()));
                }
                if let Some(content) = message
                    .content
                    .as_deref()
                    .filter(|text| !text.trim().is_empty())
                {
                    transcript.push(Entry::assistant(content.to_owned()));
                }
                if let Some(calls) = &message.tool_calls {
                    for call in calls {
                        let (detail, description, command) =
                            tool_info(&call.function.name, &call.function.arguments);
                        let mut entry = match command {
                            Some(command) => Entry::exec(description, command),
                            None => Entry::tool(tool_line(&call.function.name, &detail)),
                        };
                        // Restored entries are finished, not live.
                        entry.started = None;
                        tool_entries.insert(call.id.clone(), transcript.len());
                        transcript.push(entry);
                    }
                }
            }
            "tool" => {
                let Some(id) = message.tool_call_id.as_deref() else {
                    continue;
                };
                let Some(&index) = tool_entries.get(id) else {
                    continue;
                };
                let Some(entry) = transcript.get_mut(index) else {
                    continue;
                };
                let Some(tool) = entry.tool.as_mut() else {
                    continue;
                };
                let content = message.content.as_deref().unwrap_or_default();
                let (output, failed) = saved_tool_output(content);
                tool.output.extend_from_slice(output.as_bytes());
                tool.failed = failed;
            }
            _ => {}
        }
    }
    transcript
}

fn compaction_body(content: &str) -> String {
    content
        .strip_prefix("Summary of the earlier conversation (compacted):\n")
        .unwrap_or(content)
        .to_owned()
}

/// Split the tool's formatted result (`exit_code: N\n…`) into displayable
/// output and a failure flag.
fn saved_tool_output(result: &str) -> (String, bool) {
    let Some(rest) = result.strip_prefix("exit_code: ") else {
        // An exec result without an exit header is an error (timeout, hub
        // failure, cancelled call), never a successful command.
        return (result.to_owned(), true);
    };
    let (code, body) = rest.split_once('\n').unwrap_or((rest, ""));
    let failed = code.trim().parse::<i32>().is_ok_and(|code| code != 0);
    // Drop the wire-format section labels so a restored block looks like the
    // live exec block, which only receives the raw stream bytes.
    let body = body.strip_prefix("stdout:\n").unwrap_or(body);
    let body = body.strip_prefix("stderr:\n").unwrap_or(body);
    let output = body.replace("\nstderr:\n", "\n");
    (output.trim_end().to_owned(), failed)
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
            revision: 0,
            follow: true,
            scroll: 0,
            content_height: 0,
            viewport_height: 0,
            reasoning_active: None,
            streaming_index: None,
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
            yolo: false,
            allow_rules: Vec::new(),
            approval: None,
            queued: Vec::new(),
            esc_armed: false,
            command_selection: 0,
            command_done: false,
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

    pub fn yolo(&self) -> bool {
        self.yolo
    }

    fn info(&self) -> Option<&ModelInfo> {
        self.infos.get(&self.model)
    }

    pub fn context_window(&self) -> Option<u64> {
        self.info().and_then(|info| info.context_window)
    }

    pub fn efforts(&self) -> Vec<String> {
        self.info().map(|info| info.efforts.clone()).unwrap_or_default()
    }

    pub fn set_usage(&mut self, usage: UsageState) {
        self.context_tokens = usage.context_tokens;
        self.prompt_tokens = usage.prompt_tokens;
        self.completion_tokens = usage.completion_tokens;
        self.requests = usage.requests;
        self.bump();
    }

    pub fn queued(&self) -> &[String] {
        &self.queued
    }

    pub fn esc_armed(&self) -> bool {
        self.esc_armed
    }

    pub fn approval(&self) -> Option<&ApprovalState> {
        self.approval.as_ref()
    }

    pub fn command_active(&self) -> bool {
        let text = self.input.text();
        !self.command_done
            && text.starts_with('/')
            && !text.chars().any(char::is_whitespace)
    }

    /// Commands whose name starts with the current `/...` token.
    pub fn command_matches(&self) -> Vec<(&'static str, &'static str)> {
        let token = self.input.text();
        COMMANDS
            .iter()
            .copied()
            .filter(|(command, _)| command.starts_with(token))
            .collect()
    }

    pub fn command_selection(&self) -> usize {
        self.command_selection
    }

    fn reset_command_menu(&mut self) {
        self.command_selection = 0;
        self.command_done = false;
    }

    fn complete_command(&mut self) {
        let matches = self.command_matches();
        let Some((command, _)) = matches.get(self.command_selection) else {
            return;
        };
        self.input.set((*command).to_owned());
        self.command_done = true;
        self.command_selection = 0;
        self.bump();
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
        if self.approval.is_some() {
            return self.handle_approval_key(key);
        }
        if self.popup.is_some() {
            return self.handle_popup_key(key);
        }
        if self.command_active() {
            match key.code {
                KeyCode::Up => {
                    let total = self.command_matches().len();
                    if total > 0 {
                        self.command_selection = self.command_selection.saturating_sub(1);
                    }
                    return Action::None;
                }
                KeyCode::Down => {
                    let total = self.command_matches().len();
                    if total > 0 {
                        self.command_selection = (self.command_selection + 1) % total;
                    }
                    return Action::None;
                }
                KeyCode::Enter => {
                    if self.command_matches().is_empty() {
                        return Action::None;
                    }
                    self.complete_command();
                    return Action::None;
                }
                KeyCode::Tab => {
                    if !self.command_matches().is_empty() {
                        self.complete_command();
                    }
                    return Action::None;
                }
                KeyCode::Esc if self.pending.is_none() => {
                    self.command_done = true;
                    return Action::None;
                }
                KeyCode::Esc => {}
                _ => {}
            }
        }
        if self.pending.is_some() && key.code != KeyCode::Esc {
            self.esc_armed = false;
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
                    self.reset_command_menu();
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
                self.reset_command_menu();
                Action::None
            }
            KeyCode::Delete => {
                self.input.delete();
                self.detach_prompt_browse();
                self.reset_command_menu();
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
                    if self.esc_armed {
                        self.cancel();
                        Action::Cancel
                    } else {
                        // Two-step interrupt: the first Esc only arms it, so a
                        // stray keypress cannot kill a running build.
                        self.esc_armed = true;
                        self.bump();
                        Action::None
                    }
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
            KeyCode::Up | KeyCode::Char('k') if !self.popup_search_active() => {
                if let Some(popup) = self.popup.as_mut() {
                    popup.move_selection(false);
                }
                Action::None
            }
            KeyCode::Down | KeyCode::Char('j') if !self.popup_search_active() => {
                if let Some(popup) = self.popup.as_mut() {
                    popup.move_selection(true);
                }
                Action::None
            }
            KeyCode::Up => {
                if let Some(popup) = self.popup.as_mut() {
                    popup.move_selection(false);
                }
                Action::None
            }
            KeyCode::Down => {
                if let Some(popup) = self.popup.as_mut() {
                    popup.move_selection(true);
                }
                Action::None
            }
            KeyCode::Backspace if self.popup_search_active() => {
                if let Some(popup) = self.popup.as_mut() {
                    popup.query.pop();
                    popup.selected = 0;
                }
                Action::None
            }
            KeyCode::Char(c) if self.popup_search_active() && !control => {
                if let Some(popup) = self.popup.as_mut() {
                    popup.query.push(c);
                    popup.selected = 0;
                }
                Action::None
            }
            KeyCode::Enter => {
                let Some(popup) = self.popup.take() else {
                    return Action::None;
                };
                let Some(item) = popup.selected_item().cloned() else {
                    return Action::None;
                };
                match popup.kind {
                    PopupKind::Commands => match item.action {
                        Some(PopupAction::Compact) => self.begin_compact().unwrap_or(Action::None),
                        Some(PopupAction::Yolo) => self.toggle_yolo(),
                        Some(PopupAction::Models) => {
                            self.open_models();
                            Action::None
                        }
                        Some(PopupAction::Effort) => {
                            self.open_efforts();
                            Action::None
                        }
                        Some(PopupAction::Sessions) => Action::ListSessions,
                        Some(PopupAction::NewSession) => Action::NewSession,
                        Some(PopupAction::Undo) => {
                            self.input.clear();
                            return self.undo();
                        }
                        Some(PopupAction::Redo) => {
                            self.input.clear();
                            return self.redo();
                        }
                        Some(PopupAction::Clear) => {
                            if self.pending.is_none() {
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
                        let Some(model) = item.session else {
                            return Action::None;
                        };
                        self.model = model;
                        self.bump();
                        Action::PersistSelection
                    }
                    PopupKind::Effort => {
                        self.effort = item.label.clone();
                        self.bump();
                        Action::PersistSelection
                    }
                    PopupKind::Sessions => {
                        // Switching while a request runs would land its result
                        // and history writes on the wrong session.
                        if self.pending.is_some() {
                            return Action::None;
                        }
                        match item.session.clone() {
                            Some(id) => Action::SelectSession {
                                id,
                                title: item.label.clone(),
                            },
                            None => Action::NewSession,
                        }
                    }
                }
            }
            _ => Action::None,
        }
    }

    fn popup_search_active(&self) -> bool {
        self.popup.as_ref().is_some_and(|popup| popup.searchable)
    }

    fn handle_approval_key(&mut self, key: KeyEvent) -> Action {
        let control = key.modifiers.contains(KeyModifiers::CONTROL);
        let Some(approval) = self.approval.as_mut() else {
            return Action::None;
        };
        match key.code {
            KeyCode::Esc => {
                if approval.confirm_always {
                    approval.confirm_always = false;
                    self.bump();
                    Action::None
                } else {
                    let id = approval.id;
                    self.approval = None;
                    self.esc_armed = false;
                    self.bump();
                    Action::Approval { id, allow: false }
                }
            }
            KeyCode::Left | KeyCode::Char('h') => {
                if !approval.confirm_always {
                    approval.selected = approval.selected.saturating_sub(1);
                    self.bump();
                }
                Action::None
            }
            KeyCode::Right | KeyCode::Char('l') => {
                if !approval.confirm_always {
                    approval.selected = (approval.selected + 1) % APPROVAL_BUTTONS.len();
                    self.bump();
                }
                Action::None
            }
            KeyCode::Up | KeyCode::Down | KeyCode::Tab => {
                if !approval.confirm_always {
                    approval.selected = (approval.selected + 1) % APPROVAL_BUTTONS.len();
                    self.bump();
                }
                Action::None
            }
            KeyCode::Enter if !control => {
                if approval.confirm_always {
                    let id = approval.id;
                    let rule = approval_rule(&approval.name, &approval.detail);
                    self.allow_rules.push(rule);
                    self.approval = None;
                    self.bump();
                    return Action::Approval { id, allow: true };
                }
                match approval.selected {
                    0 => {
                        approval.confirm_always = true;
                        self.bump();
                        Action::None
                    }
                    1 => {
                        let id = approval.id;
                        self.approval = None;
                        self.bump();
                        Action::Approval { id, allow: true }
                    }
                    _ => {
                        let id = approval.id;
                        self.approval = None;
                        self.bump();
                        Action::Approval { id, allow: false }
                    }
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
        // A restored conversation has no in-memory undo history. Seed one
        // snapshot per user turn so /undo works on messages that predate this
        // terminal, walking back one turn at a time.
        self.undo.clear();
        self.redo.clear();
        for index in 0..history.len() {
            if history[index].role != "user" {
                continue;
            }
            let earlier = &history[..index];
            self.push_undo(UndoState {
                history: earlier.to_vec(),
                transcript: transcript_from_history(earlier),
                input: String::new(),
            });
        }
        self.transcript = transcript_from_history(&history);
        self.history = history;
        self.queued.clear();
        self.compact_snapshot = None;
        self.approval = None;
        self.detach_prompt_browse();
        self.input.clear();
        self.reasoning_active = None;
        self.active_tool = None;
        self.calling_tool = None;
        self.streaming_index = None;
        self.answer_streamed = false;
        self.esc_armed = false;
        self.follow = true;
        self.scroll = 0;
        self.bump();
    }

    pub fn open_approval(&mut self, id: u64, name: &str, detail: &str, reason: &str) {
        self.popup = None;
        self.approval = Some(ApprovalState {
            id,
            name: name.to_owned(),
            detail: detail.to_owned(),
            reason: reason.to_owned(),
            selected: 1,
            confirm_always: false,
        });
        self.bump();
    }

    pub fn auto_approval(&self, name: &str, detail: &str) -> bool {
        self.yolo
            || self
                .allow_rules
                .iter()
                .any(|rule| rule == &approval_rule(name, detail))
    }

    fn open_models(&mut self) {
        if self.models.is_empty() {
            return;
        }
        self.popup = Some(Popup::models(&self.models, &self.infos, &self.model));
    }

    fn open_efforts(&mut self) {
        self.popup = Some(Popup::efforts(&self.efforts(), &self.effort));
    }

    fn begin_compact(&mut self) -> Option<Action> {
        if self.pending.is_some() {
            return None;
        }
        if self.model.is_empty() {
            return None;
        }
        if self.history.is_empty() {
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
        self.follow = true;
        self.bump();
        Some(Action::Compact(self.history.clone()))
    }

    pub fn tick(&mut self) {
        // Only running entries that draw a braille frame need a new animation
        // step. Exec blocks do; plain tool/read lines do not.
        let exec_running = self
            .active_tool
            .and_then(|index| self.transcript.get(index))
            .is_some_and(|entry| entry.tool.is_some() && entry.started.is_some());
        // A pending run animates the interrupt hint in the status bar even
        // when the model is silent and no tool is drawing a spinner.
        if self.pending.is_some() || self.reasoning_active.is_some() || exec_running {
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
