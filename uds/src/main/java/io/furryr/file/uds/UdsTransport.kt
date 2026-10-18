package io.furryr.file.uds

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.FileDescriptor
import java.io.IOException

/**
 * Default [Transport] over a [UdsSocket]. A dedicated reader coroutine pumps
 * inbound frames into [events]; [send] serializes writes behind a lock. Both run
 * on [Dispatchers.IO], so a blocking read never stalls a write.
 */
internal class UdsTransport(private val socket: UdsSocket) : Transport {

    @Volatile private var closed = false
    private val stateLock = Any()

    private val eventChannel = Channel<TransportEvent>(Channel.UNLIMITED) { event ->
        if (event is TransportEvent.Incoming) event.frame.close()
    }
    override val events: Flow<TransportEvent> = eventChannel.receiveAsFlow()

    private val writeLock = Mutex()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        scope.launch { readerLoop() }
    }

    private fun readerLoop() {
        try {
            while (!closed) {
                val frame = socket.receiveFrame()
                // Publishing and closing share a lock: a late recvmsg result must
                // not enqueue descriptors after close() has drained the channel.
                synchronized(stateLock) {
                    if (closed || eventChannel.trySend(TransportEvent.Incoming(frame)).isFailure) {
                        frame.close()
                    }
                }
            }
        } catch (e: Exception) {
            terminate(e)
        }
    }

    override suspend fun send(payload: ByteArray, fds: List<FileDescriptor>) {
        withContext(Dispatchers.IO) {
            writeLock.withLock {
                if (closed) throw IOException("transport closed")
                try {
                    socket.sendFrame(payload, fds)
                } catch (e: Exception) {
                    terminate(e)
                    throw e
                }
            }
        }
    }

    override fun close() {
        terminate(null, discardQueued = true)
    }

    private fun terminate(cause: Throwable?, discardQueued: Boolean = false) {
        synchronized(stateLock) {
            if (!closed) {
                closed = true
                runCatching { socket.close() }
                eventChannel.trySend(TransportEvent.Closed(cause))
                eventChannel.close()
                scope.cancel()
            }
            if (discardQueued) {
                while (true) {
                    val event = eventChannel.tryReceive().getOrNull() ?: break
                    if (event is TransportEvent.Incoming) event.frame.close()
                }
            }
        }
    }
}
