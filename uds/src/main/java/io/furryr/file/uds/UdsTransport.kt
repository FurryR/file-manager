package io.furryr.file.uds

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.io.FileDescriptor
import java.io.IOException

/**
 * Default [Transport] over a [UdsSocket]. A dedicated reader coroutine pumps
 * inbound frames into [events]; [send] serializes writes behind a lock. Both run
 * on [Dispatchers.IO], so a blocking read never stalls a write.
 */
internal class UdsTransport(
    initial: UdsSocket,
    private val reconnect: ReconnectPolicy? = null,
    private val reconnectFactory: (() -> UdsSocket)? = null,
) : Transport {

    @Volatile private var socket: UdsSocket = initial

    @Volatile private var closed = false

    private val eventChannel = Channel<TransportEvent>(Channel.UNLIMITED)
    override val events: Flow<TransportEvent> = eventChannel.receiveAsFlow()

    private val writeLock = Mutex()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        scope.launch { readerLoop() }
    }

    private suspend fun readerLoop() {
        while (true) {
            try {
                while (true) {
                    eventChannel.send(TransportEvent.Incoming(socket.receiveFrame()))
                }
            } catch (_: EOFException) {
                // peer closed this connection; a reconnect may still follow
            } catch (e: Exception) {
                if (closed) return
                Log.w(TAG, "reader error", e)
            }
            if (closed) return
            if (!attemptReconnect()) {
                eventChannel.send(TransportEvent.Closed(IOException("transport lost")))
                eventChannel.close()
                return
            }
        }
    }

    private suspend fun attemptReconnect(): Boolean {
        val policy = reconnect ?: return false
        val factory = reconnectFactory ?: return false
        for (attempt in 1..policy.attempts) {
            if (closed) return false
            eventChannel.send(TransportEvent.Reconnecting(attempt))
            try {
                socket = factory()
                eventChannel.send(TransportEvent.Reconnected)
                return true
            } catch (_: Exception) {
                delay(policy.delayMillis)
            }
        }
        return false
    }

    override suspend fun send(payload: ByteArray, fds: List<FileDescriptor>) {
        withContext(Dispatchers.IO) {
            writeLock.withLock {
                if (closed) throw IOException("transport closed")
                socket.sendFrame(payload, fds)
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { socket.close() }
        // Free descriptors queued but never delivered.
        while (true) {
            val event = eventChannel.tryReceive().getOrNull() ?: break
            if (event is TransportEvent.Incoming) event.frame.close()
        }
        eventChannel.close()
        scope.cancel()
    }

    private companion object {
        const val TAG = "UdsTransport"
    }
}
