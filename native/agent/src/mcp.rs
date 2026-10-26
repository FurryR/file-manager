use std::collections::HashMap;
use std::process::Stdio;
use std::sync::Arc;
use std::time::Duration;

use serde_json::{json, Value};
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::process::{Child, ChildStdin, ChildStdout, Command};
use tokio::sync::Mutex;

use crate::hub::{Capabilities, Hub};
use crate::tool::ToolInfo;

const MAX_PAGES: usize = 16;
const MAX_LOCAL_CONFIG_BYTES: usize = 1024 * 1024;

#[derive(Clone)]
pub struct McpTool {
    pub qualified: String,
    pub server: String,
    pub name: String,
    pub description: String,
    pub schema: Value,
}

pub struct McpManager {
    tools: Vec<McpTool>,
    clients: HashMap<String, Arc<Mutex<Client>>>,
}

enum Client {
    Http(HttpMcp),
    Stdio(StdioMcp),
}

struct HttpMcp {
    server: String,
    cwd: String,
    hub: Arc<Mutex<Hub>>,
    session_id: Option<String>,
    next_id: u64,
}

struct StdioMcp {
    _child: Child,
    stdin: ChildStdin,
    stdout: BufReader<ChildStdout>,
    next_id: u64,
}

/// A local stdio server declared in a workspace `.agent/mcp.json`.
struct LocalServer {
    name: String,
    command: String,
    args: Vec<String>,
    env: HashMap<String, String>,
    cwd: String,
}

/// Local stdio servers from `.agent/mcp.json` in [cwd] and its ancestors.
/// Closer files override farther ones by name, and `{"enabled": false}`
/// removes a server defined farther up. Remote entries are ignored here; the
/// hub resolves those by name.
fn read_local_servers(cwd: &str) -> Vec<LocalServer> {
    let mut directory = std::path::PathBuf::from(cwd);
    let mut chain = vec![directory.clone()];
    while directory.pop() {
        chain.push(directory.clone());
    }
    let mut merged: Vec<LocalServer> = Vec::new();
    for directory in chain.iter().rev() {
        let Ok(text) = std::fs::read_to_string(directory.join(".agent").join("mcp.json")) else {
            continue;
        };
        if text.len() > MAX_LOCAL_CONFIG_BYTES {
            continue;
        }
        let Ok(root) = serde_json::from_str::<Value>(&text) else {
            continue;
        };
        let Some(servers) = root.get("mcpServers").and_then(Value::as_object) else {
            continue;
        };
        for (name, entry) in servers {
            let transport = entry
                .get("type")
                .and_then(Value::as_str)
                .or_else(|| entry.get("transport").and_then(Value::as_str))
                .unwrap_or_default();
            let url = entry.get("url").and_then(Value::as_str).unwrap_or_default();
            let command = entry
                .get("command")
                .and_then(Value::as_str)
                .unwrap_or_default();
            if command.is_empty() || !url.is_empty() || transport == "http" {
                // No command: an `{"enabled": false}` tombstone for a server
                // from a farther file, or a remote entry the hub handles.
                if merged.iter().any(|server| server.name == *name)
                    && entry.get("enabled").and_then(Value::as_bool) == Some(false)
                {
                    merged.retain(|server| server.name != *name);
                }
                continue;
            }
            let server = LocalServer {
                name: name.clone(),
                command: command.to_owned(),
                args: entry
                    .get("args")
                    .and_then(Value::as_array)
                    .map(|items| {
                        items
                            .iter()
                            .filter_map(Value::as_str)
                            .map(str::to_owned)
                            .collect()
                    })
                    .unwrap_or_default(),
                env: entry
                    .get("env")
                    .and_then(Value::as_object)
                    .map(|object| {
                        object
                            .iter()
                            .filter_map(|(key, value)| {
                                value.as_str().map(|value| (key.clone(), value.to_owned()))
                            })
                            .collect()
                    })
                    .unwrap_or_default(),
                cwd: entry
                    .get("cwd")
                    .and_then(Value::as_str)
                    .unwrap_or_default()
                    .to_owned(),
            };
            match merged.iter_mut().find(|server| server.name == *name) {
                Some(existing) => *existing = server,
                None => merged.push(server),
            }
        }
    }
    merged
}

