use std::time::Instant;

use crate::model::{Entry, Message};

use super::{Action, App, Pending, UndoState, HELP, INPUT_LIMIT, WAITING_NOTICE};

impl App {
    pub(super) fn clear_conversation(&mut self) {
        self.history.clear();
        self.transcript.clear();
        self.undo.clear();
        self.redo.clear();
        self.compact_snapshot = None;
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

    pub(super) fn show_help(&mut self) {
        self.transcript.push(Entry::notice(HELP));
        self.input.clear();
        self.follow = true;
        self.notice = String::new();
        self.bump();
    }

    pub(super) fn insert_text(&mut self, value: &str) -> Action {
        self.detach_prompt_browse();
        if !self.input.insert(value) {
            self.notice = INPUT_LIMIT.to_owned();
        }
        Action::None
    }

    pub(super) fn detach_prompt_browse(&mut self) {
        self.prompt_index = None;
        self.prompt_draft.clear();
    }

    pub(super) fn interrupt(&mut self) -> Action {
        if self.pending.is_some() {
            self.cancel();
            Action::Cancel
        } else {
            Action::Quit
        }
    }

    pub(super) fn cancel(&mut self) {
        if self.pending.take().is_none() {
            return;
        }
        self.finish_reasoning();
        self.finish_tool(Some("cancelled"));
        self.finish_calling(Some("cancelled"));
        self.streaming_answer = false;
        self.compact_snapshot = None;
        self.follow = true;
        self.bump();
    }

    pub(super) fn undo(&mut self) -> Action {
        let Some(previous) = self.undo.pop() else {
            self.notice = "Nothing to undo.".to_owned();
            self.bump();
            return Action::None;
        };
        self.redo.push(self.snapshot());
        self.restore(previous);
        self.notice = "Undid the last change.".to_owned();
        Action::PersistHistory(self.history.clone())
    }

    pub(super) fn redo(&mut self) -> Action {
        let Some(next) = self.redo.pop() else {
            self.notice = "Nothing to redo.".to_owned();
            self.bump();
            return Action::None;
        };
        self.push_undo(self.snapshot());
        self.restore(next);
        self.notice = "Restored the change.".to_owned();
        Action::PersistHistory(self.history.clone())
    }

    pub(super) fn push_undo(&mut self, state: UndoState) {
        self.undo.push(state);
        if self.undo.len() > 50 {
            self.undo.remove(0);
        }
    }

    fn snapshot(&self) -> UndoState {
        UndoState {
            history: self.history.clone(),
            transcript: self.transcript.clone(),
            input: self.input.text().to_owned(),
        }
    }

    fn restore(&mut self, state: UndoState) {
        self.history = state.history;
        self.transcript = state.transcript;
        self.input.set(state.input);
        self.reset_after_history_change();
    }

    fn reset_after_history_change(&mut self) {
        self.detach_prompt_browse();
        self.reasoning_active = None;
        self.active_tool = None;
        self.calling_tool = None;
        self.streaming_answer = false;
        self.answer_streamed = false;
        self.follow = true;
        self.bump();
    }

    pub(super) fn submit(&mut self) -> Action {
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
                return Action::PersistHistory(Vec::new());
            }
            "/undo" => {
                self.input.clear();
                return self.undo();
            }
            "/redo" => {
                self.input.clear();
                return self.redo();
            }
            "/sessions" => {
                self.input.clear();
                return Action::ListSessions;
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
        self.push_undo(UndoState {
            history: self.history.clone(),
            transcript: self.transcript.clone(),
            input: submitted.clone(),
        });
        self.redo.clear();
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
            started: Instant::now(),
        });
        self.notice = WAITING_NOTICE.to_owned();
        self.follow = true;
        self.bump();
        Action::Submit(messages)
    }

    pub(super) fn remember_prompt(&mut self, prompt: &str) {
        if self.prompts.last().map_or(true, |last| last != prompt) {
            self.prompts.push(prompt.to_owned());
        }
        self.detach_prompt_browse();
    }

    pub(super) fn previous_prompt(&mut self) {
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

    pub(super) fn next_prompt(&mut self) {
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
}
