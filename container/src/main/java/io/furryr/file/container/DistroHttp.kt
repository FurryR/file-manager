package io.furryr.file.container

import java.net.HttpURLConnection
import java.net.URL

internal object DistroHttp {
    fun openConnection(url: URL, readTimeoutMs: Int): HttpURLConnection {
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 30_000
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true
        }
    }
}
