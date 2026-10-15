package io.furryr.file.core.hub

import android.system.Os
import android.util.Log
import io.furryr.file.core.DaemonManager
import io.furryr.file.core.DaemonTarget
import io.furryr.file.proto.hub.ExecResponse
import io.furryr.file.proto.hub.HubRequest
import io.furryr.file.proto.hub.HubResponse
import io.furryr.file.proto.hub.ListTargetsResponse
import io.furryr.file.proto.hub.Target
import io.furryr.file.proto.hub.WaitResponse
import io.furryr.file.uds.Frame
import io.furryr.file.uds.Transport
import io.furryr.file.uds.TransportEvent
import io.furryr.file.uds.UdsEndpoint
import io.furryr.file.uds.UdsServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.Closeable
import java.io.FileDescriptor

/**
 * The communication hub: a stable, unauthenticated UDS server that run0 (and,
 * later, agents) attach to.
 *
 * Unlike a transparent relay, the hub speaks its **own** protocol
 * ([io.furryr.file.proto.hub.HubRequest]) and translates each operation onto the
 * daemon the connection selected — spawning PTYs, forwarding signals, reaping
 * children. The daemon's own file protocol is never exposed here, and the hub
 * holds the app identity, so it authenticates to the daemon on the client's
 * behalf. Each connection keeps its own selected target; PTYs are daemon-global.
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

    private suspend fun serve(client: Transport) {
        val state = ConnectionState()
        try {
            client.events.collect { event ->
                when (event) {
                    is TransportEvent.Incoming -> {
                        val reply = dispatch(event.frame, state)
                        event.frame.close()
                        try {
                            client.send(reply.response.toByteArray(), listOfNotNull(reply.fd))
                        } finally {
                            // The descriptor is duplicated into the receiver by
                            // the kernel; drop our copy.
                            reply.fd?.let { runCatching { Os.close(it) } }
                        }
                    }
                    is TransportEvent.Closed -> return@collect
                    else -> {}
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "hub client ended", e)
        } finally {
            runCatching { client.close() }
        }
    }

    private suspend fun dispatch(frame: Frame, state: ConnectionState): Reply {
        val request = try {
            HubRequest.parseFrom(frame.payload)
        } catch (_: Exception) {
            return Reply(failure(0, "malformed hub request"))
        }
        val id = request.id
        return runCatching {
            when (request.opCase) {
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

                HubRequest.OpCase.EXEC -> {
                    val exec = request.exec
                    val pty = DaemonManager.connection(target(state.target)).spawnPty(
                        command = exec.command,
                        args = exec.argsList,
                        env = exec.envMap,
                        termType = exec.term,
                        rows = exec.rows,
                        cols = exec.cols,
                        cwd = exec.cwd.ifBlank { null },
                        user = if (exec.hasUser()) exec.user else null,
                        nice = if (exec.hasNice()) exec.nice else null,
                    ).getOrThrow()
                    Reply(
                        HubResponse.newBuilder().setId(id).setOk(true)
                            .setExec(
                                ExecResponse.newBuilder()
                                    .setPtyId(pty.ptyId).setPid(pty.childPid).build(),
                            ).build(),
                        pty.fileDescriptor,
                    )
                }

                HubRequest.OpCase.RESIZE -> {
                    val r = request.resize
                    DaemonManager.connection(target(state.target))
                        .resizePty(r.ptyId, r.rows, r.cols).getOrThrow()
                    Reply(ok(id))
                }

                HubRequest.OpCase.SIGNAL -> {
                    val s = request.signal
                    DaemonManager.connection(target(state.target))
                        .signalPty(s.ptyId, s.signal).getOrThrow()
                    Reply(ok(id))
                }

                HubRequest.OpCase.WAIT -> {
                    val code = DaemonManager.connection(target(state.target))
                        .waitPty(request.wait.ptyId).getOrThrow()
                    Reply(
                        HubResponse.newBuilder().setId(id).setOk(true)
                            .setWait(WaitResponse.newBuilder().setExitCode(code)).build(),
                    )
                }

                HubRequest.OpCase.CLOSE -> {
                    DaemonManager.connection(target(state.target))
                        .closePty(request.close.ptyId).getOrThrow()
                    Reply(ok(id))
                }

                else -> Reply(failure(id, "missing hub op"))
            }
        }.getOrElse { Reply(failure(id, it.message ?: "hub request failed")) }
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
        var target: String = "primary"
    }

    private class Reply(val response: HubResponse, val fd: FileDescriptor? = null)

    companion object {
        private const val TAG = "HubServer"

        /** Bind [endpoint] and start serving. */
        fun listen(endpoint: UdsEndpoint): HubServer = HubServer(UdsServer.listen(endpoint))
    }
}
