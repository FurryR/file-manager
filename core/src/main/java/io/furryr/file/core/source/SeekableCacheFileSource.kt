package io.furryr.file.core.source

import java.io.File
import java.io.RandomAccessFile

/** Private local staging file. A writable close commits synchronously and propagates failure. */
class SeekableCacheFileSource(
    private val cacheFile: File,
    private val mode: OpenMode,
    override val absolutePath: String? = null,
    private val commit: ((File) -> Unit)? = null,
) : FileSource() {
    private val file = RandomAccessFile(cacheFile, if (mode == OpenMode.READ) "r" else "rw")
    private var closed = false
    private var dirty = mode == OpenMode.WRITE
    private var failed = false
    override val readable get() = mode != OpenMode.WRITE
    override val writable get() = mode != OpenMode.READ
    override val seekable get() = true
    override val size: Long @Synchronized get() { check(!closed); return file.length() }

    @Synchronized override fun readAt(offset: Long, len: Int): ByteArray {
        check(!closed && readable) { "source is not readable" }
        require(offset >= 0 && len >= 0)
        file.seek(offset)
        val bytes = ByteArray(minOf(len.toLong(), (file.length() - offset).coerceAtLeast(0)).toInt())
        file.readFully(bytes)
        return bytes
    }
    @Synchronized override fun writeAt(offset: Long, data: ByteArray) {
        check(!closed && writable) { "source is not writable" }
        require(offset >= 0)
        file.seek(offset)
        try { file.write(data) } catch (failure: Throwable) { failed = true; throw failure }
        dirty = true
    }
    @Synchronized override fun seek(position: Long) { check(!closed); require(position >= 0); file.seek(position) }
    @Synchronized override fun truncate(newSize: Long) {
        check(!closed && writable) { "source is not writable" }
        require(newSize >= 0)
        try { file.setLength(newSize) } catch (failure: Throwable) { failed = true; throw failure }
        dirty = true
    }
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        try {
            try { if (dirty) file.fd.sync() } finally { file.close() }
            if (dirty && !failed) commit?.invoke(cacheFile)
        } finally {
            if (cacheFile.exists() && !cacheFile.delete()) cacheFile.deleteOnExit()
        }
    }
    /** Discard a partial operation without publishing its staged bytes. */
    @Synchronized fun abort() { failed = true; close() }
}
