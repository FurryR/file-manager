package io.furryr.file.render

import io.furryr.file.core.source.FileSource
import java.io.InputStream

/** Sequential InputStream adapter over a positional [FileSource]. */
class FileSourceInputStream(
    private val source: FileSource,
) : InputStream() {
    private var position = 0L
    private var closed = false
    private val single = ByteArray(1)

    override fun read(): Int {
        val count = read(single, 0, 1)
        return if (count <= 0) -1 else single[0].toInt() and 0xFF
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        check(!closed) { "stream closed" }
        if (length == 0) return 0
        val chunk = source.readChunk(position, length)
        if (chunk.isEmpty()) return -1
        System.arraycopy(chunk, 0, buffer, offset, chunk.size)
        position += chunk.size
        return chunk.size
    }

    override fun available(): Int = (source.size - position).coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    override fun close() {
        if (closed) return
        closed = true
        source.close()
    }
}
