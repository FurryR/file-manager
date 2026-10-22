use std::future::Future;
use std::io;
use std::os::fd::{AsRawFd, OwnedFd};
use std::pin::Pin;
use std::sync::{Arc, Mutex as StdMutex};

use serde_json::{json, Value};
use tokio::io::unix::AsyncFd;
use tokio::io::Interest;
use tokio::sync::mpsc::UnboundedSender;
use tokio::sync::Mutex;

use crate::hub::{display_target, Hub};
use crate::mcp::McpManager;
use crate::model::{AgentEvent, ToolCall};

const MAX_TOOL_OUTPUT: usize = 128 * 1024;
const MAX_EXEC_CAPTURE: usize = 1024 * 1024;
const MAX_LIVE_OUTPUT: usize = 256 * 1024;
const MAX_WRITE_PREVIEW: u32 = 256 * 1024;
const MAX_DIFF_LINES: usize = 1_500;

/// Handle of the exec currently owned by the running request, if any. The UI
/// uses it to terminate the process when the request is cancelled.
pub type ActiveExec = Arc<StdMutex<Option<u64>>>;

pub struct ToolResult {
    pub ok: bool,
    pub content: String,
}

pub fn definitions() -> Value {
    Value::Array(TOOLS.iter().map(|tool| (tool.definition)()).collect())
}

pub fn definitions_with_mcp(mcp: Option<&McpManager>) -> Value {
    let mut definitions = match definitions() {
        Value::Array(definitions) => definitions,
        _ => Vec::new(),
    };
    if let Some(mcp) = mcp {
        definitions.extend(mcp.definitions());
    }
    Value::Array(definitions)
}

pub async fn execute(
    call: &ToolCall,
    hub: Arc<Mutex<Hub>>,
    mcp: Option<Arc<McpManager>>,
    default_container: &str,
    events: Option<UnboundedSender<AgentEvent>>,
    active_exec: &ActiveExec,
) -> ToolResult {
    bound(
        run(
            &call.function.name,
            &call.function.arguments,
            &hub,
            mcp,
            default_container,
            events,
            active_exec,
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

type ToolFuture<'a> = Pin<Box<dyn Future<Output = Result<String, String>> + Send + 'a>>;

struct ToolContext {
    hub: Arc<Mutex<Hub>>,
    default_container: String,
    events: Option<UnboundedSender<AgentEvent>>,
    active_exec: ActiveExec,
}

struct ToolSpec {
    name: &'static str,
    arrow: &'static str,
    definition: fn() -> Value,
    detail: fn(&Value) -> ToolInfo,
    run: for<'a> fn(&'a Value, ToolContext) -> ToolFuture<'a>,
}

const TOOLS: &[ToolSpec] = &[
    ToolSpec {
        name: "read",
        arrow: "→",
        definition: read_definition,
        detail: path_detail,
        run: read_run,
    },
    ToolSpec {
        name: "write",
        arrow: "←",
        definition: write_definition,
        detail: path_detail,
        run: write_run,
    },
    ToolSpec {
        name: "copy",
        arrow: "←",
        definition: copy_definition,
        detail: copy_detail,
        run: copy_run,
    },
    ToolSpec {
        name: "glob",
        arrow: "→",
        definition: glob_definition,
        detail: glob_detail,
        run: glob_run,
    },
    ToolSpec {
        name: "exec",
        arrow: "←",
        definition: exec_definition,
        detail: exec_detail,
        run: exec_run,
    },
    ToolSpec {
        name: "list_targets",
        arrow: "→",
        definition: list_targets_definition,
        detail: empty_detail,
        run: list_targets_run,
    },
];

fn find(name: &str) -> Option<&'static ToolSpec> {
    TOOLS.iter().find(|tool| tool.name == name)
}

pub fn arrow(name: &str) -> &'static str {
    find(name).map(|tool| tool.arrow).unwrap_or("←")
}

pub fn info_with_mcp(mcp: Option<&McpManager>, name: &str, arguments: &str) -> ToolInfo {
    let args: Value = serde_json::from_str(arguments).unwrap_or(Value::Null);
    if let Some(tool) = find(name) {
        return (tool.detail)(&args);
    }
    if let Some(mcp) = mcp {
        if let Some(info) = mcp.info(name, &args) {
            return info;
        }
    }
    simple_info(arguments)
}

fn simple_info(value: &str) -> ToolInfo {
    ToolInfo {
        detail: truncate(&value.replace('\n', " "), 120),
        description: None,
        command: None,
    }
}

fn path_detail(args: &Value) -> ToolInfo {
    simple_info(args.get("path").and_then(Value::as_str).unwrap_or("?"))
}

fn glob_detail(args: &Value) -> ToolInfo {
    simple_info(args.get("pattern").and_then(Value::as_str).unwrap_or("?"))
}

