package io.furryr.file.core.hub

import android.system.Os
import android.util.Log
import io.furryr.file.core.DaemonManager
import io.furryr.file.core.DaemonTarget
import io.furryr.file.proto.hub.AiConfigResponse
import io.furryr.file.core.HubActions
import io.furryr.file.core.HubCallerRoot
import io.furryr.file.proto.hub.CallerRoot
import io.furryr.file.core.ipc.DaemonConnection
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
 * The communication hub: an unauthenticated UDS server for local clients.
 *
 * Unlike a transparent relay, the hub speaks its **own** protocol
 * ([io.furryr.file.proto.hub.HubRequest]) and translates each operation onto the
 * daemon the connection selected — spawning PTYs, forwarding signals, reaping
 * children. The daemon's own file protocol is never exposed here, and the hub
 * holds the app identity, so it authenticates to the daemon on the client's
 * behalf. Each connection owns its PTY handles and keeps its own selected target.
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
                            if (request == null) {
                                send(client, Reply(failure(0, "malformed hub request")))
                            } else if (request.opCase != HubRequest.OpCase.EXEC &&
                                request.opCase != HubRequest.OpCase.HERE &&
                                request.opCase != HubRequest.OpCase.HELLO && event.frame.fds.isNotEmpty()) {
                                send(client, Reply(failure(request.id, "unexpected hub descriptors")))
                            } else if (request.opCase == HubRequest.OpCase.WAIT) {
                                // Capture ownership in request order, before later CLOSE/SELECT_TARGET.
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
                                send(client, dispatch(request, state, event.frame.fds))
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
            reply.fd?.let { runCatching { Os.close(it) } }
        }
    }

    private fun dispatch(request: HubRequest, state: ConnectionState, fds: List<FileDescriptor>): Reply {
        val id = request.id
        return runCatching {
            when (request.opCase) {
                HubRequest.OpCase.SERVICE_CONTROL -> {
                    require(fds.isEmpty()) { "service control does not take descriptors" }
                    val name = if (state.target !in listOf("primary", "app")) state.target
                        else requireNotNull(state.root?.containerName) { "systemctl requires a container or --machine" }
                    val operation = request.serviceControl
                    val result = DaemonManager.connection(DaemonTarget.Named(name)).request(
                        io.furryr.file.proto.Request.newBuilder().setServiceControl(
                            io.furryr.file.proto.ServiceControlRequest.newBuilder().setAction(operation.action)
                                .addAllUnits(operation.unitsList).setContent(operation.content),
                        ).build(),
                    ).response.serviceControl
                    Reply(HubResponse.newBuilder().setId(id).setOk(true).setServiceControl(
                        io.furryr.file.proto.hub.ServiceControlResponse.newBuilder().setText(result.text).setExitCode(result.exitCode)
                            .addAllServices(result.servicesList.map { service ->
                                io.furryr.file.proto.hub.ServiceStatus.newBuilder().setName(service.name).setState(service.state)
                                    .setEnabled(service.enabled).setPid(service.pid).setExitCode(service.exitCode)
                                    .setDetail(service.detail).setDescription(service.description).build()
                            }),
                    ).build())
                }
                HubRequest.OpCase.HELLO -> {
                    require(state.root == null) { "caller root is already registered" }
                    require(fds.size == 1) { "hello requires exactly one root descriptor" }
                    val root = requireNotNull(HubActions.resolveRoot) {
                        "filesystem resolution is unavailable"
                    }.invoke(fds.single())
                    state.root = root
                    state.terminalId = request.hello.terminalId
                    Reply(
                        HubResponse.newBuilder().setId(id).setOk(true).setCallerRoot(
                            CallerRoot.newBuilder().setContainerName(root.containerName.orEmpty())
                                .setHostPath(root.hostPath),
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

                HubRequest.OpCase.SELECT_TARGET -> {
                    state.target = request.selectTarget.target.ifBlank { "primary" }
                    Reply(ok(id))
                }

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

                HubRequest.OpCase.OPEN_GRAPHICS -> {
                    val socket = requireNotNull(HubActions.graphicsConnector) { "VirGL renderer is not running" }.invoke()
                    Reply(HubResponse.newBuilder().setId(id).setOk(true)
                        .setGraphicsConnection(io.furryr.file.proto.hub.GraphicsConnectionResponse.getDefaultInstance()).build(), socket)
                }

                HubRequest.OpCase.EXEC -> {
                    val exec = request.exec
                    require(fds.size == if (exec.cwdFd) 1 else 0) { "invalid cwd descriptor count" }
                    require(!exec.cwdFd || exec.cwd.isEmpty()) { "cwd path and descriptor are mutually exclusive" }
                    val cwd = if (exec.cwdFd) {
                        requireNotNull(HubActions.resolveCwd) { "filesystem resolution is unavailable" }
                            .invoke(fds.single(), requireNotNull(state.root) { "hello is required" }, state.target)
                    } else exec.cwd.ifBlank { null }
                    val connection = DaemonManager.connection(target(state.target))
                    val pty = connection.spawnPty(
                        command = exec.command,
                        args = exec.argsList,
                        env = exec.envMap + Hub.environment() +
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
                        HubActions.onTerminalTarget?.invoke(state.terminalId, state.target),
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
                    require(fds.size == 1) { "here requires exactly one file descriptor" }
                    val fd = fds.single()
                    require(Os.fstat(fd).st_nlink > 0) { "the selected entry has been deleted" }
                    val reveal = HubActions.onHere ?: error("file navigation is unavailable")
                    reveal(fd, requireNotNull(state.root) { "hello is required" })
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

    private fun target(name: String): DaemonTarget = when (name) {
        "primary" -> DaemonTarget.Primary
        "app" -> DaemonTarget.App
        else -> DaemonTarget.Named(name)
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
        var root: HubCallerRoot? = null
        var terminalId: String = ""
        var target: String = "primary"
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

        /** Bind [endpoint] and start serving. */
        fun listen(endpoint: UdsEndpoint): HubServer = HubServer(UdsServer.listen(endpoint))
    }
}
