package io.furryr.file.core.ipc

import android.system.Os
import android.system.OsConstants
import android.util.Log
import io.furryr.file.core.HiddenApi
import io.furryr.file.proto.Response
import java.io.EOFException
import java.io.FileDescriptor
import java.io.IOException
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Abstract-namespace UNIX stream socket built on `android.system.Os`, replacing
 * `android.net.LocalSocket`.
 *
 * `LocalSocket`'s reader turns a truncated SCM_RIGHTS receive into a thrown
 * `EMSGSIZE`, which desyncs the framed stream and (before) tore down the daemon.
 * Driving `recvmsg`/`sendmsg` ourselves lets us treat `MSG_CTRUNC` as "no fd"
 * and keep the frame.
 *
 * `Os.recvmsg`/`Os.sendmsg`, `UnixSocketAddress` and a few `OsConstants` are
 * non-SDK interfaces, reached reflectively after [HiddenApi] lifts the
 * restriction. Setup failure is fatal by design (no degraded mode).
 */
internal class OsSocket private constructor(private val fd: FileDescriptor) {

    private var closed = false

    val isOpen: Boolean get() = !closed

    fun readFrame(): DaemonResponse {
        if (closed) throw IOException("socket closed")
        val attached = ArrayList<FileDescriptor>(1)
        val header = ByteArray(HEADER_SIZE)
        readFully(header, attached)
        val size = (header[0].toInt() and 0xFF shl 24) or
                (header[1].toInt() and 0xFF shl 16) or
                (header[2].toInt() and 0xFF shl 8) or
                (header[3].toInt() and 0xFF)
        if (size < 0 || size > MAX_FRAME) throw IOException("invalid daemon frame size: $size")
        val payload = ByteArray(size)
        readFully(payload, attached)
        return DaemonResponse(Response.parseFrom(payload), attached.firstOrNull())
    }

    fun writeFrame(payload: ByteArray, fdToSend: FileDescriptor? = null) {
        if (closed) throw IOException("socket closed")
        val frame = ByteArray(HEADER_SIZE + payload.size)
        frame[0] = (payload.size ushr 24).toByte()
        frame[1] = (payload.size ushr 16).toByte()
        frame[2] = (payload.size ushr 8).toByte()
        frame[3] = payload.size.toByte()
        System.arraycopy(payload, 0, frame, HEADER_SIZE, payload.size)

        var sent = 0
        var pendingFd = fdToSend
        while (sent < frame.size) {
            val n = send(frame, sent, pendingFd)
            if (n <= 0) throw IOException("daemon send failed")
            sent += n
            pendingFd = null // the fd rides exactly one chunk
        }
    }

    fun close() {
        if (closed) return
        closed = true
        runCatching { Os.close(fd) }
    }

    private fun readFully(dst: ByteArray, attached: MutableList<FileDescriptor>) {
        var got = 0
        while (got < dst.size) {
            val n = recv(dst, got, dst.size - got, attached)
            if (n == 0) throw EOFException("unexpected EOF reading daemon frame")
            if (n < 0) throw IOException("daemon read failed")
            got += n
        }
    }

    private fun recv(dst: ByteArray, off: Int, len: Int, attached: MutableList<FileDescriptor>): Int {
        scratch.clear()
        scratch.limit(minOf(len, SCRATCH))
        val msg = msghdrCtor.newInstance(null, arrayOf(scratch), null, 0)
        val n = recvmsg.invoke(null, fd, msg, MSG_CMSG_CLOEXEC) as Int
        if (n <= 0) return n
        scratch.position(0)
        scratch.limit(n)
        scratch.get(dst, off, n)

        (msgControlField.get(msg) as? Array<*>)?.forEach { cmsg ->
            if (cmsg != null && cmsgTypeField.getInt(cmsg) == SCM_RIGHTS) {
                collectFds(cmsgDataField.get(cmsg) as ByteArray, attached)
            }
        }
        if (msgFlagsField.getInt(msg) and MSG_CTRUNC != 0) {
            Log.w(TAG, "SCM_RIGHTS truncated; fd dropped, using handle streaming")
        }
        return n
    }

