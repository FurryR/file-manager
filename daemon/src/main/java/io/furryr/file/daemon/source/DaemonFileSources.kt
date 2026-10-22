package io.furryr.file.daemon.source

import io.furryr.file.core.source.FileSource
import io.furryr.file.core.source.OpenMode
import io.furryr.file.daemon.ipc.*
import java.io.FileDescriptor
import java.io.FileInputStream

/** Daemon-virtual handle. All I/O goes through [DaemonConnection] handle protocol. */
class HandleFileSource internal constructor(
    val handleId: Long,
    override val size: Long,
    override val absolutePath: String?,
    internal val connection: DaemonConnection,
    private val mode: OpenMode,
    private val onClose: () -> Unit = {},
) : FileSource() {
    override val readable get() = mode == OpenMode.READ || mode == OpenMode.READ_WRITE
    override val writable get() = mode == OpenMode.WRITE || mode == OpenMode.READ_WRITE
    override val seekable get() = true
    private var position: Long = 0
    private var closed = false

    override fun readAt(offset: Long, len: Int): ByteArray {
        check(!closed) { "handle closed" }
        val r = connection.readHandle(handleId, offset, len).getOrThrow()
        return r.data.toByteArray()
    }

    override fun writeAt(offset: Long, data: ByteArray) {
        check(!closed) { "handle closed" }
        connection.writeHandle(handleId, offset, data).getOrThrow()
    }

    override fun seek(position: Long) {
        this.position = position
    }

    override fun truncate(newSize: Long) {
        check(!closed) { "handle closed" }
        connection.truncateHandle(handleId, newSize).getOrThrow()
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { connection.closeHandle(handleId) }
        onClose()
    }

    override fun borrow(): FileSource? {
        check(!closed) { "handle closed" }
        return connection.dupHandle(handleId).getOrNull()?.let { result ->
            HandleFileSource(result.handleId, result.size, absolutePath, connection, mode)
        }
    }

    override fun detach(): FileSource {
        check(!closed) { "handle closed" }
        closed = true
        return HandleFileSource(handleId, size, absolutePath, connection, mode)
    }
}

/** Native file descriptor, typically received from a daemon via SCM_RIGHTS. */
class FdFileSource(
    val fileDescriptor: FileDescriptor,
    override val size: Long,
    override val absolutePath: String?,
    private val mode: OpenMode,
    private val onClose: () -> Unit = {},
) : FileSource() {
    override val readable get() = mode == OpenMode.READ || mode == OpenMode.READ_WRITE
    override val writable get() = mode == OpenMode.WRITE || mode == OpenMode.READ_WRITE
    override val seekable get() = false
    private var closed = false
    private var position = 0L
    private val inputStream = FileInputStream(fileDescriptor)

    override fun readAt(offset: Long, len: Int): ByteArray {
        check(!closed) { "fd closed" }
        require(offset == position) { "fd source only supports sequential reads" }
        val buffer = ByteArray(len)
        val read = inputStream.read(buffer)
        if (read <= 0) return ByteArray(0)
        position += read
        return buffer.copyOf(read)
    }

    override fun writeAt(offset: Long, data: ByteArray) {
        check(!closed) { "fd closed" }
        throw UnsupportedOperationException("write not supported on bare fd; use HandleFileSource")
    }

    override fun seek(position: Long) {
        throw UnsupportedOperationException("seek not supported on fd; use HandleFileSource")
    }

    override fun truncate(newSize: Long) {
        throw UnsupportedOperationException("truncate not supported on fd; use HandleFileSource")
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { inputStream.close() }
        onClose()
    }
}

/** Map [OpenMode] to the daemon protocol enum. */
internal fun OpenMode.toProto(): io.furryr.file.proto.HandleMode = when (this) {
    OpenMode.READ -> io.furryr.file.proto.HandleMode.READ
    OpenMode.WRITE -> io.furryr.file.proto.HandleMode.WRITE
    OpenMode.READ_WRITE -> io.furryr.file.proto.HandleMode.READ_WRITE
}

/** Open [path] on a daemon, preferring a real kernel descriptor. */
internal fun openDaemonSource(
    path: String,
    mode: OpenMode,
    openConnection: () -> DaemonConnection,
): FileSource {
    val connection = openConnection()
    val result = connection.openHandle(path, mode.toProto(), wantFd = true).getOrThrow()
    val fd = result.fileDescriptor?.takeIf { mode == OpenMode.READ }
    return if (fd != null) {
        FdFileSource(fd, result.size, path, mode) {
            runCatching { connection.closeHandle(result.handleId) }
        }
    } else {
        HandleFileSource(result.handleId, result.size, path, connection, mode)
    }
}
