package io.furryr.file.userscript

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * The one JavaScript object exposed to the page. Every method checks the
 * per-WebView token, and the only network path is `GM_xmlhttpRequest` gated by
 * the script's `@connect` list. No VFS, daemon or intent API is reachable here.
 */
internal class UserScriptBridge(
    private val context: Context,
    private val token: String,
    private val view: WebView,
    private val onOpenUrl: (String) -> Unit,
) {
    @Volatile
    private var released = false

    private val connections = ConcurrentHashMap<Long, HttpURLConnection>()

    fun release() {
        released = true
        connections.values.forEach { runCatching { it.disconnect() } }
        connections.clear()
    }

    private fun allowed(candidate: String?): Boolean = !released && candidate == token

    @JavascriptInterface
    fun getScripts(url: String, value: String): String {
        if (!allowed(value)) return "{\"scripts\":[]}"
        val scripts = UserScriptEngine.scriptsFor(url)
        return JSONObject()
            .put("scripts", JSONArray().apply { scripts.forEach { put(it.payload()) } })
            .toString()
    }

    @JavascriptInterface
    fun getValue(value: String, scriptId: String, key: String, fallback: String): String {
        if (!allowed(value)) return fallback
        return UserScriptEngine.value(context, scriptId, key) ?: fallback
    }

    @JavascriptInterface
    fun setValue(value: String, scriptId: String, key: String, stored: String) {
        if (!allowed(value)) return
        UserScriptEngine.setValue(context, scriptId, key, stored)
    }

    @JavascriptInterface
    fun deleteValue(value: String, scriptId: String, key: String) {
        if (!allowed(value)) return
        UserScriptEngine.deleteValue(context, scriptId, key)
    }

    @JavascriptInterface
    fun listValues(value: String, scriptId: String): String {
        if (!allowed(value)) return "{}"
        val entries = UserScriptEngine.values(context, scriptId)
        return buildString {
            append('{')
            entries.entries.forEachIndexed { index, (key, stored) ->
                if (index > 0) append(',')
                append(JSONObject.quote(key)).append(':').append(stored)
            }
            append('}')
        }
    }

    @JavascriptInterface
    fun log(value: String, scriptId: String, message: String) {
        if (!allowed(value)) return
        Log.i("UserScript", "[$scriptId] $message")
    }

    @JavascriptInterface
    fun xhr(value: String, scriptId: String, requestJson: String): Int {
        if (!allowed(value)) return 0
        val request = runCatching { JSONObject(requestJson) }.getOrNull() ?: return 0
        val id = request.optLong("id", 0L)
        if (id <= 0) return 0
        val script = UserScriptEngine.source(scriptId) ?: return 0
        val url = request.optString("url")
        if (!UserScriptPattern.isHttpUrl(url)) {
            dispatch(errorPayload(id, "invalid URL"))
            return id.toInt()
        }
        val page = request.optString("page").takeIf { it.isNotBlank() }
        if (!script.connectAllowed(url, page)) {
            dispatch(errorPayload(id, "blocked by @connect"))
            return id.toInt()
        }
        Thread({ performXhr(id, request, script, page) }, "userscript-xhr").start()
        return id.toInt()
    }

    @JavascriptInterface
    fun abort(value: String, requestId: Long) {
        if (!allowed(value)) return
        connections.remove(requestId)?.let { runCatching { it.disconnect() } }
    }

    @JavascriptInterface
    fun clipboard(value: String, text: String) {
        if (!allowed(value)) return
        view.post {
            if (released) return@post
            val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return@post
            runCatching {
                manager.setPrimaryClip(ClipData.newPlainText("userscript", text))
            }
        }
    }

    @JavascriptInterface
    fun notify(value: String, title: String, text: String) {
        if (!allowed(value)) return
        view.post {
            if (released) return@post
            val message = if (title.isBlank()) text else "$title: $text"
            runCatching { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
        }
    }

    @JavascriptInterface
    fun openUrl(value: String, url: String) {
        if (!allowed(value)) return
        if (!UserScriptPattern.isHttpUrl(url)) return
        view.post {
            if (released) return@post
            onOpenUrl(url)
        }
    }

    @JavascriptInterface
    fun resourceText(value: String, scriptId: String, name: String): String? {
        if (!allowed(value)) return null
        val resource = UserScriptEngine.resource(context, scriptId, name) ?: return null
        return resource.body.toString(Charsets.UTF_8)
    }

    @JavascriptInterface
    fun resourceUrl(value: String, scriptId: String, name: String): String? {
        if (!allowed(value)) return null
        val resource = UserScriptEngine.resource(context, scriptId, name) ?: return null
        val mime = URLConnectionMime.guess(name)
        val encoded = Base64.encodeToString(resource.body, Base64.NO_WRAP)
        return "data:$mime;base64,$encoded"
    }

    private fun performXhr(
        id: Long,
        request: JSONObject,
        script: UserScriptSource,
        page: String?,
    ) {
        var connection: HttpURLConnection? = null
        try {
            val method = request.optString("method", "GET").ifBlank { "GET" }.uppercase(Locale.ROOT)
            val data = request.optString("data", "").takeIf {
                request.has("data") && !request.isNull("data")
            }
            var current = request.optString("url")
            repeat(MAX_REDIRECTS + 1) {
                val open = openConnection(current, request, method)
                connection = open
                connections[id] = open
                if (data != null && method != "GET" && method != "HEAD") {
                    open.doOutput = true
                    open.outputStream.use { it.write(data.toByteArray(Charsets.UTF_8)) }
                }
                val status = open.responseCode
                if (status in 300..399) {
                    val location = open.getHeaderField("Location")
                    val next = location?.takeIf { it.isNotBlank() }?.let {
                        runCatching { URL(open.url, it).toString() }.getOrNull()
                    }
                    if (next != null) {
                        require(script.connectAllowed(next, page)) { "blocked by @connect" }
                        connections.remove(id)
                        open.disconnect()
                        connection = null
                        current = next
                        return@repeat
                    }
                }
                val stream = if (status >= 400) open.errorStream else open.inputStream
                val body = stream?.let { input ->
                    val decoded = if (open.contentEncoding?.contains("gzip", true) == true) {
                        java.util.zip.GZIPInputStream(input)
                    } else {
                        input
                    }
                    decoded.use { readLimited(it) }
                } ?: ByteArray(0)
                val responseType = request.optString("responseType", "text").lowercase(Locale.ROOT)
                val payload = JSONObject()
                    .put("id", id)
                    .put("ok", true)
                    .put("status", status)
                    .put("statusText", open.responseMessage.orEmpty())
                    .put("finalUrl", open.url.toString())
                    .put("headers", responseHeaders(open))
                if (responseType == "arraybuffer") {
                    payload.put("base64", Base64.encodeToString(body, Base64.NO_WRAP))
                } else {
                    payload.put("text", body.toString(Charsets.UTF_8))
                }
                dispatch(payload)
                return
            }
            error("too many redirects")
        } catch (error: Exception) {
            if (!released) {
                dispatch(errorPayload(id, error.message ?: error.toString()))
            }
        } finally {
            connections.remove(id)
            runCatching { connection?.disconnect() }
        }
    }

    private fun openConnection(
        url: String,
        request: JSONObject,
        method: String,
    ): HttpURLConnection {
        val timeout = request.optInt("timeout", 0)
        return (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            useCaches = false
            requestMethod = method
            connectTimeout = if (timeout > 0) timeout else 30_000
            readTimeout = if (timeout > 0) timeout else 60_000
            setRequestProperty("User-Agent", "File/1.0 (Android) Userscripts")
            setRequestProperty("Accept", "*/*")
            request.optJSONObject("headers")?.let { headers ->
                for (key in headers.keys()) {
                    val headerValue = headers.optString(key)
                    if (headerValue.isNotEmpty()) setRequestProperty(key, headerValue)
                }
            }
        }
    }

    private fun responseHeaders(connection: HttpURLConnection): String = buildString {
        append("HTTP/1.1 ").append(connection.responseCode).append(' ')
            .append(connection.responseMessage.orEmpty()).append("\r\n")
        for ((name, values) in connection.headerFields) {
            if (name == null) continue
            for (value in values) append(name).append(": ").append(value).append("\r\n")
        }
    }

    private fun readLimited(stream: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            require(total <= MAX_RESPONSE_BYTES) { "response is too large" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun errorPayload(id: Long, message: String): JSONObject = JSONObject()
        .put("id", id)
        .put("ok", false)
        .put("error", message)

    private fun dispatch(payload: JSONObject) {
        val json = payload.toString()
        view.post {
            if (released) return@post
            runCatching {
                view.evaluateJavascript(
                    "window.$DISPATCH_NAME(${JSONObject.quote(json)})",
                    null,
                )
            }
        }
    }

    private object URLConnectionMime {
        fun guess(name: String): String =
            java.net.URLConnection.guessContentTypeFromName(name) ?: "application/octet-stream"
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 16 * 1024 * 1024
        const val MAX_REDIRECTS = 5
        const val DISPATCH_NAME = "__fileUserScriptDispatch"
    }
}
