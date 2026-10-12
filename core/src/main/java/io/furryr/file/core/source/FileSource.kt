package io.furryr.file.core.source

import io.furryr.file.core.ipc.DaemonConnection
import io.furryr.file.proto.HandleMode as ProtoHandleMode
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.InputStream
import java.io.Closeable

enum class OpenMode { READ, WRITE, READ_WRITE }

/**
 * Universal file handle abstraction. Provides block-level positional I/O
 * independent of backend (daemon handle, native fd, or Java stream).
 *
 * Each concrete variant exposes [readable]/[writable]/[seekable] flags and
 * implements [readAt]/[writeAt]/[seek]/[truncate]/[close].
 *
 * [borrow] creates an independent handle (dup fd / daemon handle clone).
 * [detach] transfers ownership — the returned handle must be closed manually,
 * and the original becomes a no-op.
 */
sealed class FileSource : Closeable {
    abstract val readable: Boolean
    abstract val writable: Boolean
    abstract val seekable: Boolean
    abstract val size: Long
    abstract val absolutePath: String?

    /** Read up to [len] bytes at [offset]. Returns empty array on EOF. */
    abstract fun readAt(offset: Long, len: Int): ByteArray

    /** Write all of [data] at [offset], extending file if needed. */
    abstract fun writeAt(offset: Long, data: ByteArray)

    /** Set read/write position (for seekable handles). */
    abstract fun seek(position: Long)

    /** Truncate or extend to [newSize]. */
    abstract fun truncate(newSize: Long)

    /** Release underlying resources. Repeated calls are safe. */
    abstract override fun close()

    /** Create an independent handle to the same file (concurrent access). */
    open fun borrow(): FileSource? = null

    /** Transfer ownership without closing. Caller must close the returned handle. */
    open fun detach(): FileSource? = null

    // ── Convenience ──────────────────────────────────────────────

    fun readFully(offset: Long, len: Int): ByteArray {
        val buf = readAt(offset, len)
        if (buf.size < len) throw java.io.EOFException("short read: expected $len, got ${buf.size}")
        return buf
    }

    fun readChunk(offset: Long, chunkSize: Int): ByteArray {
        require(chunkSize > 0) { "chunkSize must be positive" }
        val length = if (seekable) {
            minOf(chunkSize.toLong(), (size - offset).coerceAtLeast(0)).toInt()
        } else {
            chunkSize
        }
        return readAt(offset, length)
    }
}

// ══════════════════════════════════════════════════════════════════
//  Concrete variants
// ══════════════════════════════════════════════════════════════════

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
    override val seekable get() = false // can't seek on Android without JNI
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

/** Pure Java stream (SAF, content URIs, etc.). Read-only, sequential. */
class StreamFileSource(
    val inputStream: InputStream,
    override val size: Long,
    override val absolutePath: String? = null,
) : FileSource() {
    override val readable get() = true
    override val writable get() = false
    override val seekable get() = false
    private var closed = false
    private var position = 0L

    override fun readAt(offset: Long, len: Int): ByteArray {
        check(!closed) { "stream closed" }
        require(offset == position) { "stream source only supports sequential reads" }
        val buffer = ByteArray(len)
        val read = inputStream.read(buffer)
        if (read <= 0) return ByteArray(0)
        position += read
        return buffer.copyOf(read)
    }

    override fun writeAt(offset: Long, data: ByteArray) =
        throw UnsupportedOperationException("stream is read-only")

    override fun seek(position: Long) =
        throw UnsupportedOperationException("stream is not seekable")

    override fun truncate(newSize: Long) =
        throw UnsupportedOperationException("stream does not support truncate")

    override fun close() {
        if (closed) return
        closed = true
        runCatching { inputStream.close() }
    }
}

// ── Internal helper ──────────────────────────────────────────────

internal fun OpenMode.toProto(): ProtoHandleMode = when (this) {
    OpenMode.READ -> ProtoHandleMode.READ
    OpenMode.WRITE -> ProtoHandleMode.WRITE
    OpenMode.READ_WRITE -> ProtoHandleMode.READ_WRITE
}

/**
 * Open [path] on a daemon, preferring a real kernel descriptor but falling back
 * to a virtual handle.
 *
 * Whether an fd can be received depends on the *file's* SELinux label relative
 * to the receiving app's context — something the client cannot predict. A denied
 * hand-off only truncates the SCM_RIGHTS list, so the reply arrives without a
 * descriptor; we then use [HandleFileSource] (reads proxied on demand) instead.
 */
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
