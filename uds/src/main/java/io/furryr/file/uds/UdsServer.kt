package io.furryr.file.uds

import android.system.Os
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
import java.io.Closeable
import java.io.FileDescriptor

/**
 * A listening UDS server. Accepted connections surface as [Transport]s on
 * [connections]; each is an independent full-duplex session.
 *
 * The accept loop blocks on `accept(2)`; [close] wakes it by connecting to our
 * own endpoint once, then tears the listener down.
 */
class UdsServer internal constructor(
    private val endpoint: UdsEndpoint,
    private val listenerFd: FileDescriptor,
) : Closeable {

    @Volatile private var closed = false

    private val connectionChannel = Channel<Transport>(Channel.UNLIMITED)
    val connections: Flow<Transport> = connectionChannel.receiveAsFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        scope.launch { acceptLoop() }
    }

    private suspend fun acceptLoop() {
        while (!closed) {
            val clientFd = try {
                UdsNative.accept(listenerFd)
            } catch (e: Exception) {
                if (closed) return
                Log.w(TAG, "accept failed", e)
                delay(50)
                continue
            }
            if (closed) {
                runCatching { Os.close(clientFd) }
                return
            }
            connectionChannel.send(UdsTransport(UdsSocket(clientFd)))
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        wakeAccept()
        connectionChannel.close()
        runCatching { Os.close(listenerFd) }
        scope.cancel()
    }

    private fun wakeAccept() {
        runCatching {
            val fd = UdsNative.socket()
            try {
                UdsNative.connect(fd, endpoint)
            } finally {
                runCatching { Os.close(fd) }
            }
        }
    }

    companion object {
        private const val TAG = "UdsServer"

        /** Bind [endpoint] and start accepting. */
        fun listen(endpoint: UdsEndpoint, backlog: Int = 16): UdsServer {
            val fd = UdsNative.listen(endpoint, backlog)
            return UdsServer(endpoint, fd)
        }
    }
}