fn copy_detail(args: &Value) -> ToolInfo {
    let from = args.get("from").and_then(Value::as_str).unwrap_or("?");
    let to = args.get("to").and_then(Value::as_str).unwrap_or("?");
    simple_info(&format!("{from} -> {to}"))
}

fn exec_detail(args: &Value) -> ToolInfo {
    let command = args.get("command").and_then(Value::as_str);
    let mut info = simple_info(command.unwrap_or("?"));
    if let Some(target) = args
        .get("container_id")
        .and_then(Value::as_str)
        .filter(|value| !value.is_empty())
    {
        info.detail
            .push_str(&format!(" @{}", display_target(target)));
    }
    info.description = args
        .get("description")
        .and_then(Value::as_str)
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .map(str::to_owned);
    info.command = command.map(str::to_owned);
    info
}

fn empty_detail(_args: &Value) -> ToolInfo {
    ToolInfo {
        detail: String::new(),
        description: None,
        command: None,
    }
}

fn container_schema() -> Value {
    json!({
        "type": "string",
        "description": "Target name: host, app, or a container name. Defaults to the current container when omitted."
    })
}

fn read_definition() -> Value {
    json!({
        "type": "function",
        "function": {
            "name": "read",
            "description": "Read a file. The path resolves inside the current target, or may be a canonical VFS URI for another location.",
            "parameters": {
                "type": "object",
                "properties": {
                    "path": { "type": "string", "description": "Absolute path in the current target, or a VFS URI such as container://name/path or saf://id/path." },
                    "offset": { "type": "integer", "description": "Byte offset to start reading from. Defaults to 0." },
                    "length": { "type": "integer", "description": "Maximum bytes to read. Defaults to 64 KiB." }
                },
                "required": ["path"]
            }
        }
    })
}

fn write_definition() -> Value {
    json!({
        "type": "function",
        "function": {
            "name": "write",
            "description": "Create or overwrite a file. The path resolves inside the current target, or may be a canonical VFS URI for another location.",
            "parameters": {
                "type": "object",
                "properties": {
                    "path": { "type": "string", "description": "Absolute path in the current target, or a VFS URI such as container://name/path or saf://id/path." },
                    "content": { "type": "string", "description": "Full file content." }
                },
                "required": ["path", "content"]
            }
        }
    })
}

fn copy_definition() -> Value {
    json!({
        "type": "function",
        "function": {
            "name": "copy",
            "description": "Copy a file or directory from one location to another. Both paths may be absolute paths in the current target or canonical VFS URIs, including host paths (file:///...), other containers (container://name/...), and network locations such as ftp://, sftp://, saf:// and alist://. The destination is the exact new path; its parent directory must already exist.",
            "parameters": {
                "type": "object",
                "properties": {
                    "from": { "type": "string", "description": "Source file or directory: an absolute path in the current target, or a VFS URI." },
                    "to": { "type": "string", "description": "Exact destination path, or a VFS URI. Its parent directory must exist." }
                },
                "required": ["from", "to"]
            }
        }
    })
}

fn glob_definition() -> Value {
    json!({
        "type": "function",
        "function": {
            "name": "glob",
            "description": "Find files and directories matching a glob pattern. The base path resolves inside the current target or may be a VFS URI.",
            "parameters": {
                "type": "object",
                "properties": {
                    "pattern": { "type": "string", "description": "Glob such as **/*.kt, matched against paths relative to path." },
                    "path": { "type": "string", "description": "Absolute directory in the current target, or a VFS URI. Defaults to /." },
                    "max_results": { "type": "integer", "description": "Maximum number of matches. Defaults to 200." }
                },
                "required": ["pattern"]
            }
        }
    })
}

fn exec_definition() -> Value {
    json!({
        "type": "function",
        "function": {
            "name": "exec",
            "description": "Run a shell command in a container and return its exit code, stdout and stderr.",
            "parameters": {
                "type": "object",
                "properties": {
                    "command": { "type": "string", "description": "Shell command to run with the target shell." },
                    "cwd": { "type": "string", "description": "Absolute working directory. Defaults to the target daemon's cwd." },
                    "container_id": container_schema()
                },
                "required": ["command"]
            }
        }
    })
}

fn list_targets_definition() -> Value {
    json!({
        "type": "function",
        "function": {
            "name": "list_targets",
            "description": "List every execution target (host, app, containers) and every registered file location (FTP, SFTP, SAF, AList). Each entry shows its root URI when it has one, ready to use as a copy/read/write/glob path prefix.",
            "parameters": { "type": "object", "properties": {} }
        }
    })
}

fn read_run<'a>(args: &'a Value, ctx: ToolContext) -> ToolFuture<'a> {
    Box::pin(async move { read(args, &ctx.hub).await })
}

