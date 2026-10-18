package io.furryr.file.uds

import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.EOFException
import java.io.FileDescriptor
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One connected UNIX stream socket: a 4-byte big-endian length prefix followed
 * by the payload, with SCM_RIGHTS descriptors riding the frame's first write.
 *
 * Blocking and single-reader: [receiveFrame] is called from exactly one reader
 * coroutine; [sendFrame] is serialized by the caller ([UdsTransport]'s lock).
 * Each socket owns its own scratch buffer so concurrent connections never share
 * receive memory.
 */
internal class UdsSocket(val fd: FileDescriptor) {

    private val scratch: ByteBuffer = ByteBuffer.allocateDirect(SCRATCH)

    @Volatile private var closed = false

    val isOpen: Boolean get() = !closed

    fun sendFrame(payload: ByteArray, fds: List<FileDescriptor> = emptyList()) {
        if (closed) throw IOException("socket closed")
        val frame = ByteArray(HEADER_SIZE + payload.size)
        frame[0] = (payload.size ushr 24).toByte()
        frame[1] = (payload.size ushr 16).toByte()
        frame[2] = (payload.size ushr 8).toByte()
        frame[3] = payload.size.toByte()
        System.arraycopy(payload, 0, frame, HEADER_SIZE, payload.size)

        var sent = 0
        var attach: List<FileDescriptor> = fds
        while (sent < frame.size) {
            val n = send(frame, sent, attach)
            if (n <= 0) throw IOException("send failed")
            sent += n
            attach = emptyList() // descriptors ride exactly one chunk
        }
    }

    fun receiveFrame(): Frame {
        if (closed) throw IOException("socket closed")
        val fds = ArrayList<FileDescriptor>(1)
        try {
            val header = ByteArray(HEADER_SIZE)
            readFully(header, fds)
            val size = ((header[0].toInt() and 0xFF) shl 24) or
                    ((header[1].toInt() and 0xFF) shl 16) or
                    ((header[2].toInt() and 0xFF) shl 8) or
                    (header[3].toInt() and 0xFF)
            if (size < 0 || size > MAX_FRAME) throw IOException("invalid frame size: $size")
            val payload = ByteArray(size)
            readFully(payload, fds)
            return Frame(payload, fds)
        } catch (e: Throwable) {
            // recvmsg may have delivered descriptors before a partial frame fails.
            fds.forEach { runCatching { Os.close(it) } }
            throw e
        }
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        // shutdown() wakes a reader blocked in recvmsg; close() alone may not.
        runCatching { Os.shutdown(fd, OsConstants.SHUT_RDWR) }
        runCatching { Os.close(fd) }
    }

    private fun readFully(dst: ByteArray, fds: MutableList<FileDescriptor>) {
        var got = 0
        while (got < dst.size) {
            val n = recv(dst, got, dst.size - got, fds)
            if (n == 0) throw EOFException("peer closed")
            if (n < 0) throw IOException("read failed")
            got += n
        }
    }

    private fun recv(dst: ByteArray, off: Int, len: Int, fds: MutableList<FileDescriptor>): Int {
        scratch.clear()
        scratch.limit(minOf(len, SCRATCH))
        val msg = UdsNative.msghdr(null, arrayOf(scratch), null, 0)
        val n = UdsNative.recvmsg(fd, msg, UdsNative.MSG_CMSG_CLOEXEC)
        if (n <= 0) return n
        scratch.position(0)
        scratch.limit(n)
        scratch.get(dst, off, n)

        UdsNative.control(msg)?.forEach { cmsg ->
            if (cmsg != null && UdsNative.cmsgType(cmsg) == UdsNative.SCM_RIGHTS) {
                collectFds(UdsNative.cmsgData(cmsg), fds)
            }
        }
        if (UdsNative.msgFlags(msg) and UdsNative.MSG_CTRUNC != 0) {
            Log.w(TAG, "SCM_RIGHTS truncated; descriptor dropped")
        }
        return n
    }

    private fun send(frame: ByteArray, off: Int, fds: List<FileDescriptor>): Int {
        val iov = ByteBuffer.wrap(frame, off, frame.size - off)
        val control = if (fds.isEmpty()) null else {
            val data = ByteArray(fds.size * 4)
            val buffer = ByteBuffer.wrap(data).order(ByteOrder.nativeOrder())
            fds.forEach { buffer.putInt(UdsNative.fdOf(it)) }
            val array = UdsNative.cmsghdrArray(1)
            java.lang.reflect.Array.set(
                array, 0,
                UdsNative.cmsghdr(UdsNative.SOL_SOCKET, UdsNative.SCM_RIGHTS, data),
            )
            array
        }
        val msg = UdsNative.msghdr(null, arrayOf(iov), control, 0)
        return UdsNative.sendmsg(fd, msg, 0)
    }

    private fun collectFds(data: ByteArray, fds: MutableList<FileDescriptor>) {
        var i = 0
        while (i + 4 <= data.size) {
            val raw = ByteBuffer.wrap(data, i, 4).order(ByteOrder.nativeOrder()).int
            if (raw >= 0) fds.add(UdsNative.fileDescriptorOf(raw))
            i += 4
        }
    }

    companion object {
        private const val TAG = "UdsSocket"
        private const val HEADER_SIZE = 4
        private const val SCRATCH = 64 * 1024
        private const val MAX_FRAME = 64 * 1024 * 1024
    }
}
