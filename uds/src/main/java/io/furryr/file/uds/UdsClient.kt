package io.furryr.file.uds

/** Entry point for UDS clients. */
object UdsClient {

    /**
     * Connect to [endpoint]. With a non-null [reconnect] the transport
     * re-attaches to the same endpoint after a transient drop instead of
     * emitting [TransportEvent.Closed].
     */
    fun connect(endpoint: UdsEndpoint, reconnect: ReconnectPolicy? = null): Transport {
        val socket = UdsNative.connectSocket(endpoint)
        val factory: (() -> UdsSocket)? =
            if (reconnect != null) ({ UdsNative.connectSocket(endpoint) }) else null
        return UdsTransport(initial = socket, reconnect = reconnect, reconnectFactory = factory)
    }
}
