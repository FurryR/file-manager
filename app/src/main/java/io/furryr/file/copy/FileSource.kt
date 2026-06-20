package io.furryr.file.copy

import io.furryr.file.daemon.DaemonLauncher
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

    fun readChunk(offset: Long, chunkSize: Int): ByteArray =
        readAt(offset, minOf(chunkSize.toLong(), (size - offset).coerceAtLeast(0)).toInt())
}

// ══════════════════════════════════════════════════════════════════
//  Concrete variants
// ══════════════════════════════════════════════════════════════════

/** Daemon-virtual handle. All I/O goes through [DaemonConnection] handle protocol. */
class HandleFileSource(
    val handleId: Long,
    override val size: Long,
    override val absolutePath: String?,
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
        val r = DaemonLauncher.getConnection().readHandle(handleId, offset, len).getOrThrow()
        return r.data.toByteArray()
    }

    override fun writeAt(offset: Long, data: ByteArray) {
        check(!closed) { "handle closed" }
        DaemonLauncher.getConnection().writeHandle(handleId, offset, data).getOrThrow()
    }

    override fun seek(position: Long) {
        this.position = position
    }

    override fun truncate(newSize: Long) {
        check(!closed) { "handle closed" }
        DaemonLauncher.getConnection().truncateHandle(handleId, newSize).getOrThrow()
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { DaemonLauncher.getConnection().closeHandle(handleId) }
        onClose()
    }

    override fun borrow(): FileSource? {
        check(!closed) { "handle closed" }
        return DaemonLauncher.getConnection().dupHandle(handleId).getOrNull()?.let { (dupId, sz) ->
            HandleFileSource(dupId, sz, absolutePath, mode)
        }
    }

    override fun detach(): FileSource {
        check(!closed) { "handle closed" }
        closed = true
        return HandleFileSource(handleId, size, absolutePath, mode)
    }
}

/** Native file descriptor, typically received from a daemon via SCM_RIGHTS. */
class FdFileSource(
    val fileDescriptor: FileDescriptor,
    override val size: Long,
    override val absolutePath: String?,
    private val mode: OpenMode,
) : FileSource() {
    override val readable get() = mode == OpenMode.READ || mode == OpenMode.READ_WRITE
    override val writable get() = mode == OpenMode.WRITE || mode == OpenMode.READ_WRITE
    override val seekable get() = false // can't seek on Android without JNI
    private var closed = false

    override fun readAt(offset: Long, _len: Int): ByteArray {
        check(!closed) { "fd closed" }
        // Sequential-read only; offset is ignored.
        val stream = FileInputStream(fileDescriptor)
        return stream.use { it.readBytes() }
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
        runCatching { fileDescriptor.let { /* no explicit close needed for received fds */ } }
    }

    override fun detach(): FileSource {
        check(!closed) { "fd closed" }
        closed = true
        return FdFileSource(fileDescriptor, size, absolutePath, mode)
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

    override fun readAt(offset: Long, _len: Int): ByteArray {
        check(!closed) { "stream closed" }
        return if (offset == 0L && !closed) {
            inputStream.use { it.readBytes() }
        } else {
            throw UnsupportedOperationException("stream does not support positional read")
        }
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
