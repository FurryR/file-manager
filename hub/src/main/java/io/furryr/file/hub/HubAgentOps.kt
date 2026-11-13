package io.furryr.file.hub

import io.furryr.file.agent.AgentEnvironment
import io.furryr.file.agent.AgentMcp
import io.furryr.file.agent.AgentService
import io.furryr.file.agent.AgentSession
import io.furryr.file.daemon.DaemonManager
import io.furryr.file.proto.hub.ActiveSessionResponse
import io.furryr.file.proto.agent.AgentAttachResponse
import io.furryr.file.proto.agent.AgentEvent
import io.furryr.file.proto.hub.AiConfigResponse
import io.furryr.file.proto.hub.CallerRoot
import io.furryr.file.proto.hub.CapabilitiesResponse
import io.furryr.file.proto.hub.HistoryResponse
import io.furryr.file.proto.hub.HubRequest
import io.furryr.file.proto.hub.HubResponse
import io.furryr.file.proto.hub.ModelsResponse
import io.furryr.file.proto.hub.SessionInfo
import io.furryr.file.proto.hub.SessionsResponse
import io.furryr.file.uds.Transport
import io.furryr.file.hub.HubServer.ConnectionState
import io.furryr.file.hub.HubServer.Reply

/** Agent, history and handshake requests. Returns null for another category. */
internal suspend fun HubServer.dispatchAgentOps(
    request: HubRequest,
    state: ConnectionState,
    client: Transport,
): Reply? {
    val id = request.id
    return when (request.opCase) {
        HubRequest.OpCase.AGENT_ATTACH -> {
            val attach = request.agentAttach
            state.agentJobs.values.forEach { it.cancel() }
            state.agentJobs.clear()
            state.agent?.dispose()
            val sessionId = attach.sessionId.ifBlank { "agent" }
            val session = AgentSession(
                sessionId = sessionId,
                terminalId = state.terminalId.ifBlank { attach.terminalId },
                target = state.target,
                targetName = DaemonManager.hubId(state.target),
                cwd = attach.cwd.ifBlank { "/" },
                model = attach.model,
                effort = attach.effort,
                emit = { event ->
                    send(
                        client,
                        Reply(
                            HubResponse.newBuilder().setId(request.id).setOk(true)
                                .setAgentEvent(event).build(),
                        ),
                    )
                },
            )
            state.agent = session
            Reply(
                HubResponse.newBuilder().setId(id).setOk(true)
                    .setAgentAttach(
                        AgentAttachResponse.newBuilder()
                            .setSessionId(session.sessionId)
                            .setTarget(session.targetName)
                            .setCwd(session.cwd)
                            .setContextTokens(session.usage().contextTokens)
                            .setPromptTokens(session.usage().promptTokens)
                            .setCompletionTokens(session.usage().completionTokens)
                            .setRequests(session.usage().requests)
                            .build(),
                    ).build(),
            )
        }

        HubRequest.OpCase.AGENT_STEER -> {
            val agent = state.agent ?: error("agent is not attached")
            if (!agent.steer(request.agentSteer.text)) {
                error("the prompt is empty")
            }
            Reply(ok(id))
        }

        HubRequest.OpCase.AGENT_CANCEL -> {
            // Reply as soon as the kill is issued: the session closes its exec
            // handles on a detached scope and cancels its own job.
            val streams = state.agentJobs.keys.toList()
            state.agent?.cancel()
            state.agentJobs.values.forEach { it.cancel() }
            state.agentJobs.clear()
            for (streamId in streams) {
                runCatching {
                    send(
                        client,
                        Reply(
                            HubResponse.newBuilder().setId(streamId).setOk(true)
                                .setAgentEvent(AgentEvent.newBuilder().setDone(true).build())
                                .build(),
                        ),
                    )
                }
            }
            Reply(ok(id))
        }

        HubRequest.OpCase.AGENT_APPROVAL -> {
            state.agent?.resolveApproval(
                request.agentApproval.approvalId,
                request.agentApproval.allow,
            )
            Reply(ok(id))
        }

        HubRequest.OpCase.AGENT_MCP_RESULT -> {
            state.agent?.completeMcp(
                request.agentMcpResult.requestId,
                request.agentMcpResult.ok,
                request.agentMcpResult.content,
            )
            Reply(ok(id))
        }

        HubRequest.OpCase.AGENT_MCP_TOOLS -> {
            state.agent?.setMcpTools(request.agentMcpTools.definitionsJson)
            Reply(ok(id))
        }

        HubRequest.OpCase.CANCEL_REMOTE_MCP_CALL -> {
            state.httpCalls.remove(request.cancelRemoteMcpCall.requestId)?.cancel()
            Reply(ok(id))
        }

        HubRequest.OpCase.GET_HISTORY -> {
            val sessionId = request.getHistory.sessionId
            Reply(
                HubResponse.newBuilder().setId(id).setOk(true)
                    .setHistory(
                        HistoryResponse.newBuilder()
                            .setMessagesJson(
                                AgentEnvironment.sessionLoad?.invoke(sessionId).orEmpty(),
                            )
                            .setTitle(AgentEnvironment.sessionTitle?.invoke(sessionId).orEmpty())
                            .build(),
                    ).build(),
            )
        }

        HubRequest.OpCase.SET_HISTORY -> {
            AgentEnvironment.sessionSave?.invoke(
                request.setHistory.sessionId,
                request.setHistory.messagesJson,
            )
            Reply(ok(id))
        }

        HubRequest.OpCase.GET_MODELS -> Reply(
            HubResponse.newBuilder().setId(id).setOk(true)
                .setModels(ModelsResponse.newBuilder().setCatalogJson(AgentService.catalog()).build())
                .build(),
        )

        HubRequest.OpCase.GET_CAPABILITIES -> {
            val servers = AgentMcp.servers(state.target, request.getCapabilities.cwd)
                .filter { it.enabled && it.url.isNotBlank() }
            Reply(
                HubResponse.newBuilder().setId(id).setOk(true)
                    .setCapabilities(
                        CapabilitiesResponse.newBuilder()
                            .addAllRemoteMcpServers(servers.map { it.name })
                            .setWorkspaceMcp(AgentEnvironment.workspaceMcpEnabled?.invoke() == true)
                            .build(),
                    ).build(),
            )
        }

        HubRequest.OpCase.LIST_SESSIONS -> Reply(
            HubResponse.newBuilder().setId(id).setOk(true)
                .setSessions(
                    SessionsResponse.newBuilder().addAllSessions(
                        AgentEnvironment.sessionList?.invoke().orEmpty().map { session ->
                            SessionInfo.newBuilder()
                                .setId(session.id)
                                .setTitle(session.title)
                                .setCreatedAt(session.createdAt)
                                .setUpdatedAt(session.updatedAt)
                                .setMessageCount(session.messageCount)
                                .build()
                        },
                    ).build(),
                ).build(),
        )

        HubRequest.OpCase.GET_ACTIVE_SESSION -> Reply(
            HubResponse.newBuilder().setId(id).setOk(true)
                .setActiveSession(
                    ActiveSessionResponse.newBuilder()
                        .setSessionId(AgentEnvironment.activeSession?.invoke(state.terminalId).orEmpty())
                        .build(),
                ).build(),
        )

        HubRequest.OpCase.SET_ACTIVE_SESSION -> {
            AgentEnvironment.activeSessionSave?.invoke(
                state.terminalId,
                request.setActiveSession.sessionId,
            )
            Reply(ok(id))
        }

        HubRequest.OpCase.DELETE_SESSION -> {
            AgentEnvironment.sessionDelete?.invoke(request.deleteSession.sessionId)
            Reply(ok(id))
        }

        else -> null
    }
}
