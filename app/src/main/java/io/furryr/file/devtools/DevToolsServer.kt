package io.furryr.file.devtools

import android.content.Context
import android.content.res.AssetManager
import android.net.Uri
import android.util.Log
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "DevToolsServer"
private const val ASSET_ROOT = "devtools"
private const val LOCALE_ROOT = "$ASSET_ROOT/core/i18n/locales"
private const val INSPECTOR_PAGE = "inspector.html"
private const val SETTINGS_SCRIPT = "__settings.js"
private const val MODULE_SCRIPT = "<script type=\"module\""

/**
 * Pick the DevTools locale closest to [locale] from the locale data the asset
 * sync staged. `en-US` ships with the frontend and the sync adds one file per
 * app language, so a new app locale is matched here without a code change.
 */
private fun matchLocale(assets: AssetManager, locale: Locale): String {
    val staged = runCatching {
        assets.list(LOCALE_ROOT).orEmpty()
            .filter { it.endsWith(".json") }
            .map { it.removeSuffix(".json") }
    }.getOrNull().orEmpty()
    val supported = staged.ifEmpty { listOf("en-US") }
    val requested = runCatching {
        Locale.lookupTag(
            listOf(Locale.LanguageRange(locale.stripExtensions().toLanguageTag())),
            supported,
        )
    }.getOrNull()
    return requested ?: "en-US"
}

/**
 * The loopback HTTP server behind one DevTools frontend.
 *
 * It serves the generated official frontend from assets and proxies the CDP
 * WebSocket to the WebView's abstract remote-debugging socket. Every request
 * carries an unguessable token because Android shares `127.0.0.1` between
 * applications: the port alone must not be enough to reach the inspector.
 *
 * The server also owns the frontend's appearance: `inspector.html` is served
 * with a small same-origin bootstrap script that writes the DevTools
 * `ui-theme` and `language` settings before the frontend module runs, so the
 * tab never starts in English/light and then flips. `ui-theme` is a synced
 * DevTools setting (light is `default`). The language comes from the app
 * locale and the locale files the asset sync staged, so adding an app
 * language needs no code change here.
 */
