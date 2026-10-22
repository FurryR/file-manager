use std::sync::Arc;

use tokio::sync::mpsc::UnboundedSender;
use tokio::sync::Mutex;

use crate::hub::{display_target, Hub, Target};
use crate::mcp::McpManager;
use crate::model::{AgentEvent, Message};
use crate::tool;

pub struct Outcome {
    pub messages: Vec<Message>,
    pub answer: String,
}

pub struct Failure {
    pub error: String,
    pub messages: Vec<Message>,
}

impl Failure {
    pub(crate) fn new(error: String, messages: &[Message]) -> Self {
        Self {
            error,
            messages: messages.to_vec(),
        }
    }
}

pub async fn run(
    model: String,
    effort: String,
    hub: Arc<Mutex<Hub>>,
    messages: Vec<Message>,
    events: UnboundedSender<AgentEvent>,
    active_exec: tool::ActiveExec,
    session_id: String,
) -> Result<Outcome, Failure> {
    let context = hub.lock().await.caller.clone();
    let targets = hub.lock().await.targets().await.unwrap_or_default();
    let system = system_prompt(&context, &targets);
    let cwd = std::env::current_dir()
        .map(|path| path.display().to_string())
        .unwrap_or_else(|_| "/".to_owned());
    let session_hub =
        Arc::new(Mutex::new(Hub::connect().await.map_err(|error| {
            Failure::new(format!("hub: {error}"), &messages)
        })?));
    let capabilities = session_hub
        .lock()
        .await
        .capabilities(&cwd)
        .await
        .unwrap_or_default();
    let mcp = Arc::new(McpManager::connect(&capabilities, Arc::clone(&session_hub), &cwd).await);
    let tools = tool::definitions_with_mcp(Some(&mcp));
    let mut history = messages;
    let mut pending_start = history.len().saturating_sub(1);
    let mut append = !history.is_empty();
    let mut base_count = pending_start as u32;

    loop {
        let _ = events.send(AgentEvent::StepStart);
        let (delta, use_append, count) = if append {
            (&history[pending_start..], true, base_count)
        } else {
            (&history[..], false, 0)
        };
        let attempt = session_hub
            .lock()
            .await
            .chat(
                &session_id,
                &model,
                &effort,
                &system,
                delta,
                &tools,
                &events,
                use_append,
                count,
            )
            .await;
        let assistant = match attempt {
            Ok(assistant) => assistant,
            Err(error) if error.contains("history mismatch") => session_hub
                .lock()
                .await
                .chat(
                    &session_id,
                    &model,
                    &effort,
                    &system,
                    &history,
                    &tools,
                    &events,
                    false,
                    0,
                )
                .await
                .map_err(|error| Failure::new(error, &history))?,
            Err(error) => return Err(Failure::new(error, &history)),
        };
        append = true;

        if assistant.has_tools() {
            let calls = assistant.tool_calls.clone().unwrap_or_default();
            history.push(Message::assistant_with_tools(
                assistant.content.clone(),
                calls.clone(),
            ));
            pending_start = history.len();
            base_count = pending_start as u32;
            for call in calls {
                let info =
                    tool::info_with_mcp(Some(&mcp), &call.function.name, &call.function.arguments);
                let _ = events.send(AgentEvent::ToolCall {
                    name: call.function.name.clone(),
                    detail: info.detail,
                    description: info.description,
                    command: info.command,
                });
                let result = tool::execute(
                    &call,
                    Arc::clone(&hub),
                    Some(Arc::clone(&mcp)),
                    &context,
                    Some(events.clone()),
                    &active_exec,
                )
                .await;
                let _ = events.send(AgentEvent::ToolResult { ok: result.ok });
                history.push(Message::tool(call.id.clone(), result.content));
            }
            continue;
        }

        let answer = assistant.content_text().trim().to_owned();
        if answer.is_empty() {
            return Err(Failure::new(
                "the model returned an empty reply".to_owned(),
                &history,
            ));
        }
        history.push(Message::assistant(answer.clone()));
        return Ok(Outcome {
            messages: history,
            answer,
        });
    }
}

