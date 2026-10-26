use std::collections::HashMap;
use std::io;
use std::os::fd::OwnedFd;

use hub_client::filehub::{
    self, chat_event, hub_request::Op, hub_response, ChatRequest, CloseExecRequest,
    CopyFileRequest, ExecRequest, GetAiConfigRequest, GetCapabilitiesRequest, GetHistoryRequest,
    GetModelsRequest, GlobRequest, HubRequest, ListDirectoryRequest, ListSessionsRequest,
    ListTargetsRequest, ReadFileRequest, RemoteMcpCall, SetActiveSessionRequest,
    SetAiSelectionRequest, SetHistoryRequest, SubscribeSessionEventsRequest, WaitExecRequest,
    WriteFileRequest,
};
use serde_json::Value;
use tokio::sync::mpsc::{UnboundedReceiver, UnboundedSender};

use crate::model::{AgentEvent, Message, ToolCall, Usage};
use crate::model_info::ModelCatalog;

/// User-facing target name. The `container-` prefix is an internal transport
/// id and must not leak into the agent UI or model-visible tool output.
pub fn display_target(name: &str) -> &str {
    name.strip_prefix("container-").unwrap_or(name)
}

/// A control connection to the file-manager hub, with the caller's container.
pub struct Hub {
    connection: hub_client::Connection,
    next_id: u32,
    pub caller: String,
}

pub struct ExecStream {
    pub stdout: OwnedFd,
    pub stderr: OwnedFd,
    pub exec_id: u64,
}

/// Server-pushed session titles on a dedicated hub connection.
pub struct TitleStream {
    receiver: UnboundedReceiver<(String, String)>,
    task: tokio::task::JoinHandle<()>,
}

impl TitleStream {
    pub async fn recv(&mut self) -> Option<(String, String)> {
        self.receiver.recv().await
    }
}

impl Drop for TitleStream {
    fn drop(&mut self) {
        self.task.abort();
    }
}

#[derive(Clone)]
pub struct Target {
    pub name: String,
    pub running: bool,
    pub elevated: bool,
    pub kind: String,
    pub label: String,
    pub root: String,
}

#[derive(Clone)]
pub struct SessionEntry {
    pub id: String,
    pub title: String,
    pub message_count: u32,
}

/// Remote MCP servers the hub may proxy for this caller, plus whether
/// workspace `.agent/mcp.json` files are trusted. Server URLs and credential
/// headers stay in the app and never reach the agent.
#[derive(Default)]
pub struct Capabilities {
    pub remote_servers: Vec<String>,
    pub workspace_mcp: bool,
}

impl Hub {
    /// Connect and perform the Hello handshake using `$HUB_SOCKET`.
    pub async fn connect() -> io::Result<Self> {
        let (connection, root) = hub_client::Connection::connect_from_env().await?;
        match root.container_id.as_str() {
            "host" | "app" => {}
            id if id.starts_with("container-") && id.len() > "container-".len() => {}
            _ => return Err(io::Error::other("hub returned an invalid caller context")),
        }
        Ok(Self {
            connection,
            next_id: 1,
            caller: root.container_id,
        })
    }

    async fn request(&mut self, op: Op) -> Result<filehub::HubResponse, String> {
        let (response, fds) = self.request_with_fds(op).await?;
        // Unexpected descriptors are closed when this vector drops.
        drop(fds);
        Ok(response)
    }

    async fn request_with_fds(
        &mut self,
        op: Op,
    ) -> Result<(filehub::HubResponse, Vec<OwnedFd>), String> {
        let id = self.next_id;
        self.next_id = self.next_id.wrapping_add(1);
        if self.next_id == 0 {
            self.next_id = 1;
        }
        let (response, fds) = self
            .connection
            .round_trip_with_fds(HubRequest { id, op: Some(op) }, &[])
            .await
            .map_err(|error| error.to_string())?;
        if !response.ok {
            return Err(if response.error.is_empty() {
                "hub request failed".to_owned()
            } else {
                response.error
            });
        }
        Ok((response, fds))
    }

    pub async fn ai_config(&mut self) -> Result<filehub::AiConfigResponse, String> {
        let response = self.request(Op::GetAiConfig(GetAiConfigRequest {})).await?;
        match response.result {
            Some(hub_response::Result::AiConfig(config)) => Ok(config),
            _ => Err("hub returned no AI configuration".to_owned()),
        }
    }

