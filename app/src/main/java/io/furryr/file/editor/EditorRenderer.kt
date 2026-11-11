package io.furryr.file.editor

import android.content.Context
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SaveAs
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.furryr.file.R
import io.furryr.file.core.DirectoryCapabilities
import io.furryr.file.core.Path
import io.furryr.file.core.RemoteFile
import io.furryr.file.core.VFile
import io.furryr.file.core.VFolder
import io.furryr.file.render.LocationAddress
import io.furryr.file.render.RenderCapabilities
import io.furryr.file.render.RenderHost
import io.furryr.file.render.RenderTarget
import io.furryr.file.render.RendererFactory
import io.furryr.file.render.RendererMenuItem
import io.furryr.file.render.RendererPath
import io.furryr.file.render.RendererSession
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.EditorKeyEvent
import io.github.rosemoe.sora.event.PublishSearchResultEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticDetail
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticRegion
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticsContainer
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher
import io.github.rosemoe.sora.widget.component.EditorAutoCompletion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * One text buffer and its Sora view, owned by a tab. The view is created on
 * first composition and kept across tab switches, so undo history, cursor and
 * scroll position survive navigation exactly like the WebView does.
 */
class EditorRendererSession(
    val file: VFile,
    val address: String,
    private val host: RenderHost,
) : RendererSession {
    var document by mutableStateOf<TextDocument?>(null)
        private set
    var dirty by mutableStateOf(false)
        private set
    var saving by mutableStateOf(false)
        private set
    override var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
    var conflict by mutableStateOf(false)
        private set
    var showSearch by mutableStateOf(false)
    var query by mutableStateOf("")
    var replacement by mutableStateOf("")
    var matchCase by mutableStateOf(false)
    var regex by mutableStateOf(false)
    var searchError by mutableStateOf<String?>(null)
        private set
    var matchCount by mutableIntStateOf(0)
        private set
    var showLanguagePicker by mutableStateOf(false)
    var showDiagnostics by mutableStateOf(false)
    var showGoto by mutableStateOf(false)
    var gotoLine by mutableStateOf("")
    var lspStatus by mutableStateOf("")
    var connecting by mutableStateOf(false)
        private set
    var diagnostics by mutableStateOf<List<LspDiagnostic>>(emptyList())
        private set
    var cursorLine by mutableIntStateOf(0)
        private set
    var cursorColumn by mutableIntStateOf(0)
        private set
    var wrap by mutableStateOf(false)
        private set
    var completionsEnabled by mutableStateOf(true)
        private set
    /** Directory actions (terminal) depend on the folder that owns the file. */
    var parentCapabilities by mutableStateOf(DirectoryCapabilities())
        private set
    /** Bumped after every edit, so capability getters re-read undo state. */
    var revision by mutableIntStateOf(0)
        private set

    var languageId by mutableStateOf(EditorLanguages.id(file.name))
        private set
    /** Folder that owns this file; Up and the terminal action use it. */
    val parentFolder: VFolder? get() = file.parent
    /** Address of [parentFolder] in the same form the file bar uses. */
    val parentAddress: String?
        get() = parentFolder?.let { folder ->
            runCatching { LocationAddress.format(folder.path) }.getOrNull()
        }
    val title: String get() = (if (dirty) "• " else "") + file.name

    /**
     * A live resource and a view cannot commit bytes, and so can the providers
     * whose `open(WRITE)` is not implemented. The buffer stays viewable but
     * never pretends a save could succeed.
     */
    val readOnly: Boolean =
        file.path.isViewed ||
            file is RemoteFile ||
            file.path.scheme in setOf("saf", "ftp", "document")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var appContext: Context? = null
    private var editorView: CodeEditor? = null
    private var lsp: LspSession? = null
    private var lspJob: Job? = null
    private var lspGeneration = 0
    private var changeJob: Job? = null
    private var lastPublishedTitle: String? = null
    private var closed = false
    /** Continuation of a leave action waiting for the conflict dialog. */
    private var pendingSave: ((Boolean) -> Unit)? = null

    override val capabilities: RenderCapabilities
        get() {
            // Read the revision and dirty states so Compose re-evaluates the
            // shared toolbar after every edit.
            revision
            val view = editorView
            return RenderCapabilities(
                canGoParent = parentFolder != null,
                canSave = dirty && !saving && document != null && !readOnly,
                canUndo = view != null && !view.isReleased && view.text.canUndo(),
                canEdit = document != null && !saving && !readOnly,
                canTerminal = parentCapabilities.canOpenTerminal,
            )
        }

    override val consumesEdgeGestures: Boolean get() = true
    override val canDismiss: Boolean get() = true
    override val modalActive: Boolean get() = conflict

    @Composable
    override fun Content(darkTheme: Boolean, modifier: Modifier) {
        EditorContent(this, darkTheme, modifier)
    }

    override fun requestClose(action: () -> Unit) {
        if (saving) {
            appContext?.let { error = it.getString(R.string.editor_saving_wait) }
            return
        }
        if (canClose()) {
            close()
            action()
            return
        }
        host.confirmLeave(
            message = null,
            canSave = capabilities.canSave,
            onSave = { saveForLeave() },
            onLeave = {
                close()
                action()
            },
            onCancel = {},
        )
    }

    override fun canClose(): Boolean = !dirty && !saving

    override fun refresh(): Boolean = true

    override fun pathBar(context: Context): RendererPath = RendererPath(
        title = address,
        caption = document?.let { document ->
            "${document.formatLabel} · ${cursorLine + 1}:${cursorColumn + 1}" +
                if (readOnly) " · ${context.getString(R.string.editor_read_only)}" else ""
        },
        editText = address,
    )

    override fun menuItems(context: Context): List<RendererMenuItem> = buildList {
        val view = editorView?.takeIf { !it.isReleased }
        val canUndo = view != null && !saving && view.text.canUndo()
        val canRedo = view != null && !saving && view.text.canRedo()
        add(
            RendererMenuItem(
                title = context.getString(R.string.editor_file),
                icon = Icons.Default.Description,
                children = buildList {
                    add(
                        RendererMenuItem(
                            title = context.getString(R.string.editor_save),
                            icon = Icons.Default.Save,
                            enabled = capabilities.canSave,
                        ) { requestSave() },
                    )
                    add(
                        RendererMenuItem(
                            title = context.getString(R.string.editor_save_as),
                            icon = Icons.Default.SaveAs,
                            enabled = !saving,
                        ) { saveAs() },
                    )
                    add(
                        RendererMenuItem(
                            title = context.getString(R.string.editor_undo),
                            icon = Icons.AutoMirrored.Filled.Undo,
                            enabled = canUndo,
                        ) { undo() },
                    )
                    add(
                        RendererMenuItem(
                            title = context.getString(R.string.editor_redo),
                            icon = Icons.AutoMirrored.Filled.Redo,
                            enabled = canRedo,
                        ) { redo() },
                    )
                    add(
                        RendererMenuItem(
                            title = context.getString(R.string.editor_file_type),
                            icon = Icons.Default.Code,
                        ) { showLanguagePicker = true },
                    )
                },
            ),
        )
        add(
            RendererMenuItem(
                title = context.getString(R.string.editor_search),
                icon = Icons.Default.Search,
            ) { showSearch = !showSearch },
        )
        if (diagnostics.isNotEmpty()) {
            add(
                RendererMenuItem(
                    title = context.getString(R.string.editor_diagnostics, diagnostics.size),
                    icon = Icons.Default.Warning,
                ) { showDiagnostics = true },
            )
        }
        add(
            RendererMenuItem(
                title = context.getString(R.string.editor_goto),
                icon = Icons.Default.FormatListNumbered,
            ) {
                gotoLine = ""
                showGoto = true
            },
        )
        add(
            RendererMenuItem(
                title = context.getString(R.string.editor_wrap),
                icon = Icons.AutoMirrored.Filled.WrapText,
                checked = wrap,
            ) { toggleWrap() },
        )
        add(
            RendererMenuItem(
                title = context.getString(R.string.editor_complete),
                icon = Icons.Default.Lightbulb,
                checked = completionsEnabled,
            ) { toggleCompletions(context) },
        )
    }

    suspend fun load(): Result<Unit> = runCatching {
        val bytes = EditorFiles.read(file)
        document = withContext(Dispatchers.IO) { TextDocument.decode(bytes) }
        loading = false
        observeParentCapabilities()
    }.onFailure { failure ->
        if (failure is CancellationException) throw failure
        loading = false
        error = failure.localizedMessage ?: failure.javaClass.simpleName
    }

    /**
     * Directory capability probing can cost a remote round-trip, so the editor
     * opens first and the terminal action enables when the answer arrives.
     */
    private fun observeParentCapabilities() {
        val folder = parentFolder ?: return
        scope.launch {
            val capabilities = withContext(Dispatchers.IO) {
                runCatching { folder.capabilities() }.getOrNull()
            } ?: return@launch
            if (!closed) parentCapabilities = capabilities
        }
    }

    /**
     * Create the Sora view once. The AndroidView holder may attach and detach
     * it on tab switches; the session keeps the editable buffer alive.
     */
    fun ensureEditor(context: Context, dark: Boolean = true): CodeEditor {
        appContext = context.applicationContext
        editorView?.let { return it }
        lspStatus = context.getString(R.string.editor_lsp_disconnected)
        completionsEnabled = EditorPreferences.completionEnabled(context)
        val typeface = EditorPreferences.typeface(context)
        val editor = CodeEditor(context).apply {
            setTextSize(EditorPreferences.fontSize(context).toFloat())
            typefaceText = typeface
            typefaceLineNumber = typeface
            isScalable = true
            isLineNumberEnabled = true
            isWordwrap = wrap
            tabWidth = 4
            isEditable = document != null && !readOnly
            document?.let { setText(it.text, true, null) }
            EditorLanguages.configure(context, this, languageId, dark) { lsp }
            getComponent(EditorAutoCompletion::class.java)?.setEnabled(completionsEnabled)
        }
        editorView = editor
        editor.subscribeEvent(ContentChangeEvent::class.java) { _, _ ->
            editor.post {
                if (!editor.isReleased) {
                    updateState()
                    editor.diagnostics = null
                    diagnostics = emptyList()
                    changeJob?.cancel()
                    lsp?.let { server ->
                        val snapshot = editor.text.toString()
                        val version = editor.text.documentVersion
                        changeJob = scope.launch {
                            delay(180)
                            try {
                                server.changed(snapshot, version)
                            } catch (failure: Exception) {
                                if (failure is CancellationException) throw failure
                                lspStatus = failure.message ?: appContext?.getString(R.string.editor_lsp_disconnected).orEmpty()
                            }
                        }
                    }
                }
            }
        }
        editor.subscribeEvent(SelectionChangeEvent::class.java) { _, _ ->
            cursorLine = editor.cursor.leftLine
            cursorColumn = editor.cursor.leftColumn
        }
        editor.subscribeEvent(PublishSearchResultEvent::class.java) { _, _ ->
            // stopSearch() dispatches this event after clearing the pattern,
            // so the getter can legitimately throw "pattern not set".
            matchCount = runCatching { editor.searcher.matchedPositionCount }.getOrDefault(0)
        }
        editor.subscribeEvent(EditorKeyEvent::class.java) { event, _ ->
            if (event.action == KeyEvent.ACTION_DOWN && event.isCtrlPressed) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_S -> {
                        requestSave()
                        event.intercept()
                        event.setResult(true)
                    }
                    KeyEvent.KEYCODE_F -> {
                        showSearch = true
                        event.intercept()
                        event.setResult(true)
                    }
                }
            }
        }
        LspSettings.forFile(context, address, languageId)?.let { connect(it) }
        return editor
    }

    /** Re-read display and completion keys after the settings screen changes them. */
    fun applyPreferences() {
        val context = appContext ?: return
        val editor = editorView?.takeIf { !it.isReleased } ?: return
        editor.setTextSize(EditorPreferences.fontSize(context).toFloat())
        val typeface = EditorPreferences.typeface(context)
        editor.typefaceText = typeface
        editor.typefaceLineNumber = typeface
        val enabled = EditorPreferences.completionEnabled(context)
        completionsEnabled = enabled
        editor.getComponent(EditorAutoCompletion::class.java)?.setEnabled(enabled)
        if (!enabled) editor.hideAutoCompleteWindow()
    }

    fun currentEditor(): CodeEditor? = editorView

    private fun updateState() {
        val editor = editorView ?: return
        dirty = document?.let { editor.text.toString() != it.text } ?: false
        cursorLine = editor.cursor.leftLine
        cursorColumn = editor.cursor.leftColumn
        revision++
        val next = title
        if (next != lastPublishedTitle) {
            lastPublishedTitle = next
            host.updateTitle(next)
        }
    }

    fun requestSave(overwrite: Boolean = false, onDone: ((Boolean) -> Unit)? = null) {
        if (saving) return
        scope.launch {
            val success = save(overwrite).isSuccess
            onDone?.invoke(success)
        }
    }

    /**
     * Save an immutable snapshot of the buffer. Conflicts are surfaced as a
     * dialog; only an explicit overwrite may replace a file changed elsewhere.
     */
    suspend fun save(overwrite: Boolean = false): Result<Unit> {
        if (readOnly) {
            val message = appContext?.getString(R.string.editor_read_only)
                ?: "This view is read-only."
            error = message
            return Result.failure(IllegalStateException(message))
        }
        if (saving) return Result.failure(IllegalStateException("A save is already in progress"))
        val original = document ?: return Result.failure(IllegalStateException("Document is not loaded"))
        val editor = editorView ?: return Result.failure(IllegalStateException("Editor is not attached"))
        val text = editor.text.toString()
        val documentRevision = editor.text.documentVersion
        saving = true
        editor.isEditable = false
        return try {
            val encoded = withContext(Dispatchers.IO) { original.encode(text) }
            if (!overwrite && !EditorFiles.isUnchanged(file, original.digest)) {
                conflict = true
                return Result.failure(EditorConflictException())
            }
            withContext(Dispatchers.IO) { EditorFiles.write(file, encoded) }
            val updated = withContext(Dispatchers.IO) { TextDocument.decode(encoded) }
            document = updated
            dirty = editor.text.toString() != updated.text
            revision++
            if (!dirty) {
                lastPublishedTitle = title
                host.updateTitle(title)
            }
            lsp?.let { server ->
                try {
                    server.saved(updated.text, documentRevision)
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    lspStatus = failure.message ?: appContext?.getString(R.string.editor_lsp_disconnected).orEmpty()
                }
            }
            appContext?.let { context ->
                Toast.makeText(context, R.string.editor_saved, Toast.LENGTH_SHORT).show()
            }
            Result.success(Unit)
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            error = failure.localizedMessage ?: failure.javaClass.simpleName
            Result.failure(failure)
        } finally {
            saving = false
            if (!editor.isReleased) editor.isEditable = true
        }
    }

    /**
     * Save as part of leaving the editor. A conflict keeps this suspended until
     * the renderer's own conflict dialog is resolved.
     */
    suspend fun saveForLeave(): Result<Unit> {
        val result = save()
        if (result.isSuccess || !conflict) return result
        return suspendCancellableCoroutine { continuation ->
            pendingSave = { success ->
                if (success) {
                    continuation.resume(Result.success(Unit))
                } else {
                    continuation.resume(Result.failure(EditorConflictException()))
                }
            }
            continuation.invokeOnCancellation { pendingSave = null }
        }
    }

    /** Overwrite after the conflict dialog; resumes a pending leave on success. */
    fun overwrite() {
        scope.launch {
            val result = save(overwrite = true)
            if (result.isSuccess) {
                conflict = false
                val done = pendingSave
                pendingSave = null
                done?.invoke(true)
            }
        }
    }

    fun dismissConflict() {
        conflict = false
        val done = pendingSave
        pendingSave = null
        done?.invoke(false)
    }

    fun undo() {
        editorView?.takeIf { !it.isReleased }?.undo()
    }

    fun redo() {
        editorView?.takeIf { !it.isReleased }?.redo()
    }

    fun toggleWrap() {
        wrap = !wrap
        editorView?.isWordwrap = wrap
    }

    /** Enter the tab's copy flow so the user picks a destination for the file. */
    fun saveAs() {
        host.copyFile(file)
    }

    fun toggleCompletions(context: Context) {
        val enabled = !completionsEnabled
        completionsEnabled = enabled
        EditorPreferences.setCompletionEnabled(context, enabled)
        val editor = editorView?.takeIf { !it.isReleased } ?: return
        editor.getComponent(EditorAutoCompletion::class.java)?.setEnabled(enabled)
        if (!enabled) editor.hideAutoCompleteWindow()
    }

    /** Re-highlight the buffer with another language without losing its text. */
    fun setLanguage(context: Context, id: String, dark: Boolean) {
        if (id == languageId) return
        languageId = id
        disconnectLsp()
        val editor = editorView?.takeIf { !it.isReleased } ?: return
        EditorLanguages.configure(context, editor, id, dark) { lsp }
        LspSettings.forFile(context, address, id)?.let { connect(it) }
    }

    fun applySearch() {
        val editor = editorView ?: return
        searchError = null
        if (!showSearch || query.isEmpty()) {
            editor.searcher.stopSearch()
            matchCount = 0
            return
        }
        try {
            editor.searcher.search(query, EditorSearcher.SearchOptions(!matchCase, regex))
        } catch (failure: Exception) {
            editor.searcher.stopSearch()
            searchError = failure.message
            matchCount = 0
        }
    }

    fun gotoPrevious() = editorView?.searcher?.gotoPrevious()

    fun gotoNext() = editorView?.searcher?.gotoNext()

    fun replaceCurrent() {
        val editor = editorView ?: return
        editor.searcher.replaceCurrentMatch(replacement)
    }

    fun replaceAll() {
        val editor = editorView ?: return
        editor.searcher.replaceAll(replacement)
    }

    fun connect(settings: LspSettings) {
        val context = appContext ?: return
        val editor = editorView ?: return
        if (document == null) return
        lspJob?.cancel()
        lsp?.close()
        lsp = null
        diagnostics = emptyList()
        editor.diagnostics = null
        connecting = true
        lspStatus = context.getString(R.string.editor_lsp_connecting)
        val generation = ++lspGeneration
        val initialText = editor.text.toString()
        val initialRevision = editor.text.documentVersion
        lspJob = scope.launch {
            try {
                val server = LspSession.start(settings, languageId, initialText, initialRevision,
                    diagnostics = { items, serverRevision ->
                        scope.launch {
                            if (closed) return@launch
                            val view = editorView
                            if (view != null && !view.isReleased && view.text.documentVersion == serverRevision) {
                                diagnostics = items
                                val marks = DiagnosticsContainer()
                                items.forEach { item ->
                                    val content = view.text
                                    if (item.startLine in 0 until content.lineCount && item.endLine in 0 until content.lineCount) {
                                        val start = content.getCharIndex(
                                            item.startLine,
                                            item.startColumn.coerceIn(0, content.getColumnCount(item.startLine)),
                                        )
                                        val end = content.getCharIndex(
                                            item.endLine,
                                            item.endColumn.coerceIn(0, content.getColumnCount(item.endLine)),
                                        )
                                        if (end >= start) marks.addDiagnostic(DiagnosticRegion(start, end,
                                            when (item.severity) {
                                                1 -> DiagnosticRegion.SEVERITY_ERROR
                                                2 -> DiagnosticRegion.SEVERITY_WARNING
                                                else -> DiagnosticRegion.SEVERITY_TYPO
                                            },
                                            0, DiagnosticDetail(item.message)))
                                    }
                                }
                                view.diagnostics = marks
                            }
                        }
                    },
                    status = { message ->
                        scope.launch { if (!closed) lspStatus = message }
                    },
                )
                if (generation != lspGeneration || closed) {
                    server.close()
                    return@launch
                }
                lsp = server
                lspStatus = context.getString(R.string.editor_lsp_connected, settings.container)
                server.changed(editor.text.toString(), editor.text.documentVersion)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                lspStatus = failure.localizedMessage ?: context.getString(R.string.editor_lsp_disconnected)
            } finally {
                if (generation == lspGeneration) connecting = false
            }
        }
    }

    fun disconnectLsp() {
        lspGeneration++
        lspJob?.cancel()
        lspJob = null
        lsp?.close()
        lsp = null
        connecting = false
        editorView?.diagnostics = null
        diagnostics = emptyList()
        appContext?.let { lspStatus = it.getString(R.string.editor_lsp_disconnected) }
    }

    fun openDiagnostic(diagnostic: LspDiagnostic) {
        val editor = editorView ?: return
        if (diagnostic.startLine !in 0 until editor.text.lineCount) return
        editor.setSelection(
            diagnostic.startLine,
            diagnostic.startColumn.coerceIn(0, editor.text.getColumnCount(diagnostic.startLine)),
        )
        showDiagnostics = false
    }

    override fun close() {
        if (closed) return
        closed = true
        pendingSave?.invoke(false)
        pendingSave = null
        changeJob?.cancel()
        lspGeneration++
        lspJob?.cancel()
        lspJob = null
        lsp?.close()
        lsp = null
        scope.cancel()
        editorView?.let { editor ->
            if (!editor.isReleased) editor.release()
        }
        editorView = null
    }
}