impl McpManager {
    /// Local stdio servers are read from the workspace `.agent/mcp.json` when
    /// the caller trusts it; remote servers are reached by name through the
    /// hub, which holds their URLs and credentials.
    pub async fn connect(capabilities: &Capabilities, hub: Arc<Mutex<Hub>>, cwd: &str) -> Self {
        let mut manager = Self {
            tools: Vec::new(),
            clients: HashMap::new(),
        };
        let mut clients: Vec<(String, Client)> = Vec::new();
        if capabilities.workspace_mcp {
            for server in read_local_servers(cwd) {
                match StdioMcp::spawn(&server) {
                    Ok(client) => clients.push((server.name, Client::Stdio(client))),
                    Err(_) => continue,
                }
            }
        }
        for name in &capabilities.remote_servers {
            clients.push((
                name.clone(),
                Client::Http(HttpMcp {
                    server: name.clone(),
                    cwd: cwd.to_owned(),
                    hub: Arc::clone(&hub),
                    session_id: None,
                    next_id: 1,
                }),
            ));
        }
        for (name, mut client) in clients {
            if !matches!(
                tokio::time::timeout(Duration::from_secs(20), client.initialize()).await,
                Ok(Ok(()))
            ) {
                continue;
            }
            let tools =
                match tokio::time::timeout(Duration::from_secs(20), client.list_tools()).await {
                    Ok(Ok(tools)) => tools,
                    _ => continue,
                };
            let shared = Arc::new(Mutex::new(client));
            for (tool, description, schema) in tools {
                let qualified = qualified_name(&name, &tool);
                if manager.tools.iter().any(|tool| tool.qualified == qualified) {
                    continue;
                }
                manager.tools.push(McpTool {
                    qualified,
                    server: name.clone(),
                    name: tool,
                    description,
                    schema,
                });
            }
            manager.clients.insert(name, shared);
        }
        manager
    }

    pub fn definitions(&self) -> Vec<Value> {
        self.tools
            .iter()
            .map(|tool| {
                json!({
                    "type": "function",
                    "function": {
                        "name": tool.qualified,
                        "description": tool.description,
                        "parameters": tool.schema,
                    }
                })
            })
            .collect()
    }

    pub fn info(&self, name: &str, arguments: &Value) -> Option<ToolInfo> {
        let tool = self.tools.iter().find(|tool| tool.qualified == name)?;
        let value = arguments.to_string().replace('\n', " ");
        Some(ToolInfo {
            detail: truncate(&value, 120),
            description: Some(tool.description.clone()),
            command: None,
        })
    }

    pub async fn call(&self, name: &str, arguments: &Value) -> Result<String, String> {
        let tool = self
            .tools
            .iter()
            .find(|tool| tool.qualified == name)
            .ok_or_else(|| format!("unknown tool '{name}'"))?;
        let client = self
            .clients
            .get(&tool.server)
            .ok_or_else(|| format!("MCP server '{}' is unavailable", tool.server))?;
        client.lock().await.call_tool(&tool.name, arguments).await
    }
}

impl Client {
    async fn initialize(&mut self) -> Result<(), String> {
        self.request(
            "initialize",
            json!({
                "protocolVersion": "2025-03-26",
                "capabilities": {},
                "clientInfo": {
                    "name": "file-manager-agent",
                    "version": env!("CARGO_PKG_VERSION"),
                },
            }),
        )
        .await?;
        self.notify("notifications/initialized", json!({})).await
    }

    async fn list_tools(&mut self) -> Result<Vec<(String, String, Value)>, String> {
        let mut tools = Vec::new();
        let mut cursor: Option<String> = None;
        for _ in 0..MAX_PAGES {
            let params = cursor
                .as_ref()
                .map(|cursor| json!({ "cursor": cursor }))
                .unwrap_or_else(|| json!({}));
            let result = self.request("tools/list", params).await?;
            if let Some(entries) = result.get("tools").and_then(Value::as_array) {
                for entry in entries {
                    let name = match entry.get("name").and_then(Value::as_str) {
                        Some(name) if !name.is_empty() => name.to_owned(),
                        _ => continue,
                    };
                    let description = entry
                        .get("description")
                        .and_then(Value::as_str)
                        .unwrap_or_default()
                        .to_owned();
                    let schema = entry
                        .get("inputSchema")
                        .cloned()
                        .unwrap_or_else(|| json!({ "type": "object", "properties": {} }));
                    tools.push((name, description, schema));
                }
            }
            cursor = result
                .get("nextCursor")
                .and_then(Value::as_str)
                .filter(|cursor| !cursor.is_empty())
                .map(str::to_owned);
            if cursor.is_none() {
                break;
            }
        }
        Ok(tools)
    }