fn write_run<'a>(args: &'a Value, ctx: ToolContext) -> ToolFuture<'a> {
    Box::pin(async move { write(args, &ctx.hub, ctx.events.as_ref()).await })
}

fn copy_run<'a>(args: &'a Value, ctx: ToolContext) -> ToolFuture<'a> {
    Box::pin(async move { copy(args, &ctx.hub).await })
}

fn glob_run<'a>(args: &'a Value, ctx: ToolContext) -> ToolFuture<'a> {
    Box::pin(async move { glob(args, &ctx.hub).await })
}

fn exec_run<'a>(args: &'a Value, ctx: ToolContext) -> ToolFuture<'a> {
    Box::pin(async move {
        let container = match args
            .get("container_id")
            .and_then(Value::as_str)
            .map(str::trim)
            .filter(|value| !value.is_empty())
        {
            Some(name) => target_id(name),
            None => ctx.default_container.clone(),
        };
        exec(
            args,
            &ctx.hub,
            &container,
            ctx.events.as_ref(),
            &ctx.active_exec,
        )
        .await
    })
}

fn list_targets_run<'a>(_args: &'a Value, ctx: ToolContext) -> ToolFuture<'a> {
    Box::pin(async move { list_targets(&ctx.hub).await })
}

async fn run(
    name: &str,
    arguments: &str,
    hub: &Arc<Mutex<Hub>>,
    mcp: Option<Arc<McpManager>>,
    default_container: &str,
    events: Option<UnboundedSender<AgentEvent>>,
    active_exec: &ActiveExec,
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
    let context = ToolContext {
        hub: Arc::clone(hub),
        default_container: default_container.to_owned(),
        events,
        active_exec: Arc::clone(active_exec),
    };
    let result = match find(name) {
        Some(tool) => (tool.run)(&args, context).await,
        None => match mcp.as_ref() {
            Some(mcp) => mcp.call(name, &args).await,
            None => Err(format!("unknown tool '{name}'")),
        },
    };
    match result {
        Ok(content) => ToolResult { ok: true, content },
        Err(content) => ToolResult { ok: false, content },
    }
}

/// Internal transport id for a user-facing target name.
///
/// Container names are exposed without the `container-` prefix; the hub
/// protocol still requires it.
fn target_id(name: &str) -> String {
    match name {
        "host" | "app" => name.to_owned(),
        _ => format!("container-{name}"),
    }
}

