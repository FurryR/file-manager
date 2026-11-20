package io.furryr.file.lsp

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Server capabilities that change document synchronization. */
internal data class LspCapabilities(
    val changeKind: Int = 1,
    val saveSupported: Boolean = false,
    val saveIncludeText: Boolean = false,
    val completionSupported: Boolean = false,
)

/**
 * One server process for one workspace.
 *
 * Documents attach and detach; the process is reused while at least one editor
 * page holds it. The pool owns the idle timer; this class only knows its own
 * documents.
 */
internal class LspWorkspaceSession(
    private val server: LspServerSpec,
    private val host: LspHost,
    private val rootName: String,
    private val onClosed: (LspWorkspaceSession) -> Unit,
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val documents = ConcurrentHashMap<String, LspOpenDocument>()
    private val lifecycle = Mutex()
    private var connection: LspConnection? = null
    private var capabilities = LspCapabilities()
    private var idleJob: Job? = null

    @Volatile
    private var closed = false

    val isClosed: Boolean get() = closed

    suspend fun start(): Result<Unit> = try {
        val process = host.start(server).getOrThrow()
        val connection = LspConnection(
            process = process,
            settings = server.settings,
            workspaceUri = host.rootUri,
            workspaceName = rootName,
            onNotification = ::onNotification,
        )
        this.connection = connection
        val result = connection.request("initialize", initializeParams())
            .optJSONObject("result")?.optJSONObject("capabilities") ?: JSONObject()
        require(result.optString("positionEncoding", "utf-16") == "utf-16") {
            "Language server must support UTF-16 positions"
        }
        val sync = result.opt("textDocumentSync")
        val changeKind = if (sync is Number) sync.toInt() else (sync as? JSONObject)?.optInt("change", 1) ?: 1
        val save = (sync as? JSONObject)?.opt("save")
        capabilities = LspCapabilities(
            changeKind = changeKind,
            saveSupported = save == true || save is JSONObject,
            saveIncludeText = (save as? JSONObject)?.optBoolean("includeText") ?: false,
            completionSupported = result.opt("completionProvider") is JSONObject,
        )
        connection.markInitialized()
        connection.notify("initialized", JSONObject())
        Result.success(Unit)
    } catch (cancelled: CancellationException) {
        connection?.close()
        connection = null
        runCatching { host.close() }
        throw cancelled
    } catch (error: Exception) {
        Log.d(TAG, "Language server ${server.id} did not start", error)
        connection?.close()
        connection = null
        runCatching { host.close() }
        Result.failure(error)
    }

    suspend fun open(
        uri: String,
        languageId: String,
        text: String,
        revision: Long,
        diagnostics: (List<LspDiagnostic>, Long) -> Unit,
    ): LspDocumentHandle? = lifecycle.withLock {
        val connection = connection ?: return@withLock null
        idleJob?.cancel()
        idleJob = null
        val existing = documents[uri]
        val document = if (existing == null) {
            LspOpenDocument(
                uri = uri,
                languageId = languageId,
                connection = connection,
                capabilities = { capabilities },
            ).also {
                documents[uri] = it
                it.addListener(diagnostics)
                it.open(text, revision)
            }
        } else {
            existing.also { it.addListener(diagnostics) }
        }
        LspDocumentHandle(document, diagnostics, ::detach)
    }

    fun detach(handle: LspDocumentHandle) {
        val document = handle.document
        if (!document.removeListener(handle.listener)) return
        if (document.listenerCount() != 0) return
        documents.remove(document.uri, document)
        document.close()
        if (documents.isEmpty()) scheduleIdle()
    }

    private fun scheduleIdle() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(IDLE_MS)
            if (documents.isEmpty()) close()
        }
    }

    private fun onNotification(method: String, params: JSONObject) {
        when (method) {
            "textDocument/publishDiagnostics" -> {
                val uri = params.optString("uri")
                documents[uri]?.publish(params)
            }
            "window/showMessage", "window/logMessage" ->
                Log.d(TAG, "Language server: ${params.optString("message")}")
            "window/showMessageRequest" -> Unit
            "$/progress" -> Unit
        }
    }

    private fun initializeParams(): JSONObject {
        val workspace = host.rootUri
        val folders = workspace?.let {
            JSONArray().put(obj("uri" to it, "name" to rootName))
        } ?: JSONObject.NULL
        return obj(
            "processId" to JSONObject.NULL,
            "clientInfo" to obj("name" to "File", "version" to "1.0"),
            "rootUri" to (workspace ?: JSONObject.NULL),
            "workspaceFolders" to folders,
            "initializationOptions" to (server.initializationOptions ?: JSONObject.NULL),
            "capabilities" to obj(
                "general" to obj("positionEncodings" to JSONArray().put("utf-16")),
                "workspace" to obj(
                    "configuration" to true,
                    "workspaceFolders" to true,
                    "applyEdit" to false,
                ),
                "textDocument" to obj(
                    "synchronization" to obj("dynamicRegistration" to false, "didSave" to true),
                    "completion" to obj(
                        "dynamicRegistration" to false,
                        "contextSupport" to true,
                        "completionItem" to obj(
                            "snippetSupport" to false,
                            "insertReplaceSupport" to true,
                            "documentationFormat" to JSONArray().put("plaintext"),
                        ),
                    ),
                    "publishDiagnostics" to obj("versionSupport" to true),
                ),
            ),
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        idleJob?.cancel()
        idleJob = null
        documents.values.forEach { it.close() }
        documents.clear()
        connection?.close()
        connection = null
        scope.launch {
            try {
                runCatching { host.close() }
            } finally {
                onClosed(this@LspWorkspaceSession)
                scope.cancel()
            }
        }
    }

    private companion object {
        const val TAG = "Lsp"
        const val IDLE_MS = 5 * 60 * 1000L
    }
}

