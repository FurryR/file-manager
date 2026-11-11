package io.furryr.file.provider.web

import android.webkit.CookieManager
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.util.Locale

/**
 * HTTP plumbing shared by every request the app makes for a remote resource.
 *
 * Links are followed from inside a WebView, so the bytes behind them often
 * depend on the page's session: the `Range` probes and the fd handed to an
 * external viewer must carry the same cookies and referer as the page, or a
 * logged-in download answers 401/403.
 */
internal object WebRequest {
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android) file-manager"

    fun headers(url: String, referer: String?): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        // CookieManager needs a WebView provider; a plain URL open (home page
        // entry, agent) still works when it is unavailable.
        runCatching { CookieManager.getInstance().getCookie(url) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { headers["Cookie"] = it }
        if (!referer.isNullOrBlank() && referer != url) headers["Referer"] = referer
        return headers
    }

    fun connection(
        url: String,
        method: String,
        headers: Map<String, String>,
        range: String? = null,
    ): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.instanceFollowRedirects = true
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.setRequestProperty("User-Agent", USER_AGENT)
        // No transparent gzip: ranged reads and byte offsets must match the
        // resource length the server reported.
        connection.setRequestProperty("Accept-Encoding", "identity")
        for ((name, value) in headers) connection.setRequestProperty(name, value)
        if (range != null) connection.setRequestProperty("Range", range)
        return connection
    }

    fun normalizeMime(raw: String?): String? =
        raw?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }

    /** `filename`/`filename*` from a Content-Disposition header, if any. */
    fun fileName(disposition: String?): String? {
        if (disposition.isNullOrBlank()) return null
        val extended = Regex("filename\\*\\s*=\\s*([^']*)''([^;]+)", RegexOption.IGNORE_CASE)
            .find(disposition)
        if (extended != null) {
            val decoded = runCatching {
                URLDecoder.decode(extended.groupValues[2].trim().trim('"'), "UTF-8")
            }.getOrNull()
            if (!decoded.isNullOrBlank()) return safeName(decoded)
        }
        val plain = Regex("filename\\s*=\\s*\"?([^\";]+)\"?", RegexOption.IGNORE_CASE)
            .find(disposition)
            ?.groupValues?.get(1)
            ?.trim()
            ?.trim('"')
        return plain?.takeIf { it.isNotBlank() }?.let(::safeName)
    }

    /** Last path segment of a URL, used when the server names no file. */
    fun displayName(url: String): String {
        val fromPath = runCatching {
            URI(url).path.orEmpty().trimEnd('/').substringAfterLast('/')
        }.getOrNull().orEmpty()
        if (fromPath.isNotBlank()) return safeName(fromPath)
        return runCatching { URI(url).host }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: url
    }

    /** A response header must never turn into a path traversal. */
    fun safeName(name: String): String =
        name.substringAfterLast('/').substringAfterLast('\\').ifBlank { "download" }
}