async fn read(args: &Value, hub: &Arc<Mutex<Hub>>) -> Result<String, String> {
    let path = string_arg(args, "path")?;
    let offset = args.get("offset").and_then(Value::as_u64).unwrap_or(0);
    let length = args
        .get("length")
        .and_then(Value::as_u64)
        .unwrap_or(0)
        .min(u32::MAX as u64) as u32;
    let mut hub = hub.lock().await;
    if let Ok(directory) = hub.list_directory(&path).await {
        return Ok(render_directory(&path, &directory));
    }
    let response = hub.read(&path, offset, length).await?;
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

fn render_directory(path: &str, directory: &hub_client::filehub::ListDirectoryResponse) -> String {
    let mut output = format!("{path}\n");
    if directory.entries.is_empty() {
        output.push_str("(empty directory)\n");
    } else {
        for entry in &directory.entries {
            let kind = if entry.directory { "dir " } else { "file" };
            output.push_str(&format!(
                "  {kind} {}  ({} bytes)\n",
                entry.name, entry.size
            ));
        }
    }
    if directory.truncated {
        output.push_str("[listing truncated]\n");
    }
    output
}

async fn write(
    args: &Value,
    hub: &Arc<Mutex<Hub>>,
    events: Option<&UnboundedSender<AgentEvent>>,
) -> Result<String, String> {
    let path = string_arg(args, "path")?;
    let content = args
        .get("content")
        .and_then(Value::as_str)
        .ok_or_else(|| "missing 'content'".to_owned())?;
    let mut hub = hub.lock().await;
    let old = hub
        .read(&path, 0, MAX_WRITE_PREVIEW)
        .await
        .ok()
        .filter(|response| response.eof)
        .map(|response| String::from_utf8_lossy(&response.data).into_owned());
    let diff = old.as_deref().map(|old| line_diff(old, content));
    if let Some(events) = events {
        let _ = events.send(AgentEvent::ToolWrite {
            path: path.clone(),
            content: content.to_owned(),
            diff,
        });
    }
    let written = hub.write(&path, content.as_bytes().to_vec()).await?;
    Ok(format!("Wrote {written} bytes to {path}"))
}

/// Produce a compact line diff with a few lines of context. Files larger than
/// [MAX_DIFF_LINES] fall back to showing the new content so the UI stays
/// responsive.
fn line_diff(old: &str, new: &str) -> String {
    let old_lines: Vec<&str> = old.lines().collect();
    let new_lines: Vec<&str> = new.lines().collect();
    if old_lines.len() > MAX_DIFF_LINES || new_lines.len() > MAX_DIFF_LINES {
        let mut output = new_lines
            .iter()
            .map(|line| format!("+{line}\n"))
            .collect::<String>();
        output.push_str("[diff omitted: file is too large]\n");
        return output;
    }
    if old_lines == new_lines {
        return String::new();
    }

    let rows = old_lines.len();
    let columns = new_lines.len();
    let mut lcs = vec![vec![0u16; columns + 1]; rows + 1];
    for row in (0..rows).rev() {
        for column in (0..columns).rev() {
            lcs[row][column] = if old_lines[row] == new_lines[column] {
                lcs[row + 1][column + 1] + 1
            } else {
                lcs[row + 1][column].max(lcs[row][column + 1])
            };
        }
    }

    let mut ops: Vec<(char, &str)> = Vec::with_capacity(rows + columns);
    let (mut row, mut column) = (0, 0);
    while row < rows || column < columns {
        if row < rows && column < columns && old_lines[row] == new_lines[column] {
            ops.push((' ', old_lines[row]));
            row += 1;
            column += 1;
        } else if column < columns && (row == rows || lcs[row][column + 1] >= lcs[row + 1][column])
        {
            ops.push(('+', new_lines[column]));
            column += 1;
        } else {
            ops.push(('-', old_lines[row]));
            row += 1;
        }
    }

    const CONTEXT: usize = 3;
    let mut keep = vec![false; ops.len()];
    for (index, (tag, _)) in ops.iter().enumerate() {
        if *tag != ' ' {
            let start = index.saturating_sub(CONTEXT);
            let end = (index + CONTEXT + 1).min(ops.len());
            keep[start..end].fill(true);
        }
    }

    let mut output = String::new();
    let mut previous = false;
    for (index, (tag, line)) in ops.iter().enumerate() {
        if !keep[index] {
            previous = false;
            continue;
        }
        if !previous && index > 0 {
            output.push_str("...\n");
        }
        output.push(*tag);
        output.push_str(line);
        output.push('\n');
        previous = true;
    }
    output
}

async fn copy(args: &Value, hub: &Arc<Mutex<Hub>>) -> Result<String, String> {
    let from = string_arg(args, "from")?;
    let to = string_arg(args, "to")?;
    let bytes = hub.lock().await.copy(&from, &to).await?;
    Ok(format!("Copied {bytes} bytes from {from} to {to}"))
}

async fn glob(args: &Value, hub: &Arc<Mutex<Hub>>) -> Result<String, String> {
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
    let response = hub.lock().await.glob(&pattern, path, max_results).await?;
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
    active_exec: &ActiveExec,
) -> Result<String, String> {
    let command = string_arg(args, "command")?;
    let cwd = args.get("cwd").and_then(Value::as_str).unwrap_or_default();
    let stream = hub.lock().await.exec(container, &command, cwd).await?;
    let exec_id = stream.exec_id;
    *active_exec
        .lock()
        .unwrap_or_else(|error| error.into_inner()) = Some(exec_id);
    let outcome = async {
        let (stdout, stderr) = tokio::try_join!(
            read_all(stream.stdout, events),
            read_all(stream.stderr, events)
        )
        .map_err(|error| error.to_string())?;
        let exit_code = hub.lock().await.wait_exec(exec_id).await?;
        Ok::<_, String>((stdout, stderr, exit_code))
    }
    .await;
    *active_exec
        .lock()
        .unwrap_or_else(|error| error.into_inner()) = None;
    let (stdout, stderr, exit_code) = outcome?;
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
        // `try_join!` polls the stdout future first. Without yielding, a busy
        // stdout can be drained to EOF before stderr is ever polled, which
        // makes live output look buffered and hides stderr until exit.
        tokio::task::yield_now().await;
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

async fn list_targets(hub: &Arc<Mutex<Hub>>) -> Result<String, String> {
    let targets = hub.lock().await.targets().await?;
    if targets.is_empty() {
        return Ok("(no targets)".to_owned());
    }
    Ok(targets
        .into_iter()
        .map(|target| {
            if target.kind == "exec" {
                let state = if target.running { "running" } else { "stopped" };
                let elevated = if target.elevated { ", elevated" } else { "" };
                let label = if target.label.is_empty() {
                    display_target(&target.name)
                } else {
                    &target.label
                };
                let root = if target.root.is_empty() {
                    String::new()
                } else {
                    format!(" {}", target.root)
                };
                format!("{label} ({state}{elevated}, exec){root}")
            } else if target.root.is_empty() {
                format!("{} ({}, files)", target.label, target.kind)
            } else {
                format!("{} ({}, files) {}", target.label, target.kind, target.root)
            }
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
