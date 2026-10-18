package io.furryr.file.uds

/** Entry point for UDS clients. */
object UdsClient {

    /** Connect once to [endpoint]. EOF and I/O errors are terminal. */
    fun connect(endpoint: UdsEndpoint): Transport {
        val socket = UdsNative.connectSocket(endpoint)
        return UdsTransport(socket)
    }
}
