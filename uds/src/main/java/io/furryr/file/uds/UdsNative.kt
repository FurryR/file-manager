package io.furryr.file.uds

import android.system.Os
import android.system.OsConstants
import java.io.FileDescriptor
import java.io.IOException
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Reflective bridge to the non-SDK parts of `android.system.Os` this module
 * needs: `recvmsg`/`sendmsg` (to receive SCM_RIGHTS ourselves rather than trust
 * `LocalSocket`) and `UnixSocketAddress` (abstract/filesystem addresses).
 *
 * The public wrappers (`socket`, `bind`, `listen`, `accept`, `connect`, `close`,
 * `shutdown`) are called directly; the whole `Landroid/system/` prefix is
 * exempted by [HiddenApi] anyway.
 */
internal object UdsNative {

    // From <linux/socket.h>; not exposed by android.system.OsConstants.
    const val SOL_SOCKET = 0x01
    const val SCM_RIGHTS = 0x01
    const val MSG_CTRUNC = 0x08
    const val MSG_CMSG_CLOEXEC = 0x40000000

    class Refs {
        val recvmsg: Method
        val sendmsg: Method
        val msghdrCtor: Constructor<*>
        val cmsgCtor: Constructor<*>
        val msgControlField: Field
        val msgFlagsField: Field
        val cmsgTypeField: Field
        val cmsgDataField: Field
        val createAbstract: Method
        val createFilesystem: Method
        val cmsghdrClass: Class<*>

        init {
            val os = Class.forName("android.system.Os")
            val msghdr = Class.forName("android.system.StructMsghdr")
            val cmsghdr = Class.forName("android.system.StructCmsghdr")
            val unix = Class.forName("android.system.UnixSocketAddress")
            cmsghdrClass = cmsghdr
            recvmsg = os.getMethod("recvmsg", FileDescriptor::class.java, msghdr, Int::class.javaPrimitiveType)
            sendmsg = os.getMethod("sendmsg", FileDescriptor::class.java, msghdr, Int::class.javaPrimitiveType)
            val byteBufferArray = java.lang.reflect.Array.newInstance(ByteBuffer::class.java, 0).javaClass
            val cmsghdrArray = java.lang.reflect.Array.newInstance(cmsghdr, 0).javaClass
            msghdrCtor = msghdr.getConstructor(
                SocketAddress::class.java, byteBufferArray, cmsghdrArray, Int::class.javaPrimitiveType,
            )
            cmsgCtor = cmsghdr.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, ByteArray::class.java)
            msgControlField = msghdr.getField("msg_control")
            msgFlagsField = msghdr.getField("msg_flags")
            cmsgTypeField = cmsghdr.getField("cmsg_type")
            cmsgDataField = cmsghdr.getField("cmsg_data")
            createAbstract = unix.getMethod("createAbstract", String::class.java)
            createFilesystem = unix.getMethod("createFileSystem", String::class.java)
        }
    }

    private val refs: Refs by lazy {
        HiddenApi.ensure()
        Refs()
    }

    fun msghdr(name: SocketAddress?, iov: Array<ByteBuffer>, control: Any?, flags: Int): Any =
        refs.msghdrCtor.newInstance(name, iov, control, flags)

    fun cmsghdr(level: Int, type: Int, data: ByteArray): Any =
        refs.cmsgCtor.newInstance(level, type, data)

    fun cmsghdrArray(size: Int): Any = java.lang.reflect.Array.newInstance(refs.cmsghdrClass, size)

    fun recvmsg(fd: FileDescriptor, msg: Any, flags: Int): Int =
        refs.recvmsg.invoke(null, fd, msg, flags) as Int

    fun sendmsg(fd: FileDescriptor, msg: Any, flags: Int): Int =
        refs.sendmsg.invoke(null, fd, msg, flags) as Int

    fun control(msg: Any): Array<*>? = refs.msgControlField.get(msg) as? Array<*>
    fun msgFlags(msg: Any): Int = refs.msgFlagsField.getInt(msg)
    fun cmsgType(cmsg: Any): Int = refs.cmsgTypeField.getInt(cmsg)
    fun cmsgData(cmsg: Any): ByteArray = refs.cmsgDataField.get(cmsg) as ByteArray

    fun socket(): FileDescriptor = Os.socket(OsConstants.AF_UNIX, OsConstants.SOCK_STREAM, 0)

    fun accept(listenerFd: FileDescriptor): FileDescriptor = Os.accept(listenerFd, null)

    fun address(endpoint: UdsEndpoint): SocketAddress = when (endpoint) {
        is UdsEndpoint.Abstract -> refs.createAbstract.invoke(null, endpoint.name) as SocketAddress
        is UdsEndpoint.Path -> refs.createFilesystem.invoke(null, endpoint.name) as SocketAddress
    }

    fun connect(fd: FileDescriptor, endpoint: UdsEndpoint) {
        Os.connect(fd, address(endpoint))
    }

    fun connectSocket(endpoint: UdsEndpoint): UdsSocket {
        val fd = socket()
        try {
            connect(fd, endpoint)
        } catch (t: Throwable) {
            runCatching { Os.close(fd) }
            throw IOException("cannot connect to ${endpoint.name}", t)
        }
        return UdsSocket(fd)
    }

    fun listen(endpoint: UdsEndpoint, backlog: Int): FileDescriptor {
        val fd = socket()
        try {
            Os.bind(fd, address(endpoint))
            Os.listen(fd, backlog)
        } catch (t: Throwable) {
            runCatching { Os.close(fd) }
            throw IOException("cannot bind ${endpoint.name}", t)
        }
        return fd
    }

    fun fdOf(fd: FileDescriptor): Int {
        val field = FileDescriptor::class.java.getDeclaredField("descriptor")
        field.isAccessible = true
        return field.getInt(fd)
    }

    fun fileDescriptorOf(raw: Int): FileDescriptor {
        val fd = FileDescriptor()
        val field = FileDescriptor::class.java.getDeclaredField("descriptor")
        field.isAccessible = true
        field.setInt(fd, raw)
        return fd
    }

    fun intBytes(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.nativeOrder()).putInt(value).array()
}
