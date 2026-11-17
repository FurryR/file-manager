package io.furryr.file.devtools

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.Uri
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Random

private const val TAG = "DevToolsTargets"
private const val SOCKET_PREFIX = "webview_devtools_remote_"

/** One inspectable page target exposed by the WebView DevTools server. */
internal data class DevToolsTarget(
    val id: String,
    val url: String,
    val title: String,
)

/**
 * Finds and opens the abstract Unix socket the Android WebView debug server
 * exposes. The socket speaks plain HTTP, so the same connection is reused for
 * `/json/list` and for the raw WebSocket upgrade `DevToolsServer` relays.
 */
internal object DevToolsTargets {
    private val random = Random()

    fun openSocket(): LocalSocket {
        val socket = LocalSocket()
        try {
            socket.connect(
                LocalSocketAddress(
                    "$SOCKET_PREFIX${android.os.Process.myPid()}",
                    LocalSocketAddress.Namespace.ABSTRACT,
                ),
            )
            socket.setSoTimeout(0)
            return socket
        } catch (error: Throwable) {
            runCatching { socket.close() }
            throw IOException("WebView remote debugging socket is not available", error)
        }
    }

    /**
     * Resolve the target that belongs to [pageUrl] (or [pageTitle] when the URL
     * is ambiguous). A marker injected into the page's main world breaks ties
     * when several WebViews show the same address.
     */
    fun findTarget(
        pageUrl: String?,
        pageTitle: String?,
        marker: String,
    ): DevToolsTarget {
        var lastError: Throwable? = null
        repeat(50) { attempt ->
            val targets = try {
                listTargets()
            } catch (error: Throwable) {
                lastError = error
                Thread.sleep(100)
                return@repeat
            }
            val pages = targets.filter { target ->
                !target.url.startsWith("devtools://") &&
                    !target.url.startsWith("chrome://") &&
                    !isDevToolsFrontend(target.url)
            }
            if (pages.isEmpty()) {
                Thread.sleep(100)
                return@repeat
            }

            val exact = pageUrl?.takeIf { it.isNotBlank() }?.let { url ->
                val normalized = url.substringBefore('#').trimEnd('/')
                pages.filter { it.url.substringBefore('#').trimEnd('/') == normalized }
            }.orEmpty()
            if (exact.size == 1) return exact.first()

            val titled = pageTitle?.trim()?.removePrefix("• ")?.takeIf { it.isNotBlank() }?.let { title ->
                (exact.ifEmpty { pages }).filter { it.title.trim() == title }
            }.orEmpty()
            if (titled.size == 1) return titled.first()

            val ambiguous = when {
                exact.isNotEmpty() -> exact
                pages.size == 1 -> return pages.first()
                else -> pages
            }
            if (ambiguous.size == 1) return ambiguous.first()

            ambiguous.firstOrNull { target -> probeMarker(target, marker) }
                ?.let { return it }
            if (attempt >= 4) {
                // No marker match and no unique address/title: prefer a real
                // page over the WebView that hosts the DevTools frontend.
                ambiguous.firstOrNull { !it.url.startsWith("about:") }
                    ?.let { return it }
            }
            Thread.sleep(100)
        }
        throw lastError ?: IOException("no WebView page target found")
    }

