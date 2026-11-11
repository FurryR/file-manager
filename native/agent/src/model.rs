use std::time::Duration;

use serde::{Deserialize, Serialize};

#[derive(Clone, Serialize, Deserialize)]
pub struct Message {
    pub role: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub content: Option<String>,
    /// Provider-visible chain of thought. Kept in session history so resuming
    /// a conversation preserves the model's reasoning context instead of
    /// dropping whole intermediate turns.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub reasoning_content: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub tool_calls: Option<Vec<ToolCall>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub tool_call_id: Option<String>,
    /// Starts a compacted context. Everything before this message stays in the
    /// transcript for display but is no longer sent to the provider.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub compaction_summary: Option<bool>,
}

#[derive(Clone, Serialize, Deserialize)]
pub struct ToolCall {
    pub id: String,
    #[serde(rename = "type", default = "function_type")]
    pub kind: String,
    pub function: FunctionCall,
}

#[derive(Clone, Serialize, Deserialize)]
pub struct FunctionCall {
    pub name: String,
    #[serde(default)]
    pub arguments: String,
}

fn function_type() -> String {
    "function".to_owned()
}

impl Message {
    pub fn user(content: impl Into<String>) -> Self {
        Self::new("user", content)
    }

    fn new(role: &str, content: impl Into<String>) -> Self {
        Self {
            role: role.to_owned(),
            content: Some(content.into()),
            reasoning_content: None,
            tool_calls: None,
            tool_call_id: None,
            compaction_summary: None,
        }
    }

    pub fn content_text(&self) -> &str {
        self.content.as_deref().unwrap_or_default()
    }


}

/// Token usage reported by the provider for one request.
#[derive(Clone, Copy, Default)]
pub struct Usage {
    pub prompt_tokens: u64,
    pub completion_tokens: u64,
    /// Context size reported after the request. Zero means "use prompt_tokens".
    pub context_tokens: u64,
}

impl Usage {
    pub fn context(&self) -> u64 {
        if self.context_tokens > 0 {
            self.context_tokens
        } else {
            self.prompt_tokens
        }
    }
}

/// Progress emitted by the tool loop for the terminal UI.
#[derive(Clone)]
pub enum AgentEvent {
    ReasoningDelta(String),
    AnswerDelta(String),
    ToolCalling,
    ToolCall {
        name: String,
        detail: String,
        description: Option<String>,
        command: Option<String>,
    },
    ToolWrite {
        path: String,
        content: String,
        diff: Option<String>,
    },
    ToolOutput(Vec<u8>),
    ToolResult {
        ok: bool,
    },
    Usage(Usage),
    ApprovalRequest {
        id: u64,
        name: String,
        detail: String,
        reason: String,
    },
    /// A previously queued mid-run prompt has entered the conversation.
    AcceptedPrompt(String),
    /// The agent summarized its context. Older messages stay visible.
    Compaction(String),
}

#[derive(Clone, Copy, PartialEq, Eq)]
pub enum Role {
    User,
    Assistant,
    Reasoning,
    Tool,
    Notice,
    Error,
    /// A row after an undo, showing how many messages went away.
    Reverted,
    /// The horizontal `Compaction` divider plus the model's summary.
    Compaction,
}

/// Live view of an exec tool call rendered as a shell-style block.
#[derive(Clone)]
pub struct ToolBlock {
    pub description: Option<String>,
    pub command: String,
    pub output: Vec<u8>,
    pub failed: bool,
}

#[derive(Clone)]
pub struct WriteBlock {
    pub path: String,
    pub content: String,
    pub diff: Option<String>,
    pub failed: bool,
}

#[derive(Clone)]
pub struct Entry {
    pub role: Role,
    pub content: String,
    pub expanded: bool,
    pub duration: Option<Duration>,
    pub started: Option<std::time::Instant>,
    pub tool: Option<ToolBlock>,
    pub write: Option<WriteBlock>,
}

impl Entry {
    fn new(role: Role, content: impl Into<String>) -> Self {
        Self {
            role,
            content: content.into(),
            expanded: false,
            duration: None,
            started: None,
            tool: None,
            write: None,
        }
    }

    pub fn user(content: impl Into<String>) -> Self {
        Self::new(Role::User, content)
    }

    pub fn assistant(content: impl Into<String>) -> Self {
        Self::new(Role::Assistant, content)
    }

    pub fn reasoning() -> Self {
        Self {
            started: Some(std::time::Instant::now()),
            ..Self::new(Role::Reasoning, "")
        }
    }

    /// A reasoning block restored from session history. It has no live timer,
    /// so the header renders as a completed thought.
    pub fn saved_reasoning(content: impl Into<String>) -> Self {
        Self {
            duration: None,
            started: None,
            ..Self::new(Role::Reasoning, content)
        }
    }

    pub fn tool(content: impl Into<String>) -> Self {
        Self {
            started: Some(std::time::Instant::now()),
            ..Self::new(Role::Tool, content)
        }
    }

    pub fn exec(description: Option<String>, command: impl Into<String>) -> Self {
        Self {
            started: Some(std::time::Instant::now()),
            tool: Some(ToolBlock {
                description,
                command: command.into(),
                output: Vec::new(),
                failed: false,
            }),
            ..Self::new(Role::Tool, "")
        }
    }

    pub fn notice(content: impl Into<String>) -> Self {
        Self::new(Role::Notice, content)
    }

    pub fn error(content: impl Into<String>) -> Self {
        Self::new(Role::Error, content)
    }

    pub fn reverted(count: usize) -> Self {
        let label = if count == 1 { "message" } else { "messages" };
        Self::new(
            Role::Reverted,
            format!("{count} {label} reverted\nuse /redo to redo"),
        )
    }

    pub fn compaction(summary: impl Into<String>) -> Self {
        Self::new(Role::Compaction, summary)
    }
}
