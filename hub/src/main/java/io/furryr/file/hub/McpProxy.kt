package io.furryr.file.hub

import io.furryr.file.daemon.DaemonTarget
import io.furryr.file.proto.hub.McpHttpEvent
import io.furryr.file.proto.hub.McpHttpRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * Forward one HTTP request for a configured online MCP server. The hub does not
 * speak MCP: it only resolves the server, injects configured headers, and
 * streams the raw response back to the agent.
 */
internal object McpProxy {
    private const val MAX_RESPONSE = 16 * 1024 * 1024

    suspend fun request(
        request: McpHttpRequest,
        target: DaemonTarget,
        emit: suspend (McpHttpEvent) -> Unit,
    ) {
        val server = McpConfig.servers(target, request.cwd)
            .firstOrNull { it.name == request.server }
            ?: error("MCP server '${request.server}' is not configured")
        require(server.enabled) { "MCP server '${request.server}' is disabled" }
        require(server.url.isNotBlank()) { "MCP server '${request.server}' is not an online server" }
        val url = resolve(server.url, request.path)
        withContext(Dispatchers.IO) {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = request.method.ifBlank { "POST" }.uppercase()
                doInput = true
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 120_000
                for ((name, value) in request.headers + server.headers) {
                    setRequestProperty(name, value)
                }
                if (request.body.size() > 0 || requestMethod in setOf("POST", "PUT", "PATCH")) {
                    doOutput = true
                }
            }
            currentCoroutineContext()[Job]?.invokeOnCompletion {
                runCatching { connection.disconnect() }
            }
            try {
                if (connection.doOutput) {
                    connection.outputStream.use { it.write(request.body.toByteArray()) }
                }
                val status = connection.responseCode
                emit(
                    McpHttpEvent.newBuilder()
                        .setStatus(status)
                        .putAllHeaders(responseHeaders(connection))
                        .build(),
                )
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                if (stream != null) {
                    stream.use { input ->
                        val buffer = ByteArray(32 * 1024)
                        var total = 0
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read <= 0) break
                            total += read
                            if (total > MAX_RESPONSE) error("MCP response is too large")
                            emit(
                                McpHttpEvent.newBuilder()
                                    .setData(com.google.protobuf.ByteString.copyFrom(buffer, 0, read))
                                    .build(),
                            )
                        }
                    }
                }
                emit(McpHttpEvent.newBuilder().setDone(true).build())
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                emit(McpHttpEvent.newBuilder().setError(e.message ?: "MCP request failed").build())
                emit(McpHttpEvent.newBuilder().setDone(true).build())
            } finally {
                runCatching { connection.disconnect() }
            }
        }
    }

    private fun resolve(base: String, path: String): String {
        val uri = URI(base)
        require(uri.scheme == "http" || uri.scheme == "https") { "MCP URL must be HTTP(S)" }
        require(!uri.host.isNullOrBlank()) { "MCP URL has no host" }
        if (path.isBlank()) return uri.toString()
        return uri.resolve(path).toString()
    }

    private fun responseHeaders(connection: HttpURLConnection): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        for ((name, values) in connection.headerFields) {
            if (name == null) continue
            headers[name] = values.joinToString(", ")
        }
        return headers
    }
}
