package io.furryr.file.editor

import io.furryr.file.FileRepository
import io.furryr.file.core.source.OpenMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** Text decoding is strict: opening a binary or malformed file never silently replaces bytes. */
internal data class TextDocument(
    val text: String,
    val charset: Charset,
    val bom: ByteArray,
    val lineEnding: String,
    val mixedLineEndings: Boolean,
    val originalBytes: ByteArray,
) {
    val digest: ByteArray get() = MessageDigest.getInstance("SHA-256").digest(originalBytes)
    val formatLabel: String get() = charset.name() + (if (bom.isNotEmpty()) " BOM" else "") + " · " +
        (if (lineEnding == "\r\n") "CRLF" else if (lineEnding == "\r") "CR" else "LF") +
        (if (mixedLineEndings) " (mixed)" else "")

    fun encode(edited: String): ByteArray {
        if (edited == text) return originalBytes
        val normalized = edited.replace("\r\n", "\n").replace('\r', '\n')
        val external = if (lineEnding == "\n") normalized else normalized.replace("\n", lineEnding)
        val encoded = charset.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(external))
        return bom + ByteArray(encoded.remaining()).also { encoded.get(it) }
    }

    companion object {
        fun decode(bytes: ByteArray): TextDocument {
            val (charset, prefix) = when {
                bytes.take(3) == listOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) -> Charsets.UTF_8 to 3
                bytes.take(2) == listOf(0xFF.toByte(), 0xFE.toByte()) -> Charsets.UTF_16LE to 2
                bytes.take(2) == listOf(0xFE.toByte(), 0xFF.toByte()) -> Charsets.UTF_16BE to 2
                else -> Charsets.UTF_8 to 0
            }
            val raw = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, prefix, bytes.size - prefix)).toString()
            require('\u0000' !in raw) { "This file contains binary data and cannot be edited as text." }
            val crlf = Regex("\r\n").findAll(raw).count()
            val lf = raw.count { it == '\n' } - crlf
            val cr = raw.count { it == '\r' } - crlf
            val ending = if (crlf > 0 && crlf >= lf && crlf >= cr) "\r\n" else if (cr > lf) "\r" else "\n"
            return TextDocument(raw.replace("\r\n", "\n").replace('\r', '\n'), charset,
                bytes.copyOfRange(0, prefix), ending, listOf(crlf, lf, cr).count { it > 0 } > 1, bytes)
        }
    }
}

internal object EditorFiles {
    suspend fun read(path: String): ByteArray = withContext(Dispatchers.IO) {
        val resolved = FileRepository.resolve(path).getOrThrow()
        resolved.provider.open(resolved.innerPath, OpenMode.READ).getOrThrow().use { source ->
            val bytes = ByteArrayOutputStream()
            var offset = 0L
            while (true) {
                coroutineContext.ensureActive()
                val chunk = source.readAt(offset, 64 * 1024)
                if (chunk.isEmpty()) break
                bytes.write(chunk)
                offset += chunk.size
            }
            bytes.toByteArray()
        }
    }

    suspend fun isUnchanged(path: String, expected: ByteArray): Boolean =
        MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(read(path)), expected)
}
