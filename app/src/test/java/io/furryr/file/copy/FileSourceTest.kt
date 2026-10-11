package io.furryr.file.copy

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class FileSourceTest {

    @Test
    fun `stream source reads sequential chunks when size is unavailable`() {
        val bytes = ByteArray(1025) { index -> (index % 251).toByte() }
        val source = StreamFileSource(ByteArrayInputStream(bytes), size = 0)
        val copied = ByteArrayOutputStream()
        var offset = 0L

        while (true) {
            val chunk = source.readChunk(offset, 128)
            if (chunk.isEmpty()) break
            copied.write(chunk)
            offset += chunk.size
        }

        source.close()
        assertArrayEquals(bytes, copied.toByteArray())
    }

    @Test
    fun `fd source reads sequential chunks`() {
        val bytes = ByteArray(1025) { index -> (index % 251).toByte() }
        val file = File.createTempFile("file-source", ".bin")
        file.writeBytes(bytes)
        val input = FileInputStream(file)
        val source = FdFileSource(input.fd, bytes.size.toLong(), null, OpenMode.READ)
        val copied = ByteArrayOutputStream()
        var offset = 0L

        while (true) {
            val chunk = source.readChunk(offset, 128)
            if (chunk.isEmpty()) break
            copied.write(chunk)
            offset += chunk.size
        }

        source.close()
        file.delete()
        assertArrayEquals(bytes, copied.toByteArray())
    }
}
