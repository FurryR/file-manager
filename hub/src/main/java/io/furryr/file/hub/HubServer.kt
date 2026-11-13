package io.furryr.file.hub

import android.util.Log
import io.furryr.file.daemon.DaemonManager
import io.furryr.file.daemon.DaemonTarget
import io.furryr.file.agent.AgentEvents
import io.furryr.file.agent.AgentMcp
import io.furryr.file.agent.AgentSession
import io.furryr.file.daemon.process.PipeChannel
import io.furryr.file.daemon.process.ProcessChannel
import io.furryr.file.daemon.HubCallerRoot
import io.furryr.file.proto.hub.HubRequest
import io.furryr.file.proto.hub.HubResponse
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
import kotlinx.coroutines.withTimeoutOrNull
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
 *
 * Request categories live in `HubAgentOps` (agent/history) and `HubExecOps`
 * (handshake/exec/PTY); this class owns the connection lifecycle.
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
                                event.frame.descriptorsTruncated -> failure(
                                    request.id,
                                    "hub descriptor transfer was truncated (MSG_CTRUNC); received=${event.frame.fds.size}",
                                )
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
                                            val code = withTimeoutOrNull(EXEC_WAIT_TIMEOUT_MS) {
                                                exec.channel.waitFor().getOrThrow()
                                            } ?: error("exec wait timed out")
                                            state.execs.remove(handle, exec)
                                            Reply(
                                                HubResponse.newBuilder().setId(request.id).setOk(true)
                                                    .setWait(WaitResponse.newBuilder().setExitCode(code)).build(),
                                            )
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            state.execs.remove(handle, exec)
                                            runCatching { exec.channel.closeProcess() }
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
                                            val code = pty.channel.waitFor().getOrThrow()
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
                                // A failed request must not cost the client its
                                // connection: dispatch reports errors by throwing.
                                val reply = try {
                                    dispatch(request!!, state, client)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    Reply(failure(request!!.id, e.message ?: "hub request failed"))
                                }
                                try {
                                    send(client, reply)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    Log.w(TAG, "hub response delivery failed", e)
                                    client.close()
                                }
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
                runCatching { pty.channel.close() }.onFailure {
                    Log.w(TAG, "hub PTY cleanup failed", it)
                }
            }
            state.ptys.clear()
            state.execs.values.forEach { exec ->
                runCatching { exec.channel.closeProcess() }.onFailure {
                    Log.w(TAG, "hub exec cleanup failed", it)
                }
                exec.channel.close()
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

    internal suspend fun send(client: Transport, reply: Reply) {
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
            dispatchAgentOps(request, state, client)
                ?: dispatchExecOps(request, state, client)
                ?: Reply(failure(id, "missing hub op"))
        }.getOrElse {
            if (it is CancellationException) throw it
            Reply(failure(id, it.message ?: "hub request failed"))
        }
    }

    internal fun ok(id: Int): HubResponse =
        HubResponse.newBuilder().setId(id).setOk(true).build()

    internal fun failure(id: Int, message: String): HubResponse =
        HubResponse.newBuilder().setId(id).setOk(false).setError(message).build()

    override fun close() {
        server.close()
        scope.cancel()
    }

    internal class ConnectionState {
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

    internal class OwnedPty(
        val channel: ProcessChannel,
        val terminalTarget: Closeable?,
    )

    internal class OwnedExec(
        val channel: PipeChannel,
    )

    internal class Reply(
        val response: HubResponse,
        val fds: List<FileDescriptor> = emptyList(),
    )

    companion object {
        private const val TAG = "HubServer"

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
