package io.furryr.file.devtools

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** One HTTP request line plus its headers; CDP only ever sends GET. */
internal data class HttpRequest(
    val method: String,
    val path: String,
    val version: String,
    val headers: List<Pair<String, String>>,
) {
    fun header(name: String): String? =
        headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
}

/** The status and body length of an HTTP response head. */
internal data class HttpResponseHead(
    val status: Int,
    val contentLength: Int?,
)

/**
 * Read one header block without consuming bytes past the terminating empty
 * line. The WebSocket bytes that follow a handshake stay in the socket for the
 * relay loop, so buffering here would drop them.
 */
internal fun readHeaderBlock(input: InputStream): String? {
    val bytes = ByteArrayOutputStream()
    var matched = 0
    while (bytes.size() < 64 * 1024) {
        val next = input.read()
        if (next < 0) {
            return if (bytes.size() == 0) null else bytes.toString(Charsets.ISO_8859_1.name())
        }
        bytes.write(next)
        matched = when {
            (matched == 0 || matched == 2) && next == '\r'.code -> matched + 1
            (matched == 1 || matched == 3) && next == '\n'.code -> matched + 1
            next == '\r'.code -> 1
            else -> 0
        }
        if (matched == 4) return bytes.toString(Charsets.ISO_8859_1.name())
    }
    return null
}

internal fun parseRequest(block: String): HttpRequest? {
    val lines = block.removeSuffix("\r\n\r\n").split("\r\n")
    val requestLine = lines.firstOrNull()?.split(' ', limit = 3) ?: return null
    if (requestLine.size < 2) return null
    return HttpRequest(
        method = requestLine[0],
        path = requestLine[1],
        version = requestLine.getOrElse(2) { "HTTP/1.1" },
        headers = parseHeaders(lines.drop(1)),
    )
}

internal fun parseResponseHead(block: String): HttpResponseHead? {
    val lines = block.removeSuffix("\r\n\r\n").split("\r\n")
    val status = lines.firstOrNull()?.split(' ', limit = 3)?.getOrNull(1)?.toIntOrNull()
        ?: return null
    val contentLength = parseHeaders(lines.drop(1))
        .firstOrNull { it.first.equals("Content-Length", ignoreCase = true) }
        ?.second
        ?.trim()
        ?.toIntOrNull()
    return HttpResponseHead(status, contentLength)
}

private fun parseHeaders(lines: List<String>): List<Pair<String, String>> = lines.mapNotNull { line ->
    val colon = line.indexOf(':')
    if (colon <= 0) return@mapNotNull null
    line.substring(0, colon).trim() to line.substring(colon + 1).trim()
}

internal fun readResponseBody(input: InputStream, head: HttpResponseHead): ByteArray {
    if (head.status == 101 || head.status == 204 || head.status == 304) return ByteArray(0)
    val length = head.contentLength
    if (length != null) return readExact(input, length)
    return readUntilEof(input)
}

internal fun readExact(input: InputStream, length: Int): ByteArray {
    if (length <= 0) return ByteArray(0)
    val bytes = ByteArray(length)
    var offset = 0
    while (offset < length) {
        val read = input.read(bytes, offset, length - offset)
        if (read < 0) break
        offset += read
    }
    return if (offset == bytes.size) bytes else bytes.copyOf(offset)
}

private fun readUntilEof(input: InputStream, limit: Int = 4 * 1024 * 1024): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (out.size() < limit) {
        val read = input.read(buffer)
        if (read < 0) break
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

