use std::time::Instant;

use crate::model::{AgentEvent, Entry, Message, WriteBlock};

use super::{tool_line, App, GENERATING_CALL, MAX_LIVE_TOOL_BYTES};

impl App {
    pub fn complete_success(&mut self, messages: Vec<Message>, answer: String) {
        if self.pending.take().is_none() {
            return;
        }
        self.finish_reasoning();
        self.finish_tool(None);
        self.finish_calling(None);
        self.streaming_index = None;
        self.history = messages;
        if !self.answer_streamed {
            self.transcript.push(Entry::assistant(answer));
        }
        self.bump();
    }

    pub fn complete_compact(&mut self, summary: String) {
        if self.pending.take().is_none() {
            return;
        }
        self.finish_reasoning();
        self.finish_tool(None);
        self.finish_calling(None);
        self.streaming_index = None;
        self.history = vec![Message::user(format!(
            "Summary of the earlier conversation (compacted):\n{summary}"
        ))];
        if let Some(snapshot) = self.compact_snapshot.take() {
            self.push_undo(snapshot);
        }
        self.redo.clear();
        self.transcript.clear();
        self.transcript
            .push(Entry::notice("Conversation compacted."));
        self.follow = true;
        self.bump();
    }

    pub fn agent_event(&mut self, event: AgentEvent) {
        match event {
            AgentEvent::StepStart => {
                self.finish_reasoning();
                self.finish_tool(None);
                self.finish_calling(None);
                self.streaming_index = None;
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
                let index = match self.streaming_index {
                    Some(index) if index < self.transcript.len() => index,
                    _ => {
                        let index = self.transcript.len();
                        self.transcript.push(Entry::assistant(""));
                        self.streaming_index = Some(index);
                        index
                    }
                };
                if let Some(entry) = self.transcript.get_mut(index) {
                    entry.content.push_str(&text);
                }
            }
            AgentEvent::ToolCalling => {
                self.finish_reasoning();
                self.streaming_index = None;
                if self.calling_tool.is_none() {
                    let index = self.transcript.len();
                    self.transcript.push(Entry::tool(GENERATING_CALL));
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
                self.streaming_index = None;
                let index = match self.calling_tool.take() {
                    Some(index) => index,
                    None => {
                        let index = self.transcript.len();
                        self.transcript.push(Entry::tool(GENERATING_CALL));
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
            }
            AgentEvent::ToolWrite {
                path,
                content,
                diff,
            } => {
                if let Some(index) = self.active_tool {
                    if let Some(entry) = self.transcript.get_mut(index) {
                        entry.content.clear();
                        entry.write = Some(WriteBlock {
                            path,
                            content,
                            diff,
                            failed: false,
                        });
                        self.bump();
                    }
                }
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
        self.compact_snapshot = None;
        self.finish_reasoning();
        self.finish_tool(Some("failed"));
        self.finish_calling(Some("failed"));
        self.streaming_index = None;
        self.transcript.push(Entry::error(error));
        if let Some(messages) = messages {
            self.history = messages;
        }
        self.follow = true;
        self.bump();
    }

    /// Adopt the cancelled run's settled history. The transcript keeps what the
    /// user already saw; only the message history is synchronized.
    pub fn complete_cancelled(&mut self, messages: Vec<Message>) {
        self.history = messages;
        self.bump();
    }

    pub(super) fn finish_tool(&mut self, status: Option<&str>) {
        let Some(index) = self.active_tool.take() else {
            return;
        };
        if let Some(entry) = self.transcript.get_mut(index) {
            entry.started = None;
            if let Some(write) = entry.write.as_mut() {
                write.failed = matches!(status, Some("failed"));
            } else if let Some(tool) = entry.tool.as_mut() {
                tool.failed = matches!(status, Some("failed"));
            } else {
                entry.content = match status {
                    Some(status) => format!("{} ({status})", entry.content),
                    None => entry.content.clone(),
                };
            }
        }
    }

    pub(super) fn finish_calling(&mut self, status: Option<&str>) {
        let Some(index) = self.calling_tool.take() else {
            return;
        };
        if let Some(entry) = self.transcript.get_mut(index) {
            entry.started = None;
            if entry.content == GENERATING_CALL {
                entry.content = match status {
                    Some(status) => format!("← Tools ({status})"),
                    None => "← Tools".to_owned(),
                };
            }
        }
    }

    pub(super) fn finish_reasoning(&mut self) {
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

    pub(super) fn toggle_at(&mut self, row: u16) {
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
}
