use std::collections::HashMap;
use std::io;
use std::os::fd::OwnedFd;

use hub_client::fileagent::{self, agent_event, AgentApprovalReply, AgentAttachRequest,
    AgentMcpResult, AgentMcpTools, AgentPromptRequest};
use hub_client::filehub::{
    self, hub_request::Op, hub_response, GetAiConfigRequest, GetCapabilitiesRequest,
    GetHistoryRequest, GetModelsRequest, HubRequest, ListSessionsRequest, RemoteMcpCall,
    SetActiveSessionRequest, SetAiSelectionRequest, SetHistoryRequest,
    SubscribeSessionEventsRequest,
};
use serde_json::Value;
use tokio::sync::mpsc::{UnboundedReceiver, UnboundedSender};
use std::sync::Arc;

use crate::mcp::McpManager;
use crate::model::{AgentEvent, Message, Usage};
use crate::model_info::ModelCatalog;


/// A control connection to the file-manager hub, with the caller's container.
pub struct Hub {
    connection: hub_client::Connection,
    next_id: u32,
    pub caller: String,
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

    /// Attach this connection to a Kotlin agent session.
    pub async fn agent_attach(
        &mut self,
        session_id: &str,
        terminal_id: &str,
        cwd: &str,
        target: &str,
        model: &str,
        effort: &str,
        workspace_mcp: bool,
    ) -> Result<fileagent::AgentAttachResponse, String> {
        let response = self
            .request(Op::AgentAttach(AgentAttachRequest {
                session_id: session_id.to_owned(),
                terminal_id: terminal_id.to_owned(),
                cwd: cwd.to_owned(),
                target: target.to_owned(),
                model: model.to_owned(),
                effort: effort.to_owned(),
                workspace_mcp,
            }))
            .await?;
        match response.result {
            Some(hub_response::Result::AgentAttach(attach)) => Ok(attach),
            _ => Err("hub returned no agent attachment".to_owned()),
        }
    }

    pub async fn agent_set_mcp_tools(&mut self, definitions: &[Value]) -> Result<(), String> {
        let json = serde_json::to_string(definitions).map_err(|error| error.to_string())?;
        self.request(Op::AgentMcpTools(AgentMcpTools {
            definitions_json: json,
        }))
        .await?;
        Ok(())
    }

    pub async fn agent_cancel(&mut self) -> Result<(), String> {
        self.request(Op::AgentCancel(fileagent::AgentCancelRequest {}))
            .await?;
        Ok(())
    }

    pub async fn agent_compact(
        &mut self,
        model: &str,
        effort: &str,
    ) -> Result<AgentOutcome, String> {
        let id = self.next_request_id();
        self.connection
            .send_request(HubRequest {
                id,
                op: Some(Op::AgentCompact(fileagent::AgentCompactRequest {
                    model: model.to_owned(),
                    effort: effort.to_owned(),
                })),
            })
            .await
            .map_err(|error| error.to_string())?;
        let mut finished: Option<AgentOutcome> = None;
        let mut error: Option<String> = None;
        loop {
            let (response, _) = self
                .connection
                .receive_response(id)
                .await
                .map_err(|error| error.to_string())?;
            if !response.ok {
                return Err(if response.error.is_empty() {
                    "compact failed".to_owned()
                } else {
                    response.error
                });
            }
            let Some(hub_response::Result::AgentEvent(event)) = response.result else {
                return Err("hub returned no compact event".to_owned());
            };
            match event.event {
                Some(agent_event::Event::Finished(done)) => {
                    let messages = serde_json::from_str::<Vec<Message>>(&done.messages_json)
                        .unwrap_or_default();
                    finished = Some(AgentOutcome {
                        messages,
                        answer: done.answer,
                    });
                }
                Some(agent_event::Event::Error(message)) => error = Some(message),
                Some(agent_event::Event::Done(_)) => break,
                _ => {}
            }
        }
        if let Some(error) = error {
            return Err(error);
        }
        finished.ok_or_else(|| "compact returned no final history".to_owned())
    }

