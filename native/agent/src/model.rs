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

    pub fn assistant(content: impl Into<String>) -> Self {
        Self::new("assistant", content)
    }

    pub fn assistant_with_tools(content: Option<String>, tool_calls: Vec<ToolCall>) -> Self {
        Self {
            role: "assistant".to_owned(),
            content,
            reasoning_content: None,
            tool_calls: Some(tool_calls),
            tool_call_id: None,
        }
    }

    /// Attach the provider's reasoning to an assistant message.
    pub fn with_reasoning(mut self, reasoning: Option<String>) -> Self {
        self.reasoning_content = reasoning.filter(|text| !text.trim().is_empty());
        self
    }

    pub fn tool(call_id: impl Into<String>, content: impl Into<String>) -> Self {
        Self {
            role: "tool".to_owned(),
            content: Some(content.into()),
            reasoning_content: None,
            tool_calls: None,
            tool_call_id: Some(call_id.into()),
        }
    }

    fn new(role: &str, content: impl Into<String>) -> Self {
        Self {
            role: role.to_owned(),
            content: Some(content.into()),
            reasoning_content: None,
            tool_calls: None,
            tool_call_id: None,
        }
    }

    pub fn content_text(&self) -> &str {
        self.content.as_deref().unwrap_or_default()
    }

    pub fn has_tools(&self) -> bool {
        self.tool_calls
            .as_ref()
            .is_some_and(|calls| !calls.is_empty())
    }
}

/// Token usage reported by the provider for one request.
#[derive(Clone, Copy, Default)]
pub struct Usage {
    pub prompt_tokens: u64,
    pub completion_tokens: u64,
}

/// Progress emitted by the tool loop for the terminal UI.
#[derive(Clone)]
pub enum AgentEvent {
    StepStart,
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
}

#[derive(Clone, Copy, PartialEq, Eq)]
pub enum Role {
    User,
    Assistant,
    Reasoning,
    Tool,
    Notice,
    Error,
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
}
