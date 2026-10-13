package io.furryr.file.uds

import kotlinx.coroutines.flow.Flow
import java.io.Closeable
import java.io.FileDescriptor

/**
 * A full-duplex, event-driven UDS connection carrying opaque framed payloads
 * and — on demand — file descriptors. Protocol-agnostic by design: neither
 * side's message schema is known here.
 *
 * Reader and writer run independently: [events] is driven by the transport's
 * own reader loop while [send] is safe to call concurrently. [events] is a
 * single-consumer flow (backed by a channel); collect it once.
 */
interface Transport : Closeable {

    /** Inbound frames and lifecycle transitions. Single consumer. */
    val events: Flow<TransportEvent>

    /**
     * Send one payload. [fds] are borrowed for the duration of the call and may
     * be empty. Suspends until written.
     */
    suspend fun send(payload: ByteArray, fds: List<FileDescriptor> = emptyList())

    override fun close()
}