    pub async fn list_sessions(&mut self) -> Result<Vec<SessionEntry>, String> {
        let response = self
            .request(Op::ListSessions(ListSessionsRequest {}))
            .await?;
        match response.result {
            Some(hub_response::Result::Sessions(sessions)) => Ok(sessions
                .sessions
                .into_iter()
                .map(|session| SessionEntry {
                    id: session.id,
                    title: session.title,
                    message_count: session.message_count,
                })
                .collect()),
            _ => Err("hub returned no sessions".to_owned()),
        }
    }

    pub async fn set_active_session(&mut self, session_id: &str) -> Result<(), String> {
        self.request(Op::SetActiveSession(SetActiveSessionRequest {
            session_id: session_id.to_owned(),
        }))
        .await?;
        Ok(())
    }

    pub async fn subscribe_titles() -> Result<TitleStream, String> {
        let (connection, _) = hub_client::Connection::connect_from_env()
            .await
            .map_err(|error| error.to_string())?;
        let id = 1;
        connection
            .send_request(HubRequest {
                id,
                op: Some(Op::SubscribeSessionEvents(SubscribeSessionEventsRequest {})),
            })
            .await
            .map_err(|error| error.to_string())?;
        let (response, _) = connection
            .receive_response(id)
            .await
            .map_err(|error| error.to_string())?;
        if !response.ok {
            return Err(if response.error.is_empty() {
                "hub rejected the session event subscription".to_owned()
            } else {
                response.error
            });
        }
        let (sender, receiver) = tokio::sync::mpsc::unbounded_channel();
        let task = tokio::spawn(async move {
            loop {
                match connection.receive_response(id).await {
                    Ok((response, _)) if response.ok => {
                        if let Some(hub_response::Result::SessionTitle(title)) = response.result {
                            if sender.send((title.session_id, title.title)).is_err() {
                                break;
                            }
                        }
                    }
                    _ => break,
                }
            }
        });
        Ok(TitleStream { receiver, task })
    }

    pub async fn set_ai_selection(&mut self, model: &str, effort: &str) -> Result<(), String> {
        self.request(Op::SetAiSelection(SetAiSelectionRequest {
            model: model.to_owned(),
            effort: effort.to_owned(),
        }))
        .await?;
        Ok(())
    }

    pub async fn targets(&mut self) -> Result<Vec<Target>, String> {
        let response = self.request(Op::ListTargets(ListTargetsRequest {})).await?;
        match response.result {
            Some(hub_response::Result::Targets(targets)) => Ok(targets
                .targets
                .into_iter()
                .map(|target| Target {
                    name: target.name,
                    running: target.running,
                    elevated: target.elevated,
                    kind: target.kind,
                    label: target.label,
                    root: target.root,
                })
                .collect()),
            _ => Err("hub returned no target list".to_owned()),
        }
    }

    pub async fn read(
        &mut self,
        path: &str,
        offset: u64,
        length: u32,
    ) -> Result<filehub::ReadFileResponse, String> {
        let response = self
            .request(Op::ReadFile(ReadFileRequest {
                path: path.to_owned(),
                offset,
                length,
            }))
            .await?;
        match response.result {
            Some(hub_response::Result::ReadFile(read)) => Ok(read),
            _ => Err("hub returned no read result".to_owned()),
        }
    }

    pub async fn list_directory(
        &mut self,
        path: &str,
    ) -> Result<filehub::ListDirectoryResponse, String> {
        let response = self
            .request(Op::ListDirectory(ListDirectoryRequest {
                path: path.to_owned(),
            }))
            .await?;
        match response.result {
            Some(hub_response::Result::ListDirectory(list)) => Ok(list),
            _ => Err("hub returned no directory result".to_owned()),
        }
    }

    pub async fn write(&mut self, path: &str, data: Vec<u8>) -> Result<u64, String> {
        let response = self
            .request(Op::WriteFile(WriteFileRequest {
                path: path.to_owned(),
                data,
            }))
            .await?;
        match response.result {
            Some(hub_response::Result::WriteFile(write)) => Ok(write.bytes_written),
            _ => Err("hub returned no write result".to_owned()),
        }
    }

    pub async fn copy(&mut self, from: &str, to: &str) -> Result<u64, String> {
        let response = self
            .request(Op::CopyFile(CopyFileRequest {
                from: from.to_owned(),
                to: to.to_owned(),
            }))
            .await?;
        match response.result {
            Some(hub_response::Result::CopyFile(copy)) => Ok(copy.bytes_copied),
            _ => Err("hub returned no copy result".to_owned()),
        }
    }