/** A document handle held by one editor page. */
internal class LspDocumentHandle(
    internal val document: LspOpenDocument,
    internal val listener: (List<LspDiagnostic>, Long) -> Unit,
    private val detach: (LspDocumentHandle) -> Unit,
) : Closeable {
    suspend fun changed(text: String, revision: Long) = document.changed(text, revision)
    suspend fun saved(text: String, revision: Long) = document.saved(text, revision)
    suspend fun completion(text: String, revision: Long, line: Int, column: Int): List<JSONObject> =
        document.completion(text, revision, line, column)

    override fun close() = detach(this)
}

/** One open text document inside a workspace session. */
internal class LspOpenDocument(
    val uri: String,
    private val languageId: String,
    private val connection: LspConnection,
    private val capabilities: () -> LspCapabilities,
) {
    private val lock = Mutex()
    private val listeners = CopyOnWriteArrayList<(List<LspDiagnostic>, Long) -> Unit>()
    private var previousText = ""
    private var version = 1
    private var revision = -1L

    fun open(text: String, currentRevision: Long) {
        previousText = text
        revision = currentRevision
        connection.notify(
            "textDocument/didOpen",
            obj("textDocument" to obj(
                "uri" to uri,
                "languageId" to languageId,
                "version" to version,
                "text" to text,
            )),
        )
    }

    fun addListener(listener: (List<LspDiagnostic>, Long) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (List<LspDiagnostic>, Long) -> Unit): Boolean =
        listeners.remove(listener)

    fun listenerCount(): Int = listeners.size

    fun publish(params: JSONObject) {
        if (params.has("version") && params.optInt("version", version) != version) return
        val items = params.optJSONArray("diagnostics") ?: JSONArray()
        val parsed = (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val range = item.optJSONObject("range") ?: return@mapNotNull null
            val start = range.optJSONObject("start") ?: return@mapNotNull null
            val end = range.optJSONObject("end") ?: return@mapNotNull null
            LspDiagnostic(
                start.optInt("line"),
                start.optInt("character"),
                end.optInt("line"),
                end.optInt("character"),
                item.optInt("severity", 1),
                item.optString("message"),
            )
        }
        listeners.forEach { it(parsed, revision) }
    }

    suspend fun changed(text: String, currentRevision: Long) = withContext(Dispatchers.IO) {
        lock.withLock { sync(text, currentRevision) }
    }

    suspend fun saved(text: String, currentRevision: Long) = withContext(Dispatchers.IO) {
        lock.withLock {
            sync(text, currentRevision)
            val caps = capabilities()
            if (caps.saveSupported) {
                val params = obj("textDocument" to obj("uri" to uri))
                if (caps.saveIncludeText) params.put("text", text)
                connection.notify("textDocument/didSave", params)
            }
        }
    }

    suspend fun completion(
        text: String,
        currentRevision: Long,
        line: Int,
        column: Int,
    ): List<JSONObject> = withContext(Dispatchers.IO) {
        lock.withLock {
            val caps = capabilities()
            if (!caps.completionSupported || currentRevision < revision) return@withLock emptyList()
            sync(text, currentRevision)
            val result = connection.request(
                "textDocument/completion",
                obj(
                    "textDocument" to obj("uri" to uri),
                    "position" to obj("line" to line, "character" to column),
                    "context" to obj("triggerKind" to 1),
                ),
            ).opt("result")
            val items = (result as? JSONArray) ?: (result as? JSONObject)?.optJSONArray("items") ?: JSONArray()
            (0 until items.length()).mapNotNull { items.optJSONObject(it) }
        }
    }

    fun close() {
        connection.notify("textDocument/didClose", obj("textDocument" to obj("uri" to uri)))
        listeners.clear()
    }

    private fun sync(text: String, currentRevision: Long) {
        val caps = capabilities()
        if (currentRevision <= revision) return
        if (text != previousText && caps.changeKind != 0) {
            val change = obj("text" to text)
            if (caps.changeKind == 2) {
                val last = previousText.lastIndexOf('\n')
                change.put("range", obj(
                    "start" to obj("line" to 0, "character" to 0),
                    "end" to obj(
                        "line" to previousText.count { it == '\n' },
                        "character" to previousText.length - last - 1,
                    ),
                ))
            }
            version++
            previousText = text
            revision = currentRevision
            connection.notify(
                "textDocument/didChange",
                obj(
                    "textDocument" to obj("uri" to uri, "version" to version),
                    "contentChanges" to JSONArray().put(change),
                ),
            )
        } else {
            previousText = text
            revision = currentRevision
        }
    }
}

private fun obj(vararg values: Pair<String, Any?>): JSONObject = JSONObject().apply {
    values.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
}