fn system_prompt(context: &str, targets: &[Target]) -> String {
    let names = targets
        .iter()
        .map(format_target)
        .collect::<Vec<_>>()
        .join(", ");
    let path = std::env::current_dir()
        .map(|path| path.display().to_string())
        .unwrap_or_else(|_| "unknown".to_owned());
    let distribution = distribution();
    format!(
        "You are the file-manager agent.\n\n\
         Current target: {}\n\
         Current path: {path}\n\
         Distribution: {distribution}\n\
         Known targets: {names}\n\n\
         File paths are absolute inside the current target, or canonical VFS URIs \
         (file:///..., container://name/..., ftp://..., sftp://..., saf://...) for \
         another location. copy takes a from/to pair. Use list_targets to discover \
         location roots. Read a file before rewriting it. Keep answers concise.",
        display_target(context),
    )
}

fn format_target(target: &Target) -> String {
    let root = if target.root.is_empty() {
        String::new()
    } else {
        format!(" {}", target.root)
    };
    if target.kind == "exec" {
        let state = if target.running { "running" } else { "stopped" };
        format!("{} ({state}, exec){root}", display_target(&target.label))
    } else if target.root.is_empty() {
        format!("{} ({}, files)", target.label, target.kind)
    } else {
        format!("{} ({}, files) {}", target.label, target.kind, target.root)
    }
}

fn distribution() -> String {
    std::fs::read_to_string("/etc/issue")
        .ok()
        .map(|text| {
            text.replace(['\r', '\n'], "")
                .replace("\\r", "")
                .replace("\\n", "")
                .trim()
                .to_owned()
        })
        .filter(|text| !text.is_empty())
        .unwrap_or_else(|| "Android".to_owned())
}

pub async fn compact(
    model: String,
    effort: String,
    messages: Vec<Message>,
) -> Result<Outcome, String> {
    let transcript = transcript_text(&messages);
    let mut hub = Hub::connect().await.map_err(|error| error.to_string())?;
    let (sink, _receiver) = tokio::sync::mpsc::unbounded_channel();
    let assistant = hub
        .chat(
            "",
            &model,
            &effort,
            "Compress the conversation below into a concise handoff summary. Keep decisions, \
             constraints, file paths, code changes, tool results and open questions. Reply with \
             only the summary.",
            &[Message::user(transcript)],
            &serde_json::json!([]),
            &sink,
            false,
            0,
        )
        .await?;
    let summary = assistant.content_text().trim().to_owned();
    if summary.is_empty() {
        return Err("the model returned an empty summary".to_owned());
    }
    let compacted = Message::user(format!(
        "Summary of the earlier conversation (compacted):\n{summary}"
    ));
    Ok(Outcome {
        messages: vec![compacted],
        answer: summary,
    })
}

fn transcript_text(messages: &[Message]) -> String {
    let mut output = String::new();
    for message in messages {
        match message.role.as_str() {
            "user" => output.push_str("User:\n"),
            "assistant" => output.push_str("Assistant:\n"),
            "tool" => output.push_str("Tool result:\n"),
            role => {
                output.push_str(role);
                output.push_str(":\n");
            }
        }
        if let Some(content) = message
            .content
            .as_deref()
            .filter(|text| !text.trim().is_empty())
        {
            output.push_str(content.trim_end());
            output.push('\n');
        }
        if let Some(calls) = &message.tool_calls {
            for call in calls {
                output.push_str(&format!(
                    "[tool call] {}({})\n",
                    call.function.name, call.function.arguments
                ));
            }
        }
        if let Some(id) = message.tool_call_id.as_deref() {
            output.push_str(&format!("[tool call id: {id}]\n"));
        }
        output.push('\n');
    }
    output.trim_end().to_owned()
}