    pub async fn glob(
        &mut self,
        pattern: &str,
        path: &str,
        max_results: u32,
    ) -> Result<filehub::GlobResponse, String> {
        let response = self
            .request(Op::Glob(GlobRequest {
                pattern: pattern.to_owned(),
                path: path.to_owned(),
                max_results,
            }))
            .await?;
        match response.result {
            Some(hub_response::Result::Glob(glob)) => Ok(glob),
            _ => Err("hub returned no glob result".to_owned()),
        }
    }

    pub async fn exec(
        &mut self,
        container_id: &str,
        command: &str,
        cwd: &str,
    ) -> Result<ExecStream, String> {
        let (response, fds) = self
            .request_with_fds(Op::Exec(ExecRequest {
                command: "sh".to_owned(),
                args: vec!["-c".to_owned(), command.to_owned()],
                env: Default::default(),
                cwd: cwd.to_owned(),
                user: None,
                term: String::new(),
                rows: 0,
                cols: 0,
                nice: None,
                container_id: container_id.to_owned(),
                pipes: true,
            }))
            .await?;
        let exec_id = match response.result {
            Some(hub_response::Result::Exec(exec)) => exec.exec_id,
            _ => return Err("hub returned no exec result".to_owned()),
        };
        if fds.len() != 2 {
            return Err(format!(
                "hub returned {} exec descriptors, expected stdout and stderr",
                fds.len()
            ));
        }
        let mut fds = fds.into_iter();
        Ok(ExecStream {
            stdout: fds.next().expect("checked two descriptors"),
            stderr: fds.next().expect("checked two descriptors"),
            exec_id,
        })
    }

    pub async fn wait_exec(&mut self, exec_id: u64) -> Result<i32, String> {
        let response = self
            .request(Op::WaitExec(WaitExecRequest { exec_id }))
            .await?;
        match response.result {
            Some(hub_response::Result::Wait(wait)) => Ok(wait.exit_code),
            _ => Err("hub returned no wait result".to_owned()),
        }
    }

    pub async fn close_exec(&mut self, exec_id: u64) -> Result<(), String> {
        self.request(Op::CloseExec(CloseExecRequest { exec_id }))
            .await?;
        Ok(())
    }

    pub async fn chat(
        &mut self,
        session_id: &str,
        model: &str,
        effort: &str,
        system: &str,
        messages: &[Message],
        tools: &Value,
        events: &UnboundedSender<AgentEvent>,
        append: bool,
        base_count: u32,
    ) -> Result<Message, String> {
        let messages_json = serde_json::to_string(messages).map_err(|error| error.to_string())?;
        let request = ChatRequest {
            session_id: session_id.to_owned(),
            model: model.to_owned(),
            effort: effort.to_owned(),
            system: system.to_owned(),
            messages_json,
            tools_json: tools.to_string(),
            append,
            base_count,
        };
        let id = self.next_request_id();
        self.connection
            .send_request(HubRequest {
                id,
                op: Some(Op::Chat(request)),
            })
            .await
            .map_err(|error| error.to_string())?;

        let mut content = String::new();
        let mut calls: Vec<ToolCall> = Vec::new();
        let mut error: Option<String> = None;
        loop {
            let (response, _) = self
                .connection
                .receive_response(id)
                .await
                .map_err(|error| error.to_string())?;
            if !response.ok {
                return Err(if response.error.is_empty() {
                    "hub chat failed".to_owned()
                } else {
                    response.error
                });
            }
            match response.result {
                Some(hub_response::Result::Chat(event)) => match event.event {
                    Some(chat_event::Event::ReasoningDelta(text)) => {
                        let _ = events.send(AgentEvent::ReasoningDelta(text));
                    }
                    Some(chat_event::Event::ContentDelta(text)) => {
                        content.push_str(&text);
                        let _ = events.send(AgentEvent::AnswerDelta(text));
                    }
                    Some(chat_event::Event::ToolCalling(_)) => {
                        let _ = events.send(AgentEvent::ToolCalling);
                    }
                    Some(chat_event::Event::Usage(usage)) => {
                        let _ = events.send(AgentEvent::Usage(Usage {
                            prompt_tokens: usage.prompt_tokens,
                            completion_tokens: usage.completion_tokens,
                        }));
                    }
                    Some(chat_event::Event::ToolCallsJson(json)) => {
                        calls = serde_json::from_str(&json)
                            .map_err(|_| "hub returned invalid tool calls".to_owned())?;
                    }
                    Some(chat_event::Event::Error(message)) => error = Some(message),
                    Some(chat_event::Event::Done(_)) => break,
                    None => {}
                },
                _ => return Err("hub returned no chat result".to_owned()),
            }
        }
        if let Some(error) = error {
            return Err(error);
        }
        let message = Message {
            role: "assistant".to_owned(),
            content: (!content.is_empty()).then_some(content),
            tool_calls: (!calls.is_empty()).then_some(calls),
            tool_call_id: None,
        };
        if message.content_text().trim().is_empty() && !message.has_tools() {
            return Err("API returned no assistant message.".to_owned());
        }
        Ok(message)
    }

