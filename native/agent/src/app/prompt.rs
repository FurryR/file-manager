use std::time::Instant;

use crate::model::{Entry, Message};

use super::{Action, App, Pending, UndoState, HELP};

impl App {
    pub(super) fn clear_conversation(&mut self) {
        self.history.clear();
        self.transcript.clear();
        self.undo.clear();
        self.redo.clear();
        self.compact_snapshot = None;
        self.queued.clear();
        self.approval = None;
        self.esc_armed = false;
        self.reset_command_menu();
        self.input.clear();
        self.prompt_index = None;
        self.prompt_draft.clear();
        self.reasoning_active = None;
        self.active_tool = None;
        self.calling_tool = None;
        self.streaming_index = None;
        self.answer_streamed = false;
        self.follow = true;
        self.scroll = 0;
        self.bump();
    }

    pub(super) fn show_help(&mut self) {
        self.transcript.push(Entry::notice(HELP));
        self.input.clear();
        self.follow = true;
        self.bump();
    }

    pub(super) fn toggle_yolo(&mut self) -> Action {
        self.detach_prompt_browse();
        self.input.clear();
        self.yolo = !self.yolo;
        self.transcript.push(Entry::notice(if self.yolo {
            "YOLO mode enabled: every tool call in this terminal is auto-approved."
        } else {
            "YOLO mode disabled: approvals are required again."
        }));
        // Auto-approval is local to this terminal: the hub keeps asking and the
        // UI answers without drawing a dialog. Release a dialog that is already
        // waiting instead of leaving it to time out.
        let mut approval = Action::None;
        if self.yolo {
            if let Some(state) = self.approval.take() {
                approval = Action::Approval {
                    id: state.id,
                    allow: true,
                };
            }
        }
        self.follow = true;
        self.bump();
        approval
    }

    pub(super) fn insert_text(&mut self, value: &str) -> Action {
        self.detach_prompt_browse();
        self.reset_command_menu();
        self.input.insert(value);
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

    pub(crate) fn cancel(&mut self) {
        if self.pending.take().is_none() {
            return;
        }
        self.finish_reasoning();
        self.finish_tool(Some("cancelled"));
        self.finish_calling(Some("cancelled"));
        self.streaming_index = None;
        self.compact_snapshot = None;
        self.approval = None;
        self.queued.clear();
        self.esc_armed = false;
        self.follow = true;
        self.bump();
    }

    /// Surface a control-plane failure (cancel, steering) in the transcript
    /// instead of leaving the user staring at a turn that will never answer.
    pub fn push_error(&mut self, message: &str) {
        self.transcript.push(Entry::error(message.to_owned()));
        self.follow = true;
        self.bump();
    }

    pub(super) fn undo(&mut self) -> Action {
        // /undo is also a stop button: interrupt the run before touching the
        // conversation, otherwise its next event would land on restored state.
        let was_pending = self.pending.is_some();
        let mut queued = std::mem::take(&mut self.queued);
        if was_pending {
            self.cancel();
        }
        // A prompt typed mid-run has not reached the agent yet, so it is the
        // first thing undo removes. The run is interrupted either way, so the
        // remaining queued prompts are not delivered.
        if !queued.is_empty() {
            queued.pop();
            self.bump();
            return if was_pending {
                Action::Cancel
            } else {
                Action::None
            };
        }
        let Some(previous) = self.undo.pop() else {
            return if was_pending {
                Action::Cancel
            } else {
                Action::None
            };
        };
        let reverted = self.history.len().saturating_sub(previous.history.len());
        self.redo.push(self.snapshot());
        self.restore(previous);
        self.transcript.push(Entry::reverted(reverted));
        self.bump();
        if was_pending {
            Action::CancelAndPersist(self.history.clone())
        } else {
            Action::PersistHistory(self.history.clone())
        }
    }

    pub(super) fn redo(&mut self) -> Action {
        let Some(next) = self.redo.pop() else {
            return Action::None;
        };
        let was_pending = self.pending.is_some();
        if was_pending {
            self.cancel();
        }
        self.push_undo(self.snapshot());
        self.restore(next);
        let history = self.history.clone();
        if was_pending {
            Action::CancelAndPersist(history)
        } else {
            Action::PersistHistory(history)
        }
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
        self.streaming_index = None;
        self.answer_streamed = false;
        self.follow = true;
        self.bump();
    }

    pub(super) fn submit(&mut self) -> Action {
        let text = self.input.text().trim().to_owned();
        if self.pending.is_some() {
            match text.as_str() {
                "" => return Action::None,
                "/exit" | "/quit" => return Action::Quit,
                "/help" => {
                    self.show_help();
                    return Action::None;
                }
                "/yolo" => return self.toggle_yolo(),
                "/undo" => {
                    self.input.clear();
                    return self.undo();
                }
                "/clear" | "/redo" | "/sessions" | "/models" | "/effort" | "/new"
                | "/compact" => return Action::None,
                _ => {}
            }
            let submitted = self.input.take();
            self.remember_prompt(&submitted);
            self.queued.push(submitted.clone());
            self.follow = true;
            self.bump();
            // Steering, not queueing: the turn is still running, and the model
            // should see this at its next gap (right after the tool results it
            // is waiting on) rather than minutes later as a second run. The
            // entry stays below the transcript with a grey marker until the
            // agent reports that it entered the conversation.
            return Action::Steer(submitted);
        }
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
            "/new" => {
                self.input.clear();
                return Action::NewSession;
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
            "/effort" => {
                self.input.clear();
                self.open_efforts();
                return Action::None;
            }
            "/compact" => {
                self.input.clear();
                return self.begin_compact().unwrap_or(Action::None);
            }
            "/yolo" => return self.toggle_yolo(),
            _ => {}
        }
        if self.model.is_empty() {
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
        self.streaming_index = None;
        self.answer_streamed = false;
        let mut messages = self.history.clone();
        messages.push(Message::user(submitted.clone()));
        self.pending = Some(Pending {
            started: Instant::now(),
        });
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
        self.reset_command_menu();
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
            self.reset_command_menu();
            self.prompt_index = Some(index + 1);
        } else {
            let draft = std::mem::take(&mut self.prompt_draft);
            self.input.set(draft);
            self.reset_command_menu();
            self.prompt_index = None;
        }
        self.bump();
    }
}
