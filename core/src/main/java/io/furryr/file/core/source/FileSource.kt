package io.furryr.file.core.source

import java.io.Closeable
import java.io.InputStream

enum class OpenMode { READ, WRITE, READ_WRITE }

/**
 * Universal file handle abstraction. Provides block-level positional I/O
 * independent of backend (daemon handle, native fd, or Java stream).
 *
 * Each concrete variant exposes [readable]/[writable]/[seekable] flags and
 * implements [readAt]/[writeAt]/[seek]/[truncate]/[close].
 *
 * [borrow] creates an independent handle, [detach] transfers ownership.
 */
abstract class FileSource : Closeable {
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

/** Pure Java stream (SAF, content URIs, FTP/SFTP streams, etc.). Read-only, sequential. */
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
