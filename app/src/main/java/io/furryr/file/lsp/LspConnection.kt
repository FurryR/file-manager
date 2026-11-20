package io.furryr.file.lsp

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * LSP JSON-RPC over the process's three pipes.
 *
 * stderr is drained independently so a verbose server cannot block stdout, and
 * stdout is protocol-only: non-LSP bytes are a protocol error, never a message.
 */
internal class LspConnection(
    private val process: LspProcess,
    private val settings: JSONObject?,
    private val workspaceUri: String?,
    private val workspaceName: String,
    private val onNotification: (String, JSONObject) -> Unit,
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requests = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()
    private val nextId = AtomicInteger(1)
    private val closing = AtomicBoolean(false)
    private val outputLock = Any()

    @Volatile
    var initialized: Boolean = false
        private set

    fun markInitialized() {
        initialized = true
    }

    init {
        beginReading()
    }

    private fun beginReading() {
        scope.launch {
            try {
                val input = BufferedInputStream(process.stdout)
                while (true) {
                    var length: Int? = null
                    var headerBytes = 0
                    while (true) {
                        val line = ByteArrayOutputStream()
                        while (true) {
                            val byte = input.read()
                            if (byte < 0) throw EOFException("Language server closed stdout")
                            if (++headerBytes > MAX_HEADER) throw IOException("Invalid LSP header")
                            if (byte == '\n'.code) break
                            line.write(byte)
                        }
                        val header = line.toString("US-ASCII").trimEnd('\r')
                        if (header.isEmpty()) break
                        val separator = header.indexOf(':')
                        if (separator < 0) throw IOException("Language server wrote non-LSP output to stdout")
                        if (header.substring(0, separator).equals("Content-Length", true)) {
                            require(length == null) { "Duplicate LSP Content-Length" }
                            length = header.substring(separator + 1).trim().toIntOrNull()
                                ?: throw IOException("Invalid LSP Content-Length")
                        }
                    }
                    val count = length ?: throw IOException("Missing LSP Content-Length")
                    require(count in 1..MAX_MESSAGE) { "LSP message exceeds 16 MiB" }
                    val bytes = ByteArray(count)
                    var read = 0
                    while (read < count) {
                        val n = input.read(bytes, read, count - read)
                        if (n < 0) throw EOFException("Truncated LSP message")
                        read += n
                    }
                    receive(JSONObject(String(bytes, Charsets.UTF_8)))
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                requests.values.forEach { it.completeExceptionally(error) }
                requests.clear()
                if (!closing.get()) {
                    Log.d(TAG, "Language server disconnected", error)
                    close()
                }
            }
        }
        scope.launch {
            try {
                val bytes = ByteArray(4096)
                while (process.stderr.read(bytes) >= 0) { /* not protocol or document data */ }
            } catch (error: Exception) {
                if (!closing.get()) Log.d(TAG, "Language server stderr ended", error)
            }
        }
    }

    private fun write(message: JSONObject) {
        val bytes = message.toString().toByteArray(Charsets.UTF_8)
        synchronized(outputLock) {
            process.stdin.write("Content-Length: ${bytes.size}\r\n\r\n".toByteArray(Charsets.US_ASCII))
            process.stdin.write(bytes)
            process.stdin.flush()
        }
    }

    fun notify(method: String, params: JSONObject) {
        runCatching { write(obj("jsonrpc" to "2.0", "method" to method, "params" to params)) }
            .onFailure { Log.d(TAG, "Could not notify $method", it) }
    }

    suspend fun request(method: String, params: Any = JSONObject.NULL, timeoutMs: Long = 20_000): JSONObject {
        val id = nextId.getAndIncrement()
        val result = CompletableDeferred<JSONObject>()
        requests[id] = result
        try {
            withContext(Dispatchers.IO) {
                write(obj("jsonrpc" to "2.0", "id" to id, "method" to method, "params" to params))
            }
            val reply = withTimeout(timeoutMs) { result.await() }
            reply.optJSONObject("error")?.let {
                throw IOException(it.optString("message", "LSP request failed"))
            }
            return reply
        } catch (cancelled: CancellationException) {
            if (!closing.get()) scope.launch {
                runCatching { notify("$/cancelRequest", obj("id" to id)) }
            }
            throw cancelled
        } finally {
            requests.remove(id)
        }
    }

    private fun receive(message: JSONObject) {
        val method = message.optString("method")
        if (method.isEmpty()) {
            requests.remove(message.optInt("id", -1))?.complete(message)
            return
        }
        val params = message.optJSONObject("params") ?: JSONObject()
        if (message.has("id")) {
            val id = message.get("id")
            val result: Any = when (method) {
                "workspace/configuration" -> configuration(params.optJSONArray("items"))
                "workspace/workspaceFolders" -> workspaceFolders()
                "workspace/applyEdit" -> obj(
                    "applied" to false,
                    "failureReason" to "Workspace edits require explicit editor support",
                )
                "client/registerCapability", "client/unregisterCapability",
                "window/workDoneProgress/create", "window/showMessageRequest",
                -> JSONObject.NULL
                else -> {
                    write(obj("jsonrpc" to "2.0", "id" to id, "error" to obj(
                        "code" to -32601,
                        "message" to "Method not supported",
                    )))
                    return
                }
            }
            write(obj("jsonrpc" to "2.0", "id" to id, "result" to result))
        } else if (!closing.get()) {
            onNotification(method, params)
        }
    }

    private fun configuration(items: JSONArray?): JSONArray {
        val result = JSONArray()
        if (items == null) return result
        for (index in 0 until items.length()) {
            val section = items.optJSONObject(index)?.optString("section").orEmpty()
            result.put(configurationValue(section))
        }
        return result
    }

    private fun configurationValue(section: String): Any {
        val root = settings ?: return JSONObject()
        if (section.isBlank()) return root
        var current: Any? = root
        for (part in section.split('.')) {
            current = (current as? JSONObject)?.opt(part) ?: return JSONObject()
        }
        return current ?: JSONObject()
    }

    private fun workspaceFolders(): Any {
        val uri = workspaceUri ?: return JSONObject.NULL
        return JSONArray().put(obj("uri" to uri, "name" to workspaceName))
    }

    override fun close() {
        if (!closing.compareAndSet(false, true)) return
        scope.launch {
            delay(2_000)
            runCatching { process.terminate() }
        }
        scope.launch {
            try {
                if (initialized) {
                    runCatching { withTimeout(1_500) { request("shutdown") } }
                    notify("exit", JSONObject())
                }
            } finally {
                requests.values.forEach { it.cancel() }
                requests.clear()
                runCatching { process.terminate() }
                scope.cancel()
            }
        }
    }

    private companion object {
        const val TAG = "Lsp"
        const val MAX_HEADER = 32 * 1024
        const val MAX_MESSAGE = 16 * 1024 * 1024

        fun obj(vararg values: Pair<String, Any?>): JSONObject = JSONObject().apply {
            values.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
        }
    }
}