    async fn call_tool(&mut self, name: &str, arguments: &Value) -> Result<String, String> {
        let result = self
            .request(
                "tools/call",
                json!({ "name": name, "arguments": arguments }),
            )
            .await?;
        let mut output = String::new();
        if let Some(content) = result.get("content").and_then(Value::as_array) {
            for item in content {
                if item.get("type").and_then(Value::as_str) == Some("text") {
                    if let Some(text) = item.get("text").and_then(Value::as_str) {
                        output.push_str(text);
                    }
                } else {
                    output.push_str(&item.to_string());
                }
            }
        }
        if output.is_empty() {
            if let Some(structured) = result.get("structuredContent") {
                output = structured.to_string();
            }
        }
        if result.get("isError").and_then(Value::as_bool) == Some(true) {
            return Err(if output.is_empty() {
                "MCP tool failed".to_owned()
            } else {
                output
            });
        }
        Ok(output)
    }

    async fn request(&mut self, method: &str, params: Value) -> Result<Value, String> {
        match self {
            Client::Http(client) => client.request(method, params).await,
            Client::Stdio(client) => client.request(method, params).await,
        }
    }

    async fn notify(&mut self, method: &str, params: Value) -> Result<(), String> {
        match self {
            Client::Http(client) => client.notify(method, params).await,
            Client::Stdio(client) => client.notify(method, params).await,
        }
    }
}

impl HttpMcp {
    async fn request(&mut self, method: &str, params: Value) -> Result<Value, String> {
        let id = self.next_id;
        self.next_id += 1;
        let body = json!({
            "jsonrpc": "2.0",
            "id": id,
            "method": method,
            "params": params,
        })
        .to_string();
        let mut headers = HashMap::from([
            ("Content-Type".to_owned(), "application/json".to_owned()),
            (
                "Accept".to_owned(),
                "application/json, text/event-stream".to_owned(),
            ),
        ]);
        if let Some(session) = &self.session_id {
            headers.insert("Mcp-Session-Id".to_owned(), session.clone());
        }
        if method != "initialize" {
            headers.insert("MCP-Protocol-Version".to_owned(), "2025-03-26".to_owned());
        }
        let response = self
            .hub
            .lock()
            .await
            .remote_mcp_call(
                &self.server,
                &self.cwd,
                "POST",
                "",
                &headers,
                body.into_bytes(),
            )
            .await?;
        if let Some(session) = header(&response.headers, "mcp-session-id") {
            self.session_id = Some(session.to_owned());
        }
        if response.status >= 400 {
            return Err(format!("MCP server returned HTTP {}", response.status));
        }
        let content_type = header(&response.headers, "content-type").unwrap_or_default();
        let value = if content_type.contains("text/event-stream") {
            parse_sse_response(&response.body, id)?
        } else {
            let text = String::from_utf8_lossy(&response.body);
            serde_json::from_str::<Value>(text.trim())
                .map_err(|_| "MCP server returned invalid JSON".to_owned())?
        };
        extract_result(value)
    }

    async fn notify(&mut self, method: &str, params: Value) -> Result<(), String> {
        let body = json!({
            "jsonrpc": "2.0",
            "method": method,
            "params": params,
        })
        .to_string();
        let mut headers = HashMap::from([
            ("Content-Type".to_owned(), "application/json".to_owned()),
            (
                "Accept".to_owned(),
                "application/json, text/event-stream".to_owned(),
            ),
        ]);
        if let Some(session) = &self.session_id {
            headers.insert("Mcp-Session-Id".to_owned(), session.clone());
        }
        headers.insert("MCP-Protocol-Version".to_owned(), "2025-03-26".to_owned());
        self.hub
            .lock()
            .await
            .remote_mcp_call(
                &self.server,
                &self.cwd,
                "POST",
                "",
                &headers,
                body.into_bytes(),
            )
            .await?;
        Ok(())
    }
}

