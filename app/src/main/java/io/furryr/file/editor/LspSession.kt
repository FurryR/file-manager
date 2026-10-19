package io.furryr.file.editor

import android.net.Uri
import android.util.Log
import io.furryr.file.core.DaemonManager
import io.furryr.file.core.DaemonTarget
import io.furryr.file.core.process.CommandSpec
import io.furryr.file.core.process.PipeProcessChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** LSP JSON-RPC over three byte-transparent pipes; stderr can never enter a protocol frame. */
internal class LspSession private constructor(
    private val process: PipeProcessChannel,
    private val settings: LspSettings,
    val uri: String,
    private val onDiagnostics: (List<LspDiagnostic>, Long) -> Unit,
    private val onStatus: (String) -> Unit,
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requests = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()
    private val nextId = AtomicInteger(1)
    private val closing = AtomicBoolean(false)
    private val outputLock = Any()
    private val documentLock = Mutex()
    private var previousText = ""
    @Volatile private var version = 1
    @Volatile private var revision = -1L
    @Volatile private var initialized = false
    private var changeKind = 1
    private var saveSupported = false
    private var saveIncludeText = false
    private var completionSupported = false

    private fun beginReading() {
        scope.launch {
            try {
                val input = BufferedInputStream(process.input)
                while (true) {
                    var length: Int? = null
                    var headerBytes = 0
                    while (true) {
                        val line = ByteArrayOutputStream()
                        while (true) {
                            val byte = input.read()
                            if (byte < 0) throw EOFException("Language server closed stdout")
                            if (++headerBytes > 32 * 1024) throw IOException("Invalid LSP header")
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
                    require(count in 1..16 * 1024 * 1024) { "LSP message exceeds 16 MiB" }
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
                    onStatus(error.message ?: "Language server disconnected")
                    close()
                }
            }
        }
        scope.launch {
            // Drain stderr independently so a verbose server cannot block its stdout pipe.
            try {
                val bytes = ByteArray(4096)
                while (process.error.read(bytes) >= 0) { /* output is not protocol or document data */ }
            } catch (error: Exception) {
                if (!closing.get()) Log.d("EditorLsp", "Language server stderr ended", error)
            }
        }
    }

    private fun write(message: JSONObject) {
        val bytes = message.toString().toByteArray(Charsets.UTF_8)
        synchronized(outputLock) {
            process.output.write("Content-Length: ${bytes.size}\r\n\r\n".toByteArray(Charsets.US_ASCII))
            process.output.write(bytes)
            process.output.flush()
        }
    }

    private fun notify(method: String, params: JSONObject) = write(obj("jsonrpc" to "2.0", "method" to method, "params" to params))

    private suspend fun request(method: String, params: Any = JSONObject.NULL): JSONObject {
        val id = nextId.getAndIncrement()
        val result = CompletableDeferred<JSONObject>()
        requests[id] = result
        try {
            withContext(Dispatchers.IO) { write(obj("jsonrpc" to "2.0", "id" to id, "method" to method, "params" to params)) }
            val reply = withTimeout(20_000) { result.await() }
            reply.optJSONObject("error")?.let { throw IOException(it.optString("message", "LSP request failed")) }
            return reply
        } catch (cancelled: CancellationException) {
            if (!closing.get()) scope.launch {
                try { notify("$/cancelRequest", obj("id" to id)) }
                catch (error: Exception) { Log.d("EditorLsp", "Could not cancel LSP request", error) }
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
                "workspace/configuration" -> JSONArray().apply {
                    repeat(params.optJSONArray("items")?.length() ?: 0) { put(JSONObject()) }
                }
                "workspace/workspaceFolders" -> workspaceFolders()
                "workspace/applyEdit" -> obj("applied" to false, "failureReason" to "Workspace edits require explicit editor support")
                "client/registerCapability", "client/unregisterCapability", "window/workDoneProgress/create", "window/showMessageRequest" -> JSONObject.NULL
                else -> {
                    write(obj("jsonrpc" to "2.0", "id" to id, "error" to obj("code" to -32601, "message" to "Method not supported")))
                    return
                }
            }
            write(obj("jsonrpc" to "2.0", "id" to id, "result" to result))
        } else if (!closing.get() && method == "textDocument/publishDiagnostics" && params.optString("uri") == uri) {
            if (params.has("version") && params.optInt("version", version) != version) return
            val items = params.optJSONArray("diagnostics") ?: JSONArray()
            onDiagnostics((0 until items.length()).mapNotNull { index ->
                val item = items.optJSONObject(index) ?: return@mapNotNull null
                val range = item.optJSONObject("range") ?: return@mapNotNull null
                val start = range.optJSONObject("start") ?: return@mapNotNull null
                val end = range.optJSONObject("end") ?: return@mapNotNull null
                LspDiagnostic(start.optInt("line"), start.optInt("character"), end.optInt("line"), end.optInt("character"),
                    item.optInt("severity", 1), item.optString("message"))
            }, revision)
        }
    }

    private fun workspaceFolders() = JSONArray().put(obj("uri" to fileUri(settings.root), "name" to settings.root.substringAfterLast('/').ifEmpty { "/" }))

    private suspend fun initialize(language: String, text: String, initialRevision: Long) {
        beginReading()
        val capabilities = obj(
            "general" to obj("positionEncodings" to JSONArray().put("utf-16")),
            "workspace" to obj("configuration" to true, "workspaceFolders" to true, "applyEdit" to false),
            "textDocument" to obj(
                "synchronization" to obj("dynamicRegistration" to false, "didSave" to true),
                "completion" to obj("dynamicRegistration" to false, "contextSupport" to true,
                    "completionItem" to obj("snippetSupport" to false, "insertReplaceSupport" to true,
                        "documentationFormat" to JSONArray().put("plaintext"))),
                "publishDiagnostics" to obj("versionSupport" to true),
            ),
        )
        val result = request("initialize", obj("processId" to JSONObject.NULL,
            "clientInfo" to obj("name" to "File Manager", "version" to "1.0"),
            "rootUri" to fileUri(settings.root), "workspaceFolders" to workspaceFolders(), "capabilities" to capabilities))
            .optJSONObject("result")?.optJSONObject("capabilities") ?: JSONObject()
        require(result.optString("positionEncoding", "utf-16") == "utf-16") { "Language server must support UTF-16 positions" }
        val sync = result.opt("textDocumentSync")
        changeKind = if (sync is Number) sync.toInt() else (sync as? JSONObject)?.optInt("change", 1) ?: 1
        val save = (sync as? JSONObject)?.opt("save")
        saveSupported = save == true || save is JSONObject
        saveIncludeText = (save as? JSONObject)?.optBoolean("includeText") ?: false
        completionSupported = result.opt("completionProvider") is JSONObject
        previousText = text
        revision = initialRevision
        notify("initialized", JSONObject())
        notify("textDocument/didOpen", obj("textDocument" to obj("uri" to uri, "languageId" to language, "version" to version, "text" to text)))
        initialized = true
    }

    private fun syncLocked(text: String, currentRevision: Long) {
        if (currentRevision <= revision || !initialized || closing.get()) return
        if (text != previousText && changeKind != 0) {
            val change = obj("text" to text)
            if (changeKind == 2) {
                val last = previousText.lastIndexOf('\n')
                change.put("range", obj("start" to obj("line" to 0, "character" to 0),
                    "end" to obj("line" to previousText.count { it == '\n' }, "character" to previousText.length - last - 1)))
            }
            version++
            // Publish the revision before the write: a fast server may return
            // diagnostics while the pipe-write acknowledgement is in flight.
            previousText = text
            revision = currentRevision
            notify("textDocument/didChange", obj("textDocument" to obj("uri" to uri, "version" to version), "contentChanges" to JSONArray().put(change)))
        }
        previousText = text
        revision = currentRevision
    }

    suspend fun changed(text: String, currentRevision: Long) = withContext(Dispatchers.IO) {
        documentLock.withLock { syncLocked(text, currentRevision) }
    }

    suspend fun saved(text: String, currentRevision: Long) = withContext(Dispatchers.IO) {
        documentLock.withLock {
            syncLocked(text, currentRevision)
            if (initialized && !closing.get() && saveSupported) {
                val params = obj("textDocument" to obj("uri" to uri))
                if (saveIncludeText) params.put("text", text)
                notify("textDocument/didSave", params)
            }
        }
    }

    suspend fun completion(text: String, currentRevision: Long, line: Int, column: Int): List<JSONObject> = withContext(Dispatchers.IO) {
        documentLock.withLock {
            if (!initialized || closing.get() || !completionSupported || currentRevision < revision) return@withLock emptyList()
            syncLocked(text, currentRevision)
            val result = request("textDocument/completion", obj("textDocument" to obj("uri" to uri),
                "position" to obj("line" to line, "character" to column), "context" to obj("triggerKind" to 1))).opt("result")
            val items = (result as? JSONArray) ?: (result as? JSONObject)?.optJSONArray("items") ?: JSONArray()
            (0 until items.length()).mapNotNull { items.optJSONObject(it) }
        }
    }

    override fun close() {
        if (!closing.compareAndSet(false, true)) return
        // A non-reading server can block a pipe write. This independent close
        // wakes both pipes even if graceful shutdown cannot obtain outputLock.
        scope.launch {
            kotlinx.coroutines.delay(2_000)
            disposeProcess()
        }
        scope.launch {
            try {
                if (initialized) {
                    notify("textDocument/didClose", obj("textDocument" to obj("uri" to uri)))
                    withTimeout(1_500) { request("shutdown") }
                    notify("exit", JSONObject())
                }
            } catch (error: Exception) {
                Log.d("EditorLsp", "Closing language server", error)
            } finally {
                requests.values.forEach { it.cancel() }
                requests.clear()
                disposeProcess()
            }
        }
    }

    private fun disposeProcess() {
        try { process.close() }
        catch (error: Exception) { Log.d("EditorLsp", "Language server process already unavailable", error) }
        finally { scope.cancel() }
    }

    companion object {
        suspend fun start(settings: LspSettings, language: String, text: String, revision: Long,
            diagnostics: (List<LspDiagnostic>, Long) -> Unit, status: (String) -> Unit): LspSession {
            var ownedSession: LspSession? = null
            try {
                return withContext(Dispatchers.IO) {
                    require(settings.valid) { "Choose a container, server command, workspace and absolute document path" }
                    val process = DaemonManager.execService(DaemonTarget.Named(settings.container)).spawnPipe(
                        CommandSpec("/bin/sh", listOf("-c", "exec ${settings.command}"), cwd = settings.root),
                    ).getOrThrow()
                    val session = LspSession(process, settings, fileUri(settings.documentPath), diagnostics, status)
                    ownedSession = session
                    session.initialize(language, text, revision)
                    session
                }
            } catch (error: Exception) {
                // Also owns cancellation while returning to the Activity dispatcher.
                ownedSession?.close()
                throw error
            }
        }

        private fun fileUri(path: String): String = Uri.fromFile(File(path)).toString()
        private fun obj(vararg values: Pair<String, Any?>) = JSONObject().apply { values.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) } }
    }
}

internal data class LspDiagnostic(val startLine: Int, val startColumn: Int, val endLine: Int, val endColumn: Int, val severity: Int, val message: String)
