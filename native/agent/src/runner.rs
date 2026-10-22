use std::sync::Arc;

use tokio::sync::mpsc::UnboundedSender;
use tokio::sync::Mutex;

use crate::api::Api;
use crate::hub::{Hub, Target};
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
    api: Api,
    model: String,
    effort: String,
    hub: Arc<Mutex<Hub>>,
    messages: Vec<Message>,
    events: UnboundedSender<AgentEvent>,
) -> Result<Outcome, Failure> {
    let context = hub.lock().await.caller.clone();
    let targets = hub.lock().await.targets().await.unwrap_or_default();
    let system = system_prompt(&context, &targets);
    let tools = tool::definitions();
    let mut history = messages;

    loop {
        let _ = events.send(AgentEvent::StepStart);
        let mut wire = Vec::with_capacity(history.len() + 1);
        wire.push(Message::system(system.clone()));
        wire.extend(history.iter().cloned());
        let assistant = api
            .chat(&model, &effort, &wire, &tools, &events)
            .await
            .map_err(|error| Failure::new(error, &history))?;

        if assistant.has_tools() {
            let calls = assistant.tool_calls.clone().unwrap_or_default();
            history.push(Message::assistant_with_tools(
                assistant.content.clone(),
                calls.clone(),
            ));
            for call in calls {
                let info = tool::info(&call.function.name, &call.function.arguments);
                let _ = events.send(AgentEvent::ToolCall {
                    name: call.function.name.clone(),
                    detail: info.detail,
                    description: info.description,
                    command: info.command,
                });
                let result =
                    tool::execute(&call, Arc::clone(&hub), &context, Some(events.clone())).await;
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
        .map(|target| {
            let state = if target.running { "running" } else { "stopped" };
            format!("{} ({state})", target.name)
        })
        .collect::<Vec<_>>()
        .join(", ");
    let path = std::env::current_dir()
        .map(|path| path.display().to_string())
        .unwrap_or_else(|_| "unknown".to_owned());
    let distribution = distribution();
    format!(
        "You are the file-manager agent. You can inspect and change files in the current container or in another container through tools.\n\n\
         Current container: {context}\n\
         Current path: {path}\n\
         Distribution: {distribution}\n\
         Known targets: {names}\n\n\
         Tools:\n\
         - read: read a file (path, optional offset and length)\n\
         - write: create or overwrite a file (path, content)\n\
         - glob: find paths matching a glob under an absolute directory\n\
         - exec: run a shell command and return exit code, stdout and stderr\n\
         - list_containers: list all targets and whether they are running\n\n\
         Every tool takes an optional container_id (host, app, or container-<name>). \
         When it is omitted the tool uses the current container. Paths are absolute \
         inside the selected target. Use list_containers when you need another target. \
         Prefer reading a file before rewriting it, and keep answers concise."
    )
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
    api: Api,
    model: String,
    effort: String,
    messages: Vec<Message>,
) -> Result<Outcome, String> {
    let transcript = transcript_text(&messages);
    let wire = vec![
        Message::system(
            "Compress the conversation below into a concise handoff summary. Keep decisions, \
             constraints, file paths, code changes, tool results and open questions. Reply with \
             only the summary.",
        ),
        Message::user(transcript),
    ];
    let (sink, _receiver) = tokio::sync::mpsc::unbounded_channel();
    let assistant = api
        .chat(&model, &effort, &wire, &serde_json::json!([]), &sink)
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