impl StdioMcp {
    fn spawn(config: &LocalServer) -> Result<Self, String> {
        if config.command.is_empty() {
            return Err("MCP stdio server has no command".to_owned());
        }
        let mut command = Command::new(config.command.as_str());
        command.args(config.args.iter().map(String::as_str));
        for (name, value) in &config.env {
            command.env(name.as_str(), value.as_str());
        }
        if !config.cwd.is_empty() {
            command.current_dir(config.cwd.as_str());
        }
        command
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::null())
            .kill_on_drop(true);
        let mut child = command.spawn().map_err(|error| error.to_string())?;
        let stdin = child.stdin.take().ok_or("MCP process has no stdin")?;
        let stdout = child.stdout.take().ok_or("MCP process has no stdout")?;
        Ok(Self {
            _child: child,
            stdin,
            stdout: BufReader::new(stdout),
            next_id: 1,
        })
    }

    async fn request(&mut self, method: &str, params: Value) -> Result<Value, String> {
        let id = self.next_id;
        self.next_id += 1;
        self.write(json!({
            "jsonrpc": "2.0",
            "id": id,
            "method": method,
            "params": params,
        }))
        .await?;
        loop {
            let mut line = String::new();
            let read = self
                .stdout
                .read_line(&mut line)
                .await
                .map_err(|error| error.to_string())?;
            if read == 0 {
                return Err("MCP process closed stdout".to_owned());
            }
            let value: Value = match serde_json::from_str(line.trim()) {
                Ok(value) => value,
                Err(_) => continue,
            };
            if value.get("id").and_then(Value::as_u64) != Some(id) {
                continue;
            }
            return extract_result(value);
        }
    }

    async fn notify(&mut self, method: &str, params: Value) -> Result<(), String> {
        self.write(json!({
            "jsonrpc": "2.0",
            "method": method,
            "params": params,
        }))
        .await
    }

    async fn write(&mut self, value: Value) -> Result<(), String> {
        let mut line = value.to_string();
        line.push('\n');
        self.stdin
            .write_all(line.as_bytes())
            .await
            .map_err(|error| error.to_string())?;
        self.stdin.flush().await.map_err(|error| error.to_string())
    }
}

fn extract_result(value: Value) -> Result<Value, String> {
    if let Some(error) = value.get("error") {
        let message = error
            .get("message")
            .and_then(Value::as_str)
            .unwrap_or("MCP request failed");
        return Err(message.to_owned());
    }
    Ok(value.get("result").cloned().unwrap_or(Value::Null))
}

fn parse_sse_response(body: &[u8], id: u64) -> Result<Value, String> {
    let text = String::from_utf8_lossy(body);
    for line in text.lines() {
        let Some(data) = line.trim().strip_prefix("data:") else {
            continue;
        };
        let data = data.trim();
        if data.is_empty() || data == "[DONE]" {
            continue;
        }
        let Ok(value) = serde_json::from_str::<Value>(data) else {
            continue;
        };
        if value.get("id").and_then(Value::as_u64) == Some(id) {
            return Ok(value);
        }
    }
    Err("MCP server returned no matching SSE response".to_owned())
}

fn header<'a>(headers: &'a HashMap<String, String>, name: &str) -> Option<&'a str> {
    headers
        .iter()
        .find(|(key, _)| key.eq_ignore_ascii_case(name))
        .map(|(_, value)| value.as_str())
}

fn qualified_name(server: &str, tool: &str) -> String {
    format!("mcp__{}__{}", sanitize(server), sanitize(tool))
}

fn sanitize(value: &str) -> String {
    let mut output: String = value
        .chars()
        .map(|char| {
            if char.is_ascii_alphanumeric() || char == '_' || char == '-' {
                char
            } else {
                '_'
            }
        })
        .collect();
    if output.is_empty() {
        output.push('_');
    }
    output
}

fn truncate(value: &str, limit: usize) -> String {
    if value.chars().count() <= limit {
        return value.to_owned();
    }
    let mut result: String = value.chars().take(limit).collect();
    result.push('…');
    result
}
