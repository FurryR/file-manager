package io.furryr.file.core.hub

import android.util.Log
import io.furryr.file.core.DaemonManager
import io.furryr.file.core.DaemonTarget
import io.furryr.file.core.HubActions
import io.furryr.file.core.HubCallerRoot
import io.furryr.file.core.ipc.DaemonConnection
import io.furryr.file.proto.hub.AiConfigResponse
import io.furryr.file.proto.hub.CallerRoot
import io.furryr.file.proto.hub.ExecResponse
import io.furryr.file.proto.hub.HubRequest
import io.furryr.file.proto.hub.HubResponse
import io.furryr.file.proto.hub.ListTargetsResponse
import io.furryr.file.proto.hub.Target
import io.furryr.file.proto.hub.WaitResponse
import io.furryr.file.uds.Transport
import io.furryr.file.uds.TransportEvent
import io.furryr.file.uds.UdsEndpoint
import io.furryr.file.uds.UdsServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
                                send(client, dispatch(request!!, state))
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
        }
    }

    private suspend fun send(client: Transport, reply: Reply) {
        try {
            client.send(reply.response.toByteArray(), listOfNotNull(reply.fd))
        } finally {
            // SCM_RIGHTS duplicates the descriptor; drop our copy even on send failure.
            reply.fd?.let { runCatching { android.system.Os.close(it) } }
        }
    }

    private fun dispatch(request: HubRequest, state: ConnectionState): Reply {
        val id = request.id
        return runCatching {
            when (request.opCase) {
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
                            .setTargets(
                                ListTargetsResponse.newBuilder().addAllTargets(
                                    DaemonManager.liveEndpoints().map {
                                        Target.newBuilder()
                                            .setName(it.name).setElevated(it.elevated).build()
                                    },
                                ),
                            ).build(),
                    )

                HubRequest.OpCase.GET_AI_CONFIG -> {
                    val config = requireNotNull(HubActions.aiConfigProvider) {
                        "AI configuration is unavailable"
                    }.invoke()
                    Reply(
                        HubResponse.newBuilder().setId(id).setOk(true)
                            .setAiConfig(
                                AiConfigResponse.newBuilder().setBaseUrl(config.baseUrl)
                                    .setApiKey(config.apiKey).setModel(config.model),
                            ).build(),
                    )
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
                    val pty = connection.spawnPty(
                        command = exec.command,
                        args = exec.argsList,
                        env = exec.envMap + Hub.environment(target) +
                            mapOf("HUB_TERMINAL" to state.terminalId),
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
                        pty.fileDescriptor,
                    )
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

                else -> Reply(failure(id, "missing hub op"))
            }
        }.getOrElse {
            if (it is CancellationException) throw it
            Reply(failure(id, it.message ?: "hub request failed"))
        }
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

        fun pty(handle: Long): OwnedPty =
            ptys[handle] ?: error("PTY not owned by client")
    }

    private class OwnedPty(
        val connection: DaemonConnection,
        val id: Long,
        val terminalTarget: Closeable?,
    )

    private class Reply(val response: HubResponse, val fd: FileDescriptor? = null)

    companion object {
        private const val TAG = "HubServer"
        private const val CONTAINER_PREFIX = "container-"

        /** Bind [endpoint] and start serving. */
        fun listen(endpoint: UdsEndpoint): HubServer = HubServer(UdsServer.listen(endpoint))
    }
}