    pub async fn load_history(&mut self, session_id: &str) -> Result<(String, String), String> {
        let response = self
            .request(Op::GetHistory(GetHistoryRequest {
                session_id: session_id.to_owned(),
            }))
            .await?;
        match response.result {
            Some(hub_response::Result::History(history)) => {
                Ok((history.messages_json, history.title))
            }
            _ => Err("hub returned no history".to_owned()),
        }
    }

    pub async fn save_history(
        &mut self,
        session_id: &str,
        messages: &[Message],
    ) -> Result<(), String> {
        let messages_json = serde_json::to_string(messages).map_err(|error| error.to_string())?;
        self.request(Op::SetHistory(SetHistoryRequest {
            session_id: session_id.to_owned(),
            messages_json,
        }))
        .await?;
        Ok(())
    }

    pub async fn catalog(&mut self) -> Result<ModelCatalog, String> {
        let response = self.request(Op::GetModels(GetModelsRequest {})).await?;
        match response.result {
            Some(hub_response::Result::Models(models)) => {
                Ok(ModelCatalog::from_json(&models.catalog_json))
            }
            _ => Err("hub returned no model catalog".to_owned()),
        }
    }

    pub async fn capabilities(&mut self, cwd: &str) -> Result<Capabilities, String> {
        let response = self
            .request(Op::GetCapabilities(GetCapabilitiesRequest {
                cwd: cwd.to_owned(),
            }))
            .await?;
        match response.result {
            Some(hub_response::Result::Capabilities(capabilities)) => Ok(Capabilities {
                remote_servers: capabilities.remote_mcp_servers,
                workspace_mcp: capabilities.workspace_mcp,
            }),
            _ => Err("hub returned no capabilities".to_owned()),
        }
    }

    /// Proxy one HTTP request for a remote MCP server, referenced by its
    /// configured name. The hub injects the server's credential headers; the
    /// supplied headers carry protocol state only.
    pub async fn remote_mcp_call(
        &mut self,
        server: &str,
        cwd: &str,
        method: &str,
        path: &str,
        headers: &HashMap<String, String>,
        body: Vec<u8>,
    ) -> Result<RemoteMcpResult, String> {
        let request = RemoteMcpCall {
            server: server.to_owned(),
            cwd: cwd.to_owned(),
            method: method.to_owned(),
            path: path.to_owned(),
            headers: headers.clone(),
            body,
        };
        let id = self.next_request_id();
        self.connection
            .send_request(HubRequest {
                id,
                op: Some(Op::RemoteMcpCall(request)),
            })
            .await
            .map_err(|error| error.to_string())?;
        let mut result = RemoteMcpResult::default();
        let mut error: Option<String> = None;
        loop {
            let (response, _) = self
                .connection
                .receive_response(id)
                .await
                .map_err(|error| error.to_string())?;
            if !response.ok {
                return Err(if response.error.is_empty() {
                    "hub MCP request failed".to_owned()
                } else {
                    response.error
                });
            }
            match response.result {
                Some(hub_response::Result::RemoteMcpCall(event)) => {
                    if event.status != 0 {
                        result.status = event.status;
                    }
                    result.headers.extend(event.headers);
                    if !event.data.is_empty() {
                        result.body.extend_from_slice(&event.data);
                    }
                    if !event.error.is_empty() {
                        error = Some(event.error);
                    }
                    if event.done {
                        break;
                    }
                }
                _ => return Err("hub returned no MCP response".to_owned()),
            }
        }
        if let Some(error) = error {
            return Err(error);
        }
        Ok(result)
    }

    fn next_request_id(&mut self) -> u32 {
        let id = self.next_id;
        self.next_id = self.next_id.wrapping_add(1);
        if self.next_id == 0 {
            self.next_id = 1;
        }
        id
    }
}

#[derive(Default)]
pub struct RemoteMcpResult {
    pub status: i32,
    pub headers: HashMap<String, String>,
    pub body: Vec<u8>,
}