    /// Run one prompt through the Kotlin agent loop, streaming UI events.
    ///
    /// Local MCP calls arrive as events on this stream; the terminal answers
    /// them inline. Approval replies arrive on [approvals] and are forwarded to
    /// the agent while the stream stays open.
    pub async fn agent_run(
        &mut self,
        text: &str,
        model: &str,
        effort: &str,
        mcp: Arc<McpManager>,
        approvals: &mut UnboundedReceiver<(u64, bool)>,
        events: &UnboundedSender<AgentEvent>,
    ) -> Result<AgentOutcome, String> {
        let id = self.next_request_id();
        self.connection
            .send_request(HubRequest {
                id,
                op: Some(Op::AgentPrompt(AgentPromptRequest {
                    text: text.to_owned(),
                    model: model.to_owned(),
                    effort: effort.to_owned(),
                })),
            })
            .await
            .map_err(|error| error.to_string())?;
        let mut finished: Option<AgentOutcome> = None;
        let mut error: Option<String> = None;
        loop {
            tokio::select! {
                incoming = self.connection.receive_response(id) => {
                    let (response, _) = incoming.map_err(|error| error.to_string())?;
                    if !response.ok {
                        return Err(if response.error.is_empty() {
                            "agent request failed".to_owned()
                        } else {
                            response.error
                        });
                    }
                    let Some(hub_response::Result::AgentEvent(event)) = response.result else {
                        return Err("hub returned no agent event".to_owned());
                    };
                    match event.event {
                        Some(agent_event::Event::ReasoningDelta(text)) => {
                            let _ = events.send(AgentEvent::ReasoningDelta(text));
                        }
                        Some(agent_event::Event::ContentDelta(text)) => {
                            let _ = events.send(AgentEvent::AnswerDelta(text));
                        }
                        Some(agent_event::Event::ToolCalling(_)) => {
                            let _ = events.send(AgentEvent::ToolCalling);
                        }
                        Some(agent_event::Event::ToolCall(call)) => {
                            let _ = events.send(AgentEvent::ToolCall {
                                name: call.name,
                                detail: call.detail,
                                description: (!call.description.is_empty()).then_some(call.description),
                                command: (!call.command.is_empty()).then_some(call.command),
                            });
                        }
                        Some(agent_event::Event::ToolWrite(write)) => {
                            let _ = events.send(AgentEvent::ToolWrite {
                                path: write.path,
                                content: write.content,
                                diff: (!write.diff.is_empty()).then_some(write.diff),
                            });
                        }
                        Some(agent_event::Event::ToolOutput(bytes)) => {
                            let _ = events.send(AgentEvent::ToolOutput(bytes));
                        }
                        Some(agent_event::Event::ToolResultOk(ok)) => {
                            let _ = events.send(AgentEvent::ToolResult { ok });
                        }
                        Some(agent_event::Event::Usage(usage)) => {
                            let _ = events.send(AgentEvent::Usage(Usage {
                                prompt_tokens: usage.prompt_tokens,
                                completion_tokens: usage.completion_tokens,
                            }));
                        }
                        Some(agent_event::Event::Approval(request)) => {
                            let _ = events.send(AgentEvent::ApprovalRequest {
                                id: request.approval_id,
                                name: request.name,
                                reason: request.reason,
                            });
                        }
                        Some(agent_event::Event::McpCall(call)) => {
                            let args = serde_json::from_str(&call.arguments)
                                .unwrap_or(Value::Null);
                            let result = mcp.call(&call.name, &args).await;
                            let (ok, content) = match result {
                                Ok(content) => (true, content),
                                Err(error) => (false, error),
                            };
                            self.request(Op::AgentMcpResult(AgentMcpResult {
                                request_id: call.request_id,
                                ok,
                                content,
                            }))
                            .await?;
                        }
                        Some(agent_event::Event::Finished(done)) => {
                            let messages = serde_json::from_str::<Vec<Message>>(&done.messages_json)
                                .unwrap_or_default();
                            finished = Some(AgentOutcome {
                                messages,
                                answer: done.answer,
                            });
                        }
                        Some(agent_event::Event::Error(message)) => error = Some(message),
                        Some(agent_event::Event::Done(_)) => break,
                        None => {}
                    }
                }
                approval = approvals.recv() => {
                    let Some((approval_id, allow)) = approval else { continue };
                    self.request(Op::AgentApproval(AgentApprovalReply {
                        approval_id,
                        allow,
                    }))
                    .await?;
                }
            }
        }
        if let Some(error) = error {
            return Err(error);
        }
        finished.ok_or_else(|| "agent returned no final history".to_owned())
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

pub struct AgentOutcome {
    pub messages: Vec<Message>,
    pub answer: String,
}

#[derive(Default)]
pub struct RemoteMcpResult {
    pub status: i32,
    pub headers: HashMap<String, String>,
    pub body: Vec<u8>,
}