internal class DevToolsServer(
    context: Context,
    initialDarkTheme: Boolean,
) {
    private val assets: AssetManager = context.applicationContext.assets
    private val language =
        matchLocale(assets, context.resources.configuration.locales[0])
    private val token = UUID.randomUUID().toString().replace("-", "")
    /** Latest app theme; live changes update the served bootstrap script. */
    @Volatile
    var darkTheme: Boolean = initialDarkTheme
    private val serverSocket = ServerSocket(
        0,
        50,
        InetAddress.getByName("127.0.0.1"),
    )
    private val running = AtomicBoolean(true)
    private val clients = ConcurrentHashMap.newKeySet<Socket>()

    private val authority = "127.0.0.1:${serverSocket.localPort}"
    private val basePath = "/devtools/$token/"
    val baseUrl: String = "http://$authority$basePath"

    private val acceptThread = Thread({ acceptLoop() }, "devtools-http").apply {
        isDaemon = true
        start()
    }

    fun close() {
        if (!running.compareAndSet(true, false)) return
        runCatching { serverSocket.close() }
        clients.forEach { runCatching { it.close() } }
        clients.clear()
        acceptThread.interrupt()
    }

    fun inspectorUrl(targetId: String): String =
        "${baseUrl}inspector.html?ws=$authority${basePath}cdp/devtools/page/$targetId"

    private fun acceptLoop() {
        while (running.get()) {
            val client = try {
                serverSocket.accept()
            } catch (error: Throwable) {
                if (running.get()) Log.w(TAG, "accept failed", error)
                break
            }
            clients += client
            val worker = Thread({
                try {
                    client.use { handleClient(it) }
                } catch (error: Throwable) {
                    if (running.get()) Log.w(TAG, "client failed", error)
                } finally {
                    clients -= client
                }
            }, "devtools-client")
            worker.isDaemon = true
            worker.start()
        }
    }

    private fun handleClient(client: Socket) {
        client.tcpNoDelay = true
        client.soTimeout = 15_000
        val input = client.getInputStream()
        val output = client.getOutputStream()
        val block = readHeaderBlock(input) ?: return
        val request = parseRequest(block) ?: return
        val path = request.path
        if (!path.startsWith(basePath)) {
            writeResponse(output, 404, "Not Found", "text/plain; charset=utf-8", "not found".toByteArray())
            return
        }
        val rest = path.removePrefix(basePath)
        if (rest.startsWith("cdp/") && request.header("Upgrade")?.equals("websocket", true) == true) {
            relayCdp(client, request, rest)
            return
        }
        if (request.method != "GET" && request.method != "HEAD") {
            writeResponse(output, 405, "Method Not Allowed", "text/plain; charset=utf-8", "method not allowed".toByteArray())
            return
        }
        serveAsset(output, request, rest)
    }

    private fun serveAsset(output: OutputStream, request: HttpRequest, rest: String) {
        val decoded = runCatching {
            Uri.decode(rest.substringBefore('?')).replace('\\', '/')
        }.getOrElse {
            writeResponse(output, 400, "Bad Request", "text/plain; charset=utf-8", "bad path".toByteArray())
            return
        }.ifEmpty { "inspector.html" }
        val segments = decoded.split('/')
        if (decoded.startsWith('/') || segments.any { it == ".." || it == "." || it.contains('\u0000') }) {
            writeResponse(output, 400, "Bad Request", "text/plain; charset=utf-8", "bad path".toByteArray())
            return
        }
        if (decoded == SETTINGS_SCRIPT) {
            val script = settingsScript()
            val body = if (request.method == "HEAD") ByteArray(0) else script
            writeResponse(
                output,
                200,
                "OK",
                "text/javascript; charset=utf-8",
                body,
                contentLength = script.size,
            )
            return
        }
        val assetName = "$ASSET_ROOT/$decoded"
        val bytes = try {
            val raw = assets.open(assetName).use { it.readBytes() }
            if (decoded == INSPECTOR_PAGE) injectBootstrap(raw) else raw
        } catch (_: IOException) {
            writeResponse(output, 404, "Not Found", "text/plain; charset=utf-8", "not found".toByteArray())
            return
        }
        val mime = mimeType(decoded)
        val body = if (request.method == "HEAD") ByteArray(0) else bytes
        writeResponse(output, 200, "OK", mime, body, contentLength = bytes.size)
    }

    /** Load the app settings before the deferred DevTools module executes. */
    private fun injectBootstrap(html: ByteArray): ByteArray {
        val text = html.toString(Charsets.UTF_8)
        val index = text.indexOf(MODULE_SCRIPT)
        if (index < 0) return html
        val tag = "<script src=\"$SETTINGS_SCRIPT\"></script>"
        return (text.substring(0, index) + tag + text.substring(index))
            .toByteArray(Charsets.UTF_8)
    }

    /**
     * DevTools persists settings through `localStorage` with plain setting
     * names and JSON-encoded values. Writing them here means the frontend's
     * own `getPreferences()` load sees the app language and theme.
     */
    private fun settingsScript(): ByteArray {
        val dark = darkTheme
        val theme = if (dark) "dark" else "default"
        val scheme = if (dark) "dark" else "light"
        val language = this.language
            .filter { it.isLetterOrDigit() || it == '-' || it == '_' }
            .ifEmpty { "en-US" }
        return """
            (function () {
              try {
                var root = document.documentElement;
                if (root) {
                  root.classList.toggle('theme-with-dark-background', $dark);
                  root.style.colorScheme = '$scheme';
                }
                localStorage.setItem('ui-theme', JSON.stringify('$theme'));
                localStorage.setItem('language', JSON.stringify('$language'));
                localStorage.setItem('disable-locale-info-bar', JSON.stringify(true));
              } catch (e) {}
            })();
        """.trimIndent().toByteArray(Charsets.UTF_8)
    }

    private fun relayCdp(client: Socket, request: HttpRequest, rest: String) {
        val upstream = try {
            DevToolsTargets.openSocket()
        } catch (error: Throwable) {
            Log.w(TAG, "cannot open devtools socket", error)
            writeResponse(
                client.getOutputStream(),
                502,
                "Bad Gateway",
                "text/plain; charset=utf-8",
                "devtools socket unavailable".toByteArray(),
            )
            return
        }
        try {
            upstream.setSoTimeout(15_000)
            val upstreamPath = "/" + rest.removePrefix("cdp").trimStart('/')
            val header = buildString {
                append("${request.method} $upstreamPath ${request.version}\r\n")
                for ((name, value) in request.headers) {
                    // The WebView DevTools server only accepts its own origins.
                    // A hosted localhost frontend is not one of them; native
                    // clients omit Origin entirely, so drop it here.
                    if (name.equals("Host", ignoreCase = true) ||
                        name.equals("Origin", ignoreCase = true)
                    ) {
                        continue
                    }
                    append("$name: $value\r\n")
                }
                append("Host: localhost\r\n\r\n")
            }
            upstream.outputStream.write(header.toByteArray(Charsets.ISO_8859_1))
            upstream.outputStream.flush()

            val upstreamInput = upstream.inputStream
            val responseBlock = readHeaderBlock(upstreamInput)
            if (responseBlock == null) {
                writeResponse(
                    client.getOutputStream(),
                    502,
                    "Bad Gateway",
                    "text/plain; charset=utf-8",
                    "empty devtools response".toByteArray(),
                )
                return
            }
            val head = parseResponseHead(responseBlock)
            client.getOutputStream().write(responseBlock.toByteArray(Charsets.ISO_8859_1))
            client.getOutputStream().flush()
            if (head?.status != 101) {
                upstream.close()
                return
            }

            client.soTimeout = 0
            upstream.setSoTimeout(0)
            val closed = AtomicBoolean(false)
            fun closeBoth() {
                if (!closed.compareAndSet(false, true)) return
                runCatching { upstream.close() }
                runCatching { client.close() }
            }
            val clientInput = client.getInputStream()
            val clientOutput = client.getOutputStream()
            val upstreamOutput = upstream.outputStream
            val up = Thread({
                try {
                    clientInput.copyTo(upstreamOutput, 32 * 1024)
                } catch (_: Throwable) {
                } finally {
                    closeBoth()
                }
            }, "devtools-cdp-up")
            val down = Thread({
                try {
                    upstreamInput.copyTo(clientOutput, 32 * 1024)
                } catch (_: Throwable) {
                } finally {
                    closeBoth()
                }
            }, "devtools-cdp-down")
            up.isDaemon = true
            down.isDaemon = true
            up.start()
            down.start()
            up.join()
            down.join()
        } catch (error: Throwable) {
            Log.w(TAG, "cdp relay failed", error)
            runCatching { upstream.close() }
        }
    }

    private fun writeResponse(
        output: OutputStream,
        status: Int,
        reason: String,
        contentType: String,
        body: ByteArray,
        contentLength: Int = body.size,
    ) {
        val header = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: $contentLength\r\n")
            append("Cache-Control: no-cache\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(header.toByteArray(Charsets.ISO_8859_1))
        if (body.isNotEmpty()) output.write(body)
        output.flush()
    }

    private fun mimeType(path: String): String {
        return when (path.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "html" -> "text/html; charset=utf-8"
            "js" -> "text/javascript; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "json" -> "application/json; charset=utf-8"
            "md" -> "text/markdown; charset=utf-8"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "avif" -> "image/avif"
            else -> "application/octet-stream"
        }
    }
}
