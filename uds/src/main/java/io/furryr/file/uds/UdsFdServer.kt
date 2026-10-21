package io.furryr.file.uds

import android.system.Os
import android.util.Log
import java.io.Closeable
import java.io.File
import java.io.FileDescriptor
import java.io.IOException

/**
 * A raw UNIX-domain-socket listener. Accepted descriptors are handed to the
 * caller as plain ints and stay owned by the caller until it transfers them
 * elsewhere.
 *
 * Unlike [UdsServer], this exposes no framed-transport layer: it is for
 * consumers whose protocol is implemented outside Kotlin (for example a JNI
 * renderer that reads the vtest stream directly).
 */
class UdsFdServer internal constructor(
    private val endpoint: UdsEndpoint,
    private val listenerFd: FileDescriptor,
) : Closeable {

    @Volatile private var closed = false

    /** Block until a client connects, then return its raw descriptor. */
    fun acceptFd(): Int {
        if (closed) throw IOException("listener closed")
        val fd = UdsNative.accept(listenerFd)
        return try {
            UdsNative.fdOf(fd)
        } catch (error: Throwable) {
            runCatching { Os.close(fd) }
            throw error
        }
    }

    fun closeFd(fd: Int) {
        UdsNative.closeRaw(fd)
    }

    override fun close() {
        if (closed) return
        closed = true
        wakeAccept()
        runCatching { Os.close(listenerFd) }
        if (endpoint is UdsEndpoint.Path) {
            runCatching { File(endpoint.name).delete() }
        }
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
        private const val TAG = "UdsFdServer"

        /**
         * Bind [endpoint] and listen. For [UdsEndpoint.Path], the caller must
         * remove a stale socket file first; this method never touches an
         * existing path.
         */
        fun listen(endpoint: UdsEndpoint, backlog: Int = 16): UdsFdServer {
            val fd = try {
                UdsNative.listen(endpoint, backlog)
            } catch (t: Throwable) {
                Log.w(TAG, "cannot listen on ${endpoint.name}", t)
                throw t
            }
            return UdsFdServer(endpoint, fd)
        }
    }
}
