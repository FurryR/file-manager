package io.furryr.file.hub

import android.util.Log
import io.furryr.file.daemon.DaemonManager
import io.furryr.file.daemon.DaemonTarget
import io.furryr.file.agent.AgentEnvironment
import io.furryr.file.agent.AgentEvents
import io.furryr.file.agent.copyFile
import io.furryr.file.agent.glob
import io.furryr.file.agent.listDirectory
import io.furryr.file.agent.readFile
import io.furryr.file.agent.writeFile
import io.furryr.file.agent.AgentMcp
import io.furryr.file.agent.AgentService
import io.furryr.file.agent.AgentSession
import io.furryr.file.daemon.ipc.*
import io.furryr.file.proto.agent.AgentAttachResponse
import io.furryr.file.daemon.HubActions
import io.furryr.file.daemon.HubCallerRoot
import io.furryr.file.proto.hub.ActiveSessionResponse
import io.furryr.file.proto.hub.AiConfigResponse
import io.furryr.file.proto.hub.CallerRoot
import io.furryr.file.proto.hub.CapabilitiesResponse
import io.furryr.file.proto.hub.ExecResponse
import io.furryr.file.proto.hub.HistoryResponse
import io.furryr.file.proto.hub.HubRequest
import io.furryr.file.proto.hub.HubResponse
import io.furryr.file.proto.hub.ListTargetsResponse
import io.furryr.file.proto.hub.ModelsResponse
import io.furryr.file.proto.hub.SessionInfo
import io.furryr.file.proto.hub.SessionsResponse
import io.furryr.file.proto.hub.Target
import io.furryr.file.proto.hub.WaitResponse
import io.furryr.file.uds.Transport
import io.furryr.file.uds.TransportEvent
import io.furryr.file.uds.UdsEndpoint
import io.furryr.file.uds.UdsServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.Closeable
import java.io.FileDescriptor
import java.util.concurrent.ConcurrentHashMap

/**
 * The communication hub: a local UDS server.
 *
 * The socket address is open; a connection proves which daemon spawned its
 * caller by presenting that daemon's key in Hello. Every operation requires
 * authentication first. The hub translates each operation onto the selected
 * daemon — spawning PTYs, forwarding signals, reaping children — and never
 * exposes the daemon's own file protocol. Each connection owns its PTY handles
 * and keeps its own authenticated caller root.
 */
