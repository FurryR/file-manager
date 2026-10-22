use std::io;
use std::os::fd::{AsRawFd, OwnedFd};
use std::sync::Arc;

use serde_json::{json, Value};
use tokio::io::unix::AsyncFd;
use tokio::io::Interest;
use tokio::sync::mpsc::UnboundedSender;
use tokio::sync::Mutex;

use crate::hub::Hub;
use crate::model::{AgentEvent, ToolCall};

const MAX_TOOL_OUTPUT: usize = 128 * 1024;
const MAX_EXEC_CAPTURE: usize = 1024 * 1024;
const MAX_LIVE_OUTPUT: usize = 256 * 1024;

pub struct ToolResult {
    pub ok: bool,
    pub content: String,
}

pub fn definitions() -> Value {
    let container = json!({
        "type": "string",
        "description": "Target id: host, app, or container-<name>. Defaults to the current container when omitted."
    });
    json!([
        {
            "type": "function",
            "function": {
                "name": "read",
                "description": "Read a file from a container. Returns the file bytes as text, optionally starting at an offset.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "path": { "type": "string", "description": "Absolute file path inside the target." },
                        "container_id": container,
                        "offset": { "type": "integer", "description": "Byte offset to start reading from. Defaults to 0." },
                        "length": { "type": "integer", "description": "Maximum bytes to read. Defaults to 64 KiB." }
                    },
                    "required": ["path"]
                }
            }
        },
        {
            "type": "function",
            "function": {
                "name": "write",
                "description": "Create or overwrite a file in a container. Parent directories are created automatically.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "path": { "type": "string", "description": "Absolute file path inside the target." },
                        "content": { "type": "string", "description": "Full file content." },
                        "container_id": container
                    },
                    "required": ["path", "content"]
                }
            }
        },
        {
            "type": "function",
            "function": {
                "name": "glob",
                "description": "Find files and directories matching a glob pattern inside a container.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "pattern": { "type": "string", "description": "Glob such as **/*.kt, matched against paths relative to path." },
                        "path": { "type": "string", "description": "Absolute directory to search. Defaults to /." },
                        "max_results": { "type": "integer", "description": "Maximum number of matches. Defaults to 200." },
                        "container_id": container
                    },
                    "required": ["pattern"]
                }
            }
        },
        {
            "type": "function",
            "function": {
                "name": "exec",
                "description": "Run a shell command in a container and return its exit code, stdout and stderr.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "command": { "type": "string", "description": "Shell command to run with the target shell." },
                        "cwd": { "type": "string", "description": "Absolute working directory. Defaults to the target daemon's cwd." },
                        "container_id": container
                    },
                    "required": ["command"]
                }
            }
        },
        {
            "type": "function",
            "function": {
                "name": "list_containers",
                "description": "List every known target (host, app and containers) with its running state.",
                "parameters": { "type": "object", "properties": {} }
            }
        }
    ])
}

pub async fn execute(
    call: &ToolCall,
    hub: Arc<Mutex<Hub>>,
    default_container: &str,
    events: Option<UnboundedSender<AgentEvent>>,
) -> ToolResult {
    bound(
        run(
            &call.function.name,
            &call.function.arguments,
            &hub,
            default_container,
            events,
        )
        .await,
    )
}

fn bound(mut result: ToolResult) -> ToolResult {
    if result.content.chars().count() > MAX_TOOL_OUTPUT {
        result.content = result.content.chars().take(MAX_TOOL_OUTPUT).collect();
        result.content.push_str("\n[tool output truncated]");
    }
    result
}

pub struct ToolInfo {
    pub detail: String,
    pub description: Option<String>,
    pub command: Option<String>,
}

pub fn info(name: &str, arguments: &str) -> ToolInfo {
    let args: Value = serde_json::from_str(arguments).unwrap_or(Value::Null);
    let container = args
        .get("container_id")
        .and_then(Value::as_str)
        .filter(|value| !value.is_empty())
        .map(|value| format!(" @{value}"))
        .unwrap_or_default();
    let (value, description, command) = match name {
        "read" | "write" => (
            args.get("path").and_then(Value::as_str).unwrap_or("?"),
            None,
            None,
        ),
        "glob" => (
            args.get("pattern").and_then(Value::as_str).unwrap_or("?"),
            None,
            None,
        ),
        "exec" => {
            let command = args.get("command").and_then(Value::as_str);
            (
                command.unwrap_or("?"),
                args.get("description")
                    .and_then(Value::as_str)
                    .map(str::trim)
                    .filter(|value| !value.is_empty())
                    .map(str::to_owned),
                command.map(str::to_owned),
            )
        }
        "list_containers" => {
            return ToolInfo {
                detail: String::new(),
                description: None,
                command: None,
            }
        }
        _ => (arguments, None, None),
    };
    let value = value.replace('\n', " ");
    ToolInfo {
        detail: format!("{}{container}", truncate(&value, 120)),
        description,
        command,
    }
}

async fn run(
    name: &str,
    arguments: &str,
    hub: &Arc<Mutex<Hub>>,
    default_container: &str,
    events: Option<UnboundedSender<AgentEvent>>,
) -> ToolResult {
    let args: Value = if arguments.trim().is_empty() {
        json!({})
    } else {
        match serde_json::from_str(arguments) {
            Ok(args) => args,
            Err(error) => {
                return ToolResult {
                    ok: false,
                    content: format!("invalid tool arguments: {error}"),
                }
            }
        }
    };
    let container = args
        .get("container_id")
        .and_then(Value::as_str)
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .unwrap_or(default_container)
        .to_owned();
    let result = match name {
        "read" => read(&args, hub, &container).await,
        "write" => write(&args, hub, &container).await,
        "glob" => glob(&args, hub, &container).await,
        "exec" => exec(&args, hub, &container, events.as_ref()).await,
        "list_containers" => list_containers(hub).await,
        _ => Err(format!("unknown tool '{name}'")),
    };
    match result {
        Ok(content) => ToolResult { ok: true, content },
        Err(content) => ToolResult { ok: false, content },
    }
}

async fn read(args: &Value, hub: &Arc<Mutex<Hub>>, container: &str) -> Result<String, String> {
    let path = string_arg(args, "path")?;
    let offset = args.get("offset").and_then(Value::as_u64).unwrap_or(0);
    let length = args
        .get("length")
        .and_then(Value::as_u64)
        .unwrap_or(0)
        .min(u32::MAX as u64) as u32;
    let response = hub
        .lock()
        .await
        .read(container, &path, offset, length)
        .await?;
    if response.data.is_empty() {
        return Ok(format!(
            "(empty at offset {offset}; file size {} bytes)",
            response.size
        ));
    }
    let mut text = String::from_utf8_lossy(&response.data).into_owned();
    if !response.eof {
        text.push_str(&format!(
            "\n\n[truncated at byte {} of {}]",
            offset + response.data.len() as u64,
            response.size
        ));
    }
    Ok(text)
}

async fn write(args: &Value, hub: &Arc<Mutex<Hub>>, container: &str) -> Result<String, String> {
    let path = string_arg(args, "path")?;
    let content = args
        .get("content")
        .and_then(Value::as_str)
        .ok_or_else(|| "missing 'content'".to_owned())?;
    let written = hub
        .lock()
        .await
        .write(container, &path, content.as_bytes().to_vec())
        .await?;
    Ok(format!("Wrote {written} bytes to {path}"))
}

async fn glob(args: &Value, hub: &Arc<Mutex<Hub>>, container: &str) -> Result<String, String> {
    let pattern = string_arg(args, "pattern")?;
    let path = args
        .get("path")
        .and_then(Value::as_str)
        .filter(|value| !value.is_empty())
        .unwrap_or("/");
    let max_results = args
        .get("max_results")
        .and_then(Value::as_u64)
        .unwrap_or(200)
        .min(u32::MAX as u64) as u32;
    let response = hub
        .lock()
        .await
        .glob(container, &pattern, path, max_results)
        .await?;
    if response.paths.is_empty() {
        return Ok(format!("(no matches for {pattern} under {path})"));
    }
    let mut output = response.paths.join("\n");
    if response.truncated {
        output.push_str("\n[results truncated]");
    }
    Ok(output)
}

async fn exec(
    args: &Value,
    hub: &Arc<Mutex<Hub>>,
    container: &str,
    events: Option<&UnboundedSender<AgentEvent>>,
) -> Result<String, String> {
    let command = string_arg(args, "command")?;
    let cwd = args.get("cwd").and_then(Value::as_str).unwrap_or_default();
    let stream = hub.lock().await.exec(container, &command, cwd).await?;
    let exec_id = stream.exec_id;
    let (stdout, stderr) = tokio::try_join!(
        read_all(stream.stdout, events),
        read_all(stream.stderr, events)
    )
    .map_err(|error| error.to_string())?;
    let exit_code = hub.lock().await.wait_exec(exec_id).await?;
    let mut output = format!("exit_code: {exit_code}\n");
    let stdout = String::from_utf8_lossy(&stdout);
    let stderr = String::from_utf8_lossy(&stderr);
    if !stdout.is_empty() {
        output.push_str("stdout:\n");
        push_bounded(&mut output, &stdout);
    }
    if !stderr.is_empty() {
        output.push_str("stderr:\n");
        push_bounded(&mut output, &stderr);
    }
    if stdout.is_empty() && stderr.is_empty() {
        output.push_str("(no output)");
    }
    Ok(output)
}

/// Drain one exec pipe to EOF. Output beyond the cap is discarded but still
/// read so the child never blocks on a full pipe.
async fn read_all(
    fd: OwnedFd,
    events: Option<&UnboundedSender<AgentEvent>>,
) -> io::Result<Vec<u8>> {
    let flags = unsafe { libc::fcntl(fd.as_raw_fd(), libc::F_GETFL) };
    if flags < 0
        || unsafe { libc::fcntl(fd.as_raw_fd(), libc::F_SETFL, flags | libc::O_NONBLOCK) } < 0
    {
        return Err(io::Error::last_os_error());
    }
    let fd = AsyncFd::new(fd)?;
    let mut collected = Vec::new();
    let mut streamed = 0usize;
    let mut buf = [0u8; 8192];
    loop {
        let n = fd
            .async_io(Interest::READABLE, |inner| unsafe {
                read_raw(inner.as_raw_fd(), &mut buf)
            })
            .await?;
        if n == 0 {
            break;
        }
        if let Some(events) = events {
            if streamed < MAX_LIVE_OUTPUT {
                let take = (MAX_LIVE_OUTPUT - streamed).min(n);
                let _ = events.send(AgentEvent::ToolOutput(buf[..take].to_vec()));
                streamed += take;
            }
        }
        if collected.len() < MAX_EXEC_CAPTURE {
            let take = (MAX_EXEC_CAPTURE - collected.len()).min(n);
            collected.extend_from_slice(&buf[..take]);
        }
    }
    Ok(collected)
}

unsafe fn read_raw(fd: std::os::fd::RawFd, buf: &mut [u8]) -> io::Result<usize> {
    loop {
        let n = unsafe { libc::read(fd, buf.as_mut_ptr().cast(), buf.len()) };
        if n < 0 {
            let error = io::Error::last_os_error();
            if error.kind() == io::ErrorKind::Interrupted {
                continue;
            }
            return Err(error);
        }
        return Ok(n as usize);
    }
}

async fn list_containers(hub: &Arc<Mutex<Hub>>) -> Result<String, String> {
    let targets = hub.lock().await.targets().await?;
    if targets.is_empty() {
        return Ok("(no targets)".to_owned());
    }
    Ok(targets
        .into_iter()
        .map(|target| {
            let state = if target.running { "running" } else { "stopped" };
            let elevated = if target.elevated { ", elevated" } else { "" };
            format!("{} ({state}{elevated})", target.name)
        })
        .collect::<Vec<_>>()
        .join("\n"))
}

fn string_arg(args: &Value, name: &str) -> Result<String, String> {
    args.get(name)
        .and_then(Value::as_str)
        .filter(|value| !value.is_empty())
        .map(str::to_owned)
        .ok_or_else(|| format!("missing '{name}'"))
}

fn push_bounded(output: &mut String, text: &str) {
    if text.chars().count() > MAX_TOOL_OUTPUT {
        output.extend(text.chars().take(MAX_TOOL_OUTPUT));
        output.push_str("\n[output truncated]");
    } else {
        output.push_str(text);
    }
    if !output.ends_with('\n') {
        output.push('\n');
    }
}

fn truncate(value: &str, limit: usize) -> String {
    if value.chars().count() <= limit {
        return value.to_owned();
    }
    let mut result: String = value.chars().take(limit).collect();
    result.push('…');
    result
}