    private fun listTargets(): List<DevToolsTarget> {
        val (status, body) = request("/json/list")
        if (status !in 200..299) {
            throw IOException("devtools target list returned HTTP $status")
        }
        val array = JSONArray(body.toString(Charsets.UTF_8))
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val type = item.optString("type")
                if (type != "page" && type != "webview") continue
                val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                add(
                    DevToolsTarget(
                        id = id,
                        url = item.optString("url"),
                        title = item.optString("title"),
                    ),
                )
            }
        }
    }

    private fun request(path: String): Pair<Int, ByteArray> {
        val socket = openSocket()
        socket.use {
            socket.setSoTimeout(4_000)
            val request = "GET $path HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
            socket.outputStream.write(request.toByteArray(Charsets.ISO_8859_1))
            socket.outputStream.flush()
            val input = socket.inputStream
            val block = readHeaderBlock(input) ?: throw IOException("empty devtools response")
            val head = parseResponseHead(block) ?: throw IOException("bad devtools response")
            return head.status to readResponseBody(input, head)
        }
    }

    private fun isDevToolsFrontend(url: String): Boolean {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val path = uri.path ?: return false
        return uri.host == "127.0.0.1" &&
            path.startsWith("/devtools/") &&
            (path.endsWith("/inspector.html") || path.endsWith("/device_mode_emulation_frame.html"))
    }

    /**
     * Evaluate the marker through a one-shot CDP WebSocket. This is only used
     * when more than one target matches; the normal path never opens a second
     * connection.
     */
    private fun probeMarker(target: DevToolsTarget, marker: String): Boolean {
        val socket = openSocket()
        return socket.use {
            try {
                socket.setSoTimeout(2_500)
                val key = ByteArray(16).also(random::nextBytes)
                val keyText = Base64.encodeToString(key, Base64.NO_WRAP)
                val request = buildString {
                    append("GET /devtools/page/${target.id} HTTP/1.1\r\n")
                    append("Host: localhost\r\n")
                    append("Upgrade: websocket\r\n")
                    append("Connection: Upgrade\r\n")
                    append("Sec-WebSocket-Key: $keyText\r\n")
                    append("Sec-WebSocket-Version: 13\r\n\r\n")
                }
                socket.outputStream.write(request.toByteArray(Charsets.ISO_8859_1))
                socket.outputStream.flush()
                val input = socket.inputStream
                val block = readHeaderBlock(input) ?: return@use false
                if (parseResponseHead(block)?.status != 101) return@use false
                val expression = "window.__fileManagerDevToolsMarker"
                val message = JSONObject()
                    .put("id", 1)
                    .put("method", "Runtime.evaluate")
                    .put("params", JSONObject().put("expression", expression).put("returnByValue", true))
                    .toString()
                writeFrame(socket.outputStream, 0x1, message.toByteArray(Charsets.UTF_8))
                repeat(20) {
                    val frame = readFrame(input) ?: return@use false
                    if (frame.opcode == 9) {
                        writeFrame(socket.outputStream, 0xA, frame.payload)
                        return@repeat
                    }
                    if (frame.opcode != 1) return@repeat
                    val response = runCatching { JSONObject(frame.payload.toString(Charsets.UTF_8)) }
                        .getOrNull() ?: return@repeat
                    if (response.optInt("id", -1) != 1) return@repeat
                    val value = response.optJSONObject("result")
                        ?.optJSONObject("result")
                        ?.optString("value")
                    return@use value == marker
                }
                false
            } catch (error: Throwable) {
                Log.w(TAG, "target probe failed", error)
                false
            }
        }
    }

    private data class Frame(val opcode: Int, val payload: ByteArray)

    private fun writeFrame(output: OutputStream, opcode: Int, payload: ByteArray) {
        output.write(0x80 or (opcode and 0x0F))
        when {
            payload.size < 126 -> output.write(0x80 or payload.size)
            payload.size <= 0xFFFF -> {
                output.write(0x80 or 126)
                output.write((payload.size ushr 8) and 0xFF)
                output.write(payload.size and 0xFF)
            }
            else -> {
                output.write(0x80 or 127)
                for (shift in 56 downTo 0 step 8) {
                    output.write(((payload.size.toLong() ushr shift) and 0xFF).toInt())
                }
            }
        }
        val mask = ByteArray(4).also(random::nextBytes)
        output.write(mask)
        for (index in payload.indices) {
            output.write(payload[index].toInt() xor mask[index % 4].toInt())
        }
        output.flush()
    }

    private fun readFrame(input: InputStream): Frame? {
        val first = input.read()
        val second = input.read()
        if (first < 0 || second < 0) return null
        val opcode = first and 0x0F
        val shortLength = second and 0x7F
        val length = when (shortLength) {
            126 -> (input.read() shl 8) or input.read()
            127 -> {
                var value = 0L
                repeat(8) { value = (value shl 8) or input.read().toLong() }
                if (value > Int.MAX_VALUE) return null
                value.toInt()
            }
            else -> shortLength
        }
        if (length < 0) return null
        return Frame(opcode, readExact(input, length))
    }

}