class HubServer internal constructor(private val server: UdsServer) : Closeable {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        scope.launch {
            server.connections.collect { client ->
                scope.launch { serve(client) }
            }
        }
    }

    private suspend fun serve(client: Transport) = coroutineScope {
        val state = ConnectionState()
        try {
            client.events.takeWhile { it !is TransportEvent.Closed }.collect { event ->
                when (event) {
                    is TransportEvent.Incoming -> {
                        try {
                            val request = try {
                                HubRequest.parseFrom(event.frame.payload)
                            } catch (_: Exception) {
                                null
                            }
                            val failure = when {
                                request == null -> failure(0, "malformed hub request")
                                event.frame.descriptorsTruncated -> failure(request.id,
                                    "hub descriptor transfer was truncated (MSG_CTRUNC); received=${event.frame.fds.size}")
                                event.frame.fds.isNotEmpty() -> failure(request.id, "unexpected hub descriptors")
                                request.opCase != HubRequest.OpCase.HELLO && !state.authenticated ->
                                    failure(request.id, "authentication required")
                                request.opCase == HubRequest.OpCase.HELLO && state.authenticated ->
                                    failure(request.id, "caller is already authenticated")
                                else -> null
                            }
                            if (failure != null) {
                                send(client, Reply(failure))
                            } else if (request!!.opCase == HubRequest.OpCase.SUBSCRIBE_SESSION_EVENTS) {
                                val id = request.id
                                val events = AgentEvents.subscribe()
                                try {
                                    send(client, Reply(ok(id)))
                                } catch (e: Exception) {
                                    AgentEvents.unsubscribe(events)
                                    throw e
                                }
                                launch(start = CoroutineStart.UNDISPATCHED) {
                                    try {
                                        for (event in events) {
                                            send(
                                                client,
                                                Reply(
                                                    HubResponse.newBuilder().setId(id).setOk(true)
                                                        .setSessionTitle(event).build(),
                                                ),
                                            )
                                        }
                                    } finally {
                                        AgentEvents.unsubscribe(events)
                                    }
                                }
                            } else if (request!!.opCase == HubRequest.OpCase.AGENT_COMPACT) {
                                val id = request.id
                                val session = state.agent
                                if (session == null) {
                                    send(client, Reply(failure(id, "agent is not attached")))
                                } else {
                                    session.setEmitter { event ->
                                        send(
                                            client,
                                            Reply(
                                                HubResponse.newBuilder().setId(id).setOk(true)
                                                    .setAgentEvent(event).build(),
                                            ),
                                        )
                                    }
                                    val job = launch {
                                        try {
                                            session.compact(
                                                request.agentCompact.model,
                                                request.agentCompact.effort,
                                            )
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            runCatching {
                                                send(client, Reply(failure(id, e.message ?: "compact failed")))
                                            }
                                        } finally {
                                            state.agentJobs.remove(id)
                                        }
                                    }
                                    state.agentJobs[id] = job
                                }
                            } else if (request!!.opCase == HubRequest.OpCase.AGENT_PROMPT) {
                                val id = request.id
                                val session = state.agent
                                if (session == null) {
                                    send(client, Reply(failure(id, "agent is not attached")))
                                } else {
                                    if (request.agentPrompt.model.isNotBlank()) {
                                        session.model = request.agentPrompt.model
                                    }
                                    if (request.agentPrompt.effort.isNotBlank()) {
                                        session.effort = request.agentPrompt.effort
                                    }
                                    session.setEmitter { event ->
                                        send(
                                            client,
                                            Reply(
                                                HubResponse.newBuilder().setId(id).setOk(true)
                                                    .setAgentEvent(event).build(),
                                            ),
                                        )
                                    }
                                    val job = launch {
                                        try {
                                            session.startPrompt(request.agentPrompt.text).join()
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            runCatching {
                                                send(client, Reply(failure(id, e.message ?: "agent failed")))
                                            }
                                        } finally {
                                            state.agentJobs.remove(id)
                                        }
                                    }
                                    state.agentJobs[id] = job
                                }
                            } else if (request!!.opCase == HubRequest.OpCase.REMOTE_MCP_CALL) {
                                val id = request.id
                                val call = launch {
                                    try {
                                        AgentMcp.request(request.remoteMcpCall, state.target) { event ->
                                            send(
                                                client,
                                                Reply(
                                                    HubResponse.newBuilder().setId(id).setOk(true)
                                                        .setRemoteMcpCall(event).build(),
                                                ),
                                            )
                                        }
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        runCatching {
                                            send(client, Reply(failure(id, e.message ?: "MCP request failed")))
                                        }
                                    } finally {
                                        state.httpCalls.remove(id)
                                    }
                                }
                                state.httpCalls[id] = call
                            } else if (request!!.opCase == HubRequest.OpCase.WAIT_EXEC) {
                                val handle = request.waitExec.execId
                                val exec = state.execs[handle]
                                if (exec == null) {
                                    send(client, Reply(failure(request.id, "exec not owned by client")))
                                } else {
                                    launch {
                                        val reply = try {
                                            val code = runInterruptible {
                                                exec.connection.waitExec(exec.execId, EXEC_WAIT_TIMEOUT_MS).getOrThrow()
                                            }
                                            state.execs.remove(handle, exec)
                                            Reply(
                                                HubResponse.newBuilder().setId(request.id).setOk(true)
                                                    .setWait(WaitResponse.newBuilder().setExitCode(code)).build(),
                                            )
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            state.execs.remove(handle, exec)
                                            runCatching { exec.connection.closeExec(exec.execId) }
                                            Reply(failure(request.id, e.message ?: "hub request failed"))
                                        }
                                        try {
                                            send(client, reply)
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            Log.w(TAG, "hub exec wait response delivery failed", e)
                                            client.close()
                                        }
                                    }
                                }
                            } else if (request!!.opCase == HubRequest.OpCase.WAIT) {
                                // Capture ownership in request order, before a later CLOSE.
                                val handle = request.wait.ptyId
                                val pty = state.ptys[handle]
                                if (pty == null) {
                                    send(client, Reply(failure(request.id, "PTY not owned by client")))
                                } else {
                                    launch {
                                        val reply = try {
                                            val code = runInterruptible {
                                                pty.connection.waitPty(pty.id).getOrThrow()
                                            }
                                            state.ptys.remove(handle, pty)
                                            pty.terminalTarget?.close()
                                            Reply(
                                                HubResponse.newBuilder().setId(request.id).setOk(true)
                                                    .setWait(WaitResponse.newBuilder().setExitCode(code)).build(),
                                            )
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            Reply(failure(request.id, e.message ?: "hub request failed"))
                                        }
                                        try {
                                            send(client, reply)
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            Log.w(TAG, "hub wait response delivery failed", e)
                                            client.close()
                                        }
                                    }
                                }
                            } else {
                                send(client, dispatch(request!!, state, client))
                            }
                        } finally {
                            event.frame.close()
                        }
                    }
                    else -> {}
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "hub client ended", e)
        } finally {
            coroutineContext.cancelChildren()
            runCatching { client.close() }
            state.ptys.values.forEach { pty ->
                pty.terminalTarget?.close()
                pty.connection.closePty(pty.id).onFailure {
                    Log.w(TAG, "hub PTY cleanup failed", it)
                }
            }
            state.ptys.clear()
            state.execs.values.forEach { exec ->
                exec.connection.closeExec(exec.execId).onFailure {
                    Log.w(TAG, "hub exec cleanup failed", it)
                }
            }
            state.execs.clear()
            state.httpCalls.values.forEach { it.cancel() }
            state.httpCalls.clear()
            state.agentJobs.values.forEach { it.cancel() }
            state.agentJobs.clear()
            state.agent?.dispose()
            state.agent = null
        }
    }

    private suspend fun send(client: Transport, reply: Reply) {
        try {
            client.send(reply.response.toByteArray(), reply.fds)
        } finally {
            // SCM_RIGHTS duplicates each descriptor; drop our copies even on send failure.
            reply.fds.forEach { runCatching { android.system.Os.close(it) } }
        }
    }

    private suspend fun dispatch(request: HubRequest, state: ConnectionState, client: Transport): Reply {
        val id = request.id
        return runCatching {
            when (request.opCase) {
                HubRequest.OpCase.AGENT_ATTACH -> {
                    val attach = request.agentAttach
                    state.agentJobs.values.forEach { it.cancel() }
                    state.agentJobs.clear()
                    state.agent?.dispose()
                    val session = AgentSession(
                        sessionId = attach.sessionId.ifBlank { "agent" },
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
                                    .build(),
                            ).build(),
                    )
                }

                HubRequest.OpCase.AGENT_CANCEL -> {
                    state.agent?.cancel()
                    state.agentJobs.values.forEach { it.cancel() }
                    state.agentJobs.clear()
                    state.agent?.cancelAndWait()
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

                HubRequest.OpCase.HELLO -> {
                    require(request.hello.key.isNotBlank()) { "missing daemon key" }
                    val target = requireNotNull(DaemonManager.targetForKey(request.hello.key)) {
                        "unknown daemon key"
                    }
                    val containerId = DaemonManager.hubId(target)
                    val root = requireNotNull(HubActions.resolveRoot) {
                        "filesystem resolution is unavailable"
                    }.invoke(containerId)
                    state.authenticated = true
                    state.root = root
                    state.target = target
                    state.terminalId = request.hello.terminalId
                    Reply(
                        HubResponse.newBuilder().setId(id).setOk(true).setCallerRoot(
                            CallerRoot.newBuilder().setContainerId(containerId),
                        ).build(),
                    )
                }

                HubRequest.OpCase.LIST_TARGETS ->
                    Reply(
                        HubResponse.newBuilder().setId(id).setOk(true)
                            .setTargets(listTargets()).build(),
                    )

                HubRequest.OpCase.GET_AI_CONFIG -> {
                    val config = requireNotNull(AgentEnvironment.aiConfigProvider) {
                        "AI configuration is unavailable"
                    }.invoke()
                    Reply(
                        HubResponse.newBuilder().setId(id).setOk(true)
                            .setAiConfig(
                                AiConfigResponse.newBuilder().setBaseUrl(config.baseUrl)
                                    .setApiKey("").setModel(config.model)
                                    .setEffort(config.effort).setProvider(config.provider),
                            ).build(),
                    )
                }

                HubRequest.OpCase.SET_AI_SELECTION -> {
                    val selection = request.setAiSelection
                    requireNotNull(AgentEnvironment.aiSelectionSaver) {
                        "AI selection persistence is unavailable"
                    }.invoke(selection.model, selection.effort)
                    Reply(HubResponse.newBuilder().setId(id).setOk(true).build())
                }

                HubRequest.OpCase.EXEC -> {
                    val exec = request.exec
                    val targetId = exec.containerId.ifBlank { "host" }
                    val target = target(targetId)
                    val root = requireNotNull(state.root) { "hello is required" }
                    val cwd = exec.cwd.ifBlank { null }?.let {
                        requireNotNull(HubActions.resolveCwd) { "filesystem resolution is unavailable" }
                            .invoke(root, targetId, it)
                    }
                    val connection = DaemonManager.connection(target)
                    val env = exec.envMap + Hub.environment(target) +
                        mapOf("HUB_TERMINAL" to state.terminalId)
                    if (exec.pipes) {
                        val stream = connection.spawnExec(
                            command = exec.command,
                            args = exec.argsList,
                            cwd = cwd,
                            env = env,
                        ).getOrThrow()
                        val handle = state.nextHandle++
                        state.execs[handle] = OwnedExec(connection, stream.execId)
                        return@runCatching Reply(
                            HubResponse.newBuilder().setId(id).setOk(true)
                                .setExec(
                                    ExecResponse.newBuilder()
                                        .setExecId(handle).setPid(stream.childPid).build(),
                                ).build(),
                            listOf(stream.stdout, stream.stderr),
                        )
                    }
                    val pty = connection.spawnPty(
                        command = exec.command,
                        args = exec.argsList,
                        env = env,
                        termType = exec.term,
                        rows = exec.rows,
                        cols = exec.cols,
                        cwd = cwd,
                        user = if (exec.hasUser()) exec.user else null,
                        nice = if (exec.hasNice()) exec.nice else null,
                    ).getOrThrow()
                    val handle = state.nextHandle++
                    state.ptys[handle] = OwnedPty(
                        connection, pty.ptyId,
                        HubActions.onTerminalTarget?.invoke(state.terminalId, targetId),
                    )
                    Reply(
                        HubResponse.newBuilder().setId(id).setOk(true)
                            .setExec(
                                ExecResponse.newBuilder()
                                    .setPtyId(handle).setPid(pty.childPid).build(),
                            ).build(),
                        listOfNotNull(pty.fileDescriptor),
                    )
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
                    // Only server names travel to the caller; URLs and
                    // credential headers stay in the app and are resolved by
                    // RemoteMcpCall.
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

                HubRequest.OpCase.RESIZE -> {
                    val r = request.resize
                    val pty = state.pty(r.ptyId)
                    pty.connection.resizePty(pty.id, r.rows, r.cols).getOrThrow()
                    Reply(ok(id))
                }

                HubRequest.OpCase.HERE -> {
                    val path = request.here.path
                    require(path.startsWith('/')) { "here requires an absolute path" }
                    val reveal = HubActions.onHere ?: error("file navigation is unavailable")
                    reveal(path, requireNotNull(state.root) { "hello is required" })
                    Reply(ok(id))
                }

                HubRequest.OpCase.SIGNAL -> {
                    val s = request.signal
                    val pty = state.pty(s.ptyId)
                    pty.connection.signalPty(pty.id, s.signal).getOrThrow()
                    Reply(ok(id))
                }

                HubRequest.OpCase.CLOSE -> {
                    val handle = request.close.ptyId
                    val pty = state.pty(handle)
                    pty.connection.closePty(pty.id).getOrThrow()
                    state.ptys.remove(handle, pty)
                    pty.terminalTarget?.close()
                    Reply(ok(id))
                }

                HubRequest.OpCase.CLOSE_EXEC -> {
                    val handle = request.closeExec.execId
                    val exec = state.execs.remove(handle) ?: error("exec not owned by client")
                    exec.connection.closeExec(exec.execId).getOrThrow()
                    Reply(ok(id))
                }

                else -> Reply(failure(id, "missing hub op"))
            }
        }.getOrElse {
            if (it is CancellationException) throw it
            Reply(failure(id, it.message ?: "hub request failed"))
        }
    }

    private fun listTargets(): ListTargetsResponse {
        val live = DaemonManager.liveEndpoints().associateBy { it.name }
        val names = LinkedHashSet<String>()
        names.add("host")
        names.add("app")
        names.addAll(live.keys)
        runCatching { HubActions.containerIds?.invoke().orEmpty() }
            .onFailure { Log.w(TAG, "container enumeration failed", it) }
            .getOrDefault(emptyList())
            .let(names::addAll)
        val ordered = names.filter { it == "host" || it == "app" } +
            names.filterNot { it == "host" || it == "app" }.sorted()
        val execTargets = ordered.map { name ->
            val endpoint = live[name]
            Target.newBuilder()
                .setName(name)
                .setElevated(endpoint?.elevated == true)
                .setRunning(endpoint != null)
                .setKind("exec")
                .setLabel(name.removePrefix(CONTAINER_PREFIX))
                .setRoot(execRoot(name))
                .build()
        }
        val locations = runCatching { HubActions.locationTargets?.invoke().orEmpty() }
            .onFailure { Log.w(TAG, "location enumeration failed", it) }
            .getOrDefault(emptyList())
        val fileTargets = locations
            .filter { it.root.isNotBlank() }
            .distinctBy { it.root }
            .map { location ->
                Target.newBuilder()
                    .setName(location.root)
                    .setKind(location.kind)
                    .setLabel(location.label)
                    .setRoot(location.root)
                    .setRunning(true)
                    .build()
            }
        return ListTargetsResponse.newBuilder()
            .addAllTargets(execTargets)
            .addAllTargets(fileTargets)
            .build()
    }


    /**
     * VFS root that addresses an execution target's filesystem. `app` has no
     * separate provider, so it exposes no root.
     */
    private fun execRoot(name: String): String = when {
        name == "host" -> "file:///"
        name.startsWith(CONTAINER_PREFIX) -> "container://${name.removePrefix(CONTAINER_PREFIX)}"
        else -> ""
    }

    private fun target(containerId: String): DaemonTarget = when (containerId) {
        "host" -> DaemonTarget.Host
        "app" -> DaemonTarget.App
        else -> {
            require(containerId.startsWith(CONTAINER_PREFIX)) {
                "invalid container id '$containerId'"
            }
            DaemonTarget.Named(containerId.removePrefix(CONTAINER_PREFIX))
        }
    }

    private fun ok(id: Int): HubResponse =
        HubResponse.newBuilder().setId(id).setOk(true).build()

    private fun failure(id: Int, message: String): HubResponse =
        HubResponse.newBuilder().setId(id).setOk(false).setError(message).build()

    override fun close() {
        server.close()
        scope.cancel()
    }

    private class ConnectionState {
        var authenticated: Boolean = false
        var root: HubCallerRoot? = null
        var terminalId: String = ""
        var target: DaemonTarget = DaemonTarget.Host
        var nextHandle: Long = 1
        val ptys = ConcurrentHashMap<Long, OwnedPty>()
        val execs = ConcurrentHashMap<Long, OwnedExec>()
        val httpCalls = ConcurrentHashMap<Int, Job>()
        val agentJobs = ConcurrentHashMap<Int, Job>()
        var agent: AgentSession? = null

        fun pty(handle: Long): OwnedPty =
            ptys[handle] ?: error("PTY not owned by client")
    }

    private class OwnedPty(
        val connection: DaemonConnection,
        val id: Long,
        val terminalTarget: Closeable?,
    )

    private class OwnedExec(
        val connection: DaemonConnection,
        val execId: Long,
    )

    private class Reply(
        val response: HubResponse,
        val fds: List<FileDescriptor> = emptyList(),
    )

    companion object {
        private const val TAG = "HubServer"
        private const val CONTAINER_PREFIX = "container-"

        /**
         * Bound on one pipe-mode exec wait. Only a wedged daemon reaches it; the
         * timeout path also closes the exec so its process group is terminated
         * instead of leaking.
         */
        private const val EXEC_WAIT_TIMEOUT_MS = 15 * 60 * 1000L

        /** Bind [endpoint] and start serving. */
        fun listen(endpoint: UdsEndpoint): HubServer = HubServer(UdsServer.listen(endpoint))
    }
}
