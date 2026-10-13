package io.furryr.file.core.hub

import android.util.Log
import io.furryr.file.core.DaemonEndpoint
import io.furryr.file.proto.hub.HubEndpoint
import io.furryr.file.proto.hub.HubRequest
import io.furryr.file.proto.hub.HubResponse
import io.furryr.file.uds.Frame
import io.furryr.file.uds.Transport
import io.furryr.file.uds.TransportEvent
import io.furryr.file.uds.UdsClient
import io.furryr.file.uds.UdsEndpoint
import io.furryr.file.uds.UdsServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.Closeable

/**
 * The run0-style communication hub: a stable UDS server that external clients
 * attach to. It enumerates live daemon endpoints and, on [HubRequest.Attach],
 * becomes a transparent frame relay to the chosen daemon — parsing only its own
 * attach handshake, never the daemon protocol. Descriptors are forwarded as-is.
 *
 * A relay opens a fresh kernel connection to the daemon (the daemon is
 * multi-client and its state is daemon-global), so the hub stays stateless
 * w.r.t. handles, PTYs and cancellation.
 */
class HubServer internal constructor(
    private val server: UdsServer,
    private val resolver: (String) -> DaemonEndpoint?,
    private val endpointsProvider: () -> List<DaemonEndpoint>,
) : Closeable {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        scope.launch {
            server.connections.collect { client ->
                scope.launch { serve(client) }
            }
        }
    }

    private suspend fun serve(client: Transport) {
        var daemon: Transport? = null
        try {
            client.events.collect { event ->
                when (event) {
                    is TransportEvent.Incoming -> {
                        val target = daemon
                        if (target == null) {
                            val attached = handleControl(client, event.frame)
                            if (attached != null) {
                                daemon = attached
                                pumpDaemon(attached, client)
                            }
                        } else {
                            forward(event.frame, target)
                        }
                    }
                    is TransportEvent.Closed -> {
                        runCatching { daemon?.close() }
                        return@collect
                    }
                    else -> {}
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "hub client ended", e)
        } finally {
            runCatching { daemon?.close() }
            runCatching { client.close() }
        }
    }

    private fun pumpDaemon(daemon: Transport, client: Transport) {
        scope.launch {
            try {
                daemon.events.collect { event ->
                    when (event) {
                        is TransportEvent.Incoming -> forward(event.frame, client)
                        is TransportEvent.Closed -> client.close()
                        else -> {}
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "hub daemon pump ended", e)
            } finally {
                runCatching { daemon.close() }
            }
        }
    }

    private suspend fun handleControl(client: Transport, frame: Frame): Transport? {
        val request = try {
            HubRequest.parseFrom(frame.payload)
        } catch (_: Exception) {
            frame.close()
            client.close()
            return null
        }
        frame.close() // control frames carry no descriptors
        val id = request.id
        return when (request.commandCase) {
            HubRequest.CommandCase.LIST_ENDPOINTS -> {
                val response = HubResponse.newBuilder()
                    .setId(id).setOk(true)
                    .addAllEndpoints(endpointsProvider().map {
                        HubEndpoint.newBuilder()
                            .setName(it.name).setSocket(it.socketName).setElevated(it.elevated)
                            .build()
                    })
                    .build()
                client.send(response.toByteArray())
                null
            }
            HubRequest.CommandCase.ATTACH -> {
                val target = resolver(request.attach.name)
                if (target == null) {
                    client.send(failure(id, "unknown endpoint '${request.attach.name}'"))
                    return null
                }
                val daemon = try {
                    UdsClient.connect(UdsEndpoint.Abstract(target.socketName))
                } catch (e: Exception) {
                    client.send(failure(id, "cannot attach: ${e.message}"))
                    return null
                }
                client.send(HubResponse.newBuilder().setId(id).setOk(true).build().toByteArray())
                daemon
            }
            HubRequest.CommandCase.DETACH -> {
                client.send(HubResponse.newBuilder().setId(id).setOk(true).build().toByteArray())
                null
            }
            else -> {
                client.send(failure(id, "missing hub command"))
                null
            }
        }
    }

    private fun failure(id: Long, message: String): ByteArray =
        HubResponse.newBuilder().setId(id).setOk(false).setError(message).build().toByteArray()

    private suspend fun forward(frame: Frame, target: Transport) {
        try {
            target.send(frame.payload, frame.fds)
        } finally {
            frame.close()
        }
    }

    override fun close() {
        server.close()
        scope.cancel()
    }

    companion object {
        private const val TAG = "HubServer"

        /** Bind [endpoint] and start serving. */
        fun listen(
            endpoint: UdsEndpoint,
            resolver: (String) -> DaemonEndpoint?,
            endpointsProvider: () -> List<DaemonEndpoint>,
        ): HubServer = HubServer(UdsServer.listen(endpoint), resolver, endpointsProvider)
    }
}