internal class EditorConflictException : Exception("The file changed outside the editor")

class EditorRendererFactory : RendererFactory {
    override val id: String = "editor"

    override fun supports(target: RenderTarget): Boolean {
        val node = target.node as? VFile ?: return false
        return EditorLanguages.supports(node.name, target.content)
    }

    override suspend fun create(
        target: RenderTarget,
        host: RenderHost,
    ): Result<RendererSession> = runCatching {
        val node = target.node as? VFile
            ?: error("editor renderer requires a file")
        val address = runCatching { LocationAddress.format(node.path) }.getOrNull() ?: node.name
        EditorRendererSession(node, address, host).also { it.load().getOrThrow() }
    }
}

/** Compose projection of an [EditorRendererSession]; the session owns all state. */
@Composable
fun EditorContent(
    session: EditorRendererSession,
    darkTheme: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val background = scheme.surface.toArgb()
    val foreground = scheme.onSurface.toArgb()
    val accent = scheme.primary.toArgb()
    val bracketBackground = scheme.primary
        .copy(alpha = if (darkTheme) 0.32f else 0.18f)
        .toArgb()
    val lineNumberBackground = scheme.surfaceContainerHigh.toArgb()
    val lineHighlight = scheme.surfaceContainerHighest.toArgb()
    val muted = scheme.onSurfaceVariant.toArgb()
    val prefs = remember(context) { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    var preferencesVersion by remember { mutableIntStateOf(0) }

    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == EditorPreferences.FONT_SIZE_KEY || key == EditorPreferences.FONT_KEY ||
                key == EditorPreferences.COMPLETION_KEY) {
                preferencesVersion++
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    LaunchedEffect(preferencesVersion) {
        session.applyPreferences()
    }

    LaunchedEffect(session.query, session.matchCase, session.regex, session.showSearch) {
        session.applySearch()
    }

    Column(modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            key(session) {
                AndroidView(
                    factory = { context ->
                        val editor = session.ensureEditor(context, darkTheme)
                        // A tab switch may compose the new holder before the
                        // previous one releases the view.
                        (editor.parent as? ViewGroup)?.removeView(editor)
                        editor
                    },
                    onRelease = { editor ->
                        (editor.parent as? ViewGroup)?.removeView(editor)
                    },
                    update = { editor ->
                        editor.isEditable = session.document != null && !session.saving && !session.readOnly
                        editor.isWordwrap = session.wrap
                        editor.isScalable = true
                        editor.getComponent(EditorAutoCompletion::class.java)
                            ?.setEnabled(session.completionsEnabled)
                        EditorLanguages.applyColors(
                            editor = editor,
                            dark = darkTheme,
                            background = background,
                            foreground = foreground,
                            accent = accent,
                            bracketBackground = bracketBackground,
                            lineNumberBackground = lineNumberBackground,
                            lineHighlight = lineHighlight,
                            muted = muted,
                        )
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (session.loading || session.saving) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        }
        if (session.showSearch) {
            Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.editor_search),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { session.showSearch = false }) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.cd_close_search),
                        )
                    }
                }
                OutlinedTextField(
                    value = session.query,
                    onValueChange = { session.query = it },
                    label = { Text(stringResource(R.string.editor_find)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = session.replacement,
                    onValueChange = { session.replacement = it },
                    label = { Text(stringResource(R.string.editor_replace)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = session.matchCase, onCheckedChange = { session.matchCase = it })
                    Text("Aa")
                    Checkbox(checked = session.regex, onCheckedChange = { session.regex = it })
                    Text(".*")
                    TextButton(onClick = session::gotoPrevious, enabled = session.matchCount > 0) { Text("↑") }
                    TextButton(onClick = session::gotoNext, enabled = session.matchCount > 0) { Text("↓") }
                    TextButton(
                        onClick = session::replaceCurrent,
                        enabled = session.matchCount > 0 && !session.saving && !session.readOnly,
                    ) {
                        Text(stringResource(R.string.editor_replace))
                    }
                    TextButton(
                        onClick = session::replaceAll,
                        enabled = session.matchCount > 0 && !session.saving && !session.readOnly,
                    ) {
                        Text(stringResource(R.string.editor_replace_all))
                    }
                    Text("${session.matchCount}")
                }
                session.searchError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    }

    session.error?.let { message ->
        AlertDialog(
            onDismissRequest = { session.error = null },
            title = { Text(stringResource(R.string.editor_error)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { session.error = null }) { Text(stringResource(R.string.confirm)) }
            },
        )
    }
    if (session.conflict) {
        AlertDialog(
            onDismissRequest = session::dismissConflict,
            title = { Text(stringResource(R.string.editor_conflict)) },
            text = { Text(stringResource(R.string.editor_conflict_message)) },
            confirmButton = {
                TextButton(onClick = session::overwrite) {
                    Text(stringResource(R.string.editor_overwrite))
                }
            },
            dismissButton = {
                TextButton(onClick = session::dismissConflict) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
    if (session.showLanguagePicker) {
        AlertDialog(
            onDismissRequest = { session.showLanguagePicker = false },
            title = { Text(stringResource(R.string.editor_file_type)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    EditorLanguages.languageIds.forEach { id ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    session.setLanguage(context, id, darkTheme)
                                    session.showLanguagePicker = false
                                }
                                .padding(vertical = 4.dp),
                        ) {
                            RadioButton(
                                selected = session.languageId == id,
                                onClick = null,
                            )
                            Text(EditorLanguages.label(context, id))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { session.showLanguagePicker = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
    if (session.showDiagnostics) {
        AlertDialog(
            onDismissRequest = { session.showDiagnostics = false },
            title = { Text(stringResource(R.string.editor_diagnostics, session.diagnostics.size)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (session.diagnostics.isEmpty()) {
                        Text(stringResource(R.string.editor_no_diagnostics))
                    }
                    session.diagnostics.forEach { item ->
                        TextButton(onClick = { session.openDiagnostic(item) }) {
                            Text("${item.startLine + 1}:${item.startColumn + 1} ${item.message}")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { session.showDiagnostics = false }) {
                    Text(stringResource(R.string.confirm))
                }
            },
        )
    }
    if (session.showGoto) {
        val lineCount = session.currentEditor()?.text?.lineCount ?: 0
        AlertDialog(
            onDismissRequest = { session.showGoto = false },
            title = { Text(stringResource(R.string.editor_goto)) },
            text = {
                OutlinedTextField(
                    value = session.gotoLine,
                    onValueChange = { session.gotoLine = it.filter(Char::isDigit) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = session.gotoLine.toIntOrNull()?.let { it in 1..lineCount } == true,
                    onClick = {
                        session.gotoLine.toIntOrNull()?.let { line ->
                            session.currentEditor()?.setSelection(line - 1, 0)
                        }
                        session.showGoto = false
                    },
                ) { Text(stringResource(R.string.confirm)) }
            },
        )
    }
}