    private fun send(frame: ByteArray, off: Int, fdToSend: FileDescriptor?): Int {
        val iov = ByteBuffer.wrap(frame, off, frame.size - off)
        val control = fdToSend?.let { sent ->
            val array = java.lang.reflect.Array.newInstance(cmsghdrClass, 1)
            java.lang.reflect.Array.set(
                array, 0,
                cmsgCtor.newInstance(SOL_SOCKET, SCM_RIGHTS, intBytes(fdOf(sent))),
            )
            array
        }
        val msg = msghdrCtor.newInstance(null, arrayOf(iov), control, 0)
        return sendmsg.invoke(null, fd, msg, 0) as Int
    }

    private fun collectFds(data: ByteArray, attached: MutableList<FileDescriptor>) {
        var i = 0
        while (i + 4 <= data.size) {
            val raw = ByteBuffer.wrap(data, i, 4).order(ByteOrder.nativeOrder()).int
            if (raw >= 0) attached.add(fileDescriptorOf(raw))
            i += 4
        }
    }

    companion object {
        private const val TAG = "OsSocket"
        private const val HEADER_SIZE = 4
        private const val SCRATCH = 64 * 1024
        private const val MAX_FRAME = 64 * 1024 * 1024

        // From <linux/socket.h>; not exposed by android.system.OsConstants.
        private const val SOL_SOCKET = 0x01
        private const val SCM_RIGHTS = 0x01
        private const val MSG_CTRUNC = 0x08
        private const val MSG_CMSG_CLOEXEC = 0x40000000

        // recvmsg runs on a single reader loop; a reusable direct buffer avoids
        // per-frame allocations.
        private val scratch: ByteBuffer = ByteBuffer.allocateDirect(SCRATCH)

        private val recvmsg: Method
        private val sendmsg: Method
        private val msghdrCtor: Constructor<*>
        private val cmsgCtor: Constructor<*>
        private val msgControlField: Field
        private val msgFlagsField: Field
        private val cmsgTypeField: Field
        private val cmsgDataField: Field
        private val cmsghdrClass: Class<*>
        private val unixCreateAbstract: Method

        init {
            HiddenApi.ensure()
            val os = Class.forName("android.system.Os")
            val msghdr = Class.forName("android.system.StructMsghdr")
            val cmsghdr = Class.forName("android.system.StructCmsghdr")
            cmsghdrClass = cmsghdr
            recvmsg = os.getMethod("recvmsg", FileDescriptor::class.java, msghdr, Int::class.javaPrimitiveType)
            sendmsg = os.getMethod("sendmsg", FileDescriptor::class.java, msghdr, Int::class.javaPrimitiveType)
            val byteBufferArray = java.lang.reflect.Array.newInstance(ByteBuffer::class.java, 0).javaClass
            val cmsghdrArray = java.lang.reflect.Array.newInstance(cmsghdr, 0).javaClass
            msghdrCtor = msghdr.getConstructor(
                java.net.SocketAddress::class.java, byteBufferArray, cmsghdrArray,
                Int::class.javaPrimitiveType,
            )
            cmsgCtor = cmsghdr.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, ByteArray::class.java)
            msgControlField = msghdr.getField("msg_control")
            msgFlagsField = msghdr.getField("msg_flags")
            cmsgTypeField = cmsghdr.getField("cmsg_type")
            cmsgDataField = cmsghdr.getField("cmsg_data")
            unixCreateAbstract = Class.forName("android.system.UnixSocketAddress")
                .getMethod("createAbstract", String::class.java)
            Log.i(TAG, "Os socket ready (SDK_INT=${android.os.Build.VERSION.SDK_INT})")
        }

        fun connect(name: String): OsSocket {
            val fd = Os.socket(OsConstants.AF_UNIX, OsConstants.SOCK_STREAM, 0)
            try {
                val address = unixCreateAbstract.invoke(null, name) as SocketAddress
                Os.connect(fd, address)
            } catch (t: Throwable) {
                runCatching { Os.close(fd) }
                throw IOException("cannot connect to $name", t)
            }
            return OsSocket(fd)
        }

        private fun fdOf(fileDescriptor: FileDescriptor): Int {
            val field = FileDescriptor::class.java.getDeclaredField("descriptor")
            field.isAccessible = true
            return field.getInt(fileDescriptor)
        }

        private fun intBytes(value: Int): ByteArray =
            ByteBuffer.allocate(4).order(ByteOrder.nativeOrder()).putInt(value).array()

        private fun fileDescriptorOf(raw: Int): FileDescriptor {
            val fileDescriptor = FileDescriptor()
            val field = FileDescriptor::class.java.getDeclaredField("descriptor")
            field.isAccessible = true
            field.setInt(fileDescriptor, raw)
            return fileDescriptor
        }
    }
}
