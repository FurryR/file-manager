package io.furryr.file.editor

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.furryr.file.FileRepository
import io.furryr.file.R
import io.furryr.file.agent.ContainerManager
import io.furryr.file.provider.installFileProviders
import io.furryr.file.ui.theme.FileManagerTheme
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.EditorKeyEvent
import io.github.rosemoe.sora.event.PublishSearchResultEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticDetail
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticRegion
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticsContainer
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher
import io.github.rosemoe.sora.widget.component.EditorAutoCompletion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Native Sora editor. Provider paths are opaque; all file I/O stays in FileRepository. */
class EditorActivity : ComponentActivity() {
    private lateinit var buffer: EditorBuffer
    private var editorView: CodeEditor? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installFileProviders(this)
        val path = intent.getStringExtra(EXTRA_PATH)?.takeIf { it.isNotBlank() } ?: run { finish(); return }
        @Suppress("DEPRECATION")
        buffer = (lastCustomNonConfigurationInstance as? EditorBuffer)?.takeIf { it.path == path } ?: EditorBuffer(path)
        setContent { FileManagerTheme { EditorScreen(buffer) } }
    }

    @Deprecated("Retains the native text and undo history across activity recreation")
    override fun onRetainCustomNonConfigurationInstance(): Any {
        editorView?.let { buffer.content = it.text }
        return buffer
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun EditorScreen(buffer: EditorBuffer) {
        val scope = rememberCoroutineScope()
        val language = remember { EditorLanguages.id(buffer.path) }
        val handle = remember { ServerHandle() }
        var error by buffer::error
        var loading by remember { mutableStateOf(buffer.document == null) }
        val saving = buffer.saving
        var revision by remember { mutableIntStateOf(0) }
        var cursorPosition by remember { mutableStateOf(0 to 0) }
        var showExit by remember { mutableStateOf(false) }
        var conflict by buffer::conflict
        var showSearch by remember { mutableStateOf(false) }
        var query by remember { mutableStateOf("") }
        var replacement by remember { mutableStateOf("") }
        var matchCase by remember { mutableStateOf(false) }
        var regex by remember { mutableStateOf(false) }
        var searchError by remember { mutableStateOf<String?>(null) }
        var matchCount by remember { mutableIntStateOf(0) }
        var more by remember { mutableStateOf(false) }
        var showLspSettings by remember { mutableStateOf(false) }
        var showDiagnostics by remember { mutableStateOf(false) }
        var showGoto by remember { mutableStateOf(false) }
        var gotoLine by remember { mutableStateOf("") }
        var lspStatus by remember { mutableStateOf(getString(R.string.editor_lsp_disconnected)) }
        var connecting by remember { mutableStateOf(false) }
        var diagnostics by remember { mutableStateOf<List<LspDiagnostic>>(emptyList()) }
        var changeJob by remember { mutableStateOf<Job?>(null) }
        val editor = remember {
            CodeEditor(this).apply {
                setTextSize(14f)
                typefaceText = Typeface.MONOSPACE
                isLineNumberEnabled = true
                isWordwrap = buffer.wrap
                tabWidth = 4
                isEditable = buffer.document != null
                buffer.content?.let { setText(it, true, null) }
                EditorLanguages.configure(this@EditorActivity, this, language) { handle.session }
            }.also { editorView = it }
        }
        val dirty = remember(revision, buffer.document) {
            buffer.document?.let { editor.text.toString() != it.text } ?: false
        }
        val canUndo = remember(revision) { editor.text.canUndo() }
        val canRedo = remember(revision) { editor.text.canRedo() }

        fun updateState() {
            if (editor.isReleased) return
            buffer.content = editor.text
            cursorPosition = editor.cursor.leftLine to editor.cursor.leftColumn
            revision++
        }

        fun reportFailure(failure: Exception) {
            if (failure is CancellationException) throw failure
            error = failure.localizedMessage ?: failure.javaClass.simpleName
        }

        fun requestExit() {
            if (buffer.saving) return
            if (buffer.document != null && editor.text.toString() != buffer.document!!.text) showExit = true else finish()
        }

        fun save(overwrite: Boolean = false, exitAfter: Boolean = false) {
            val original = buffer.document ?: return
            if (buffer.saving) return
            val text = editor.text.toString()
            val documentRevision = editor.text.documentVersion
            buffer.saving = true
            editor.isEditable = false
            // A save owns an immutable snapshot and survives Activity recreation.
            // Cancelling a UI coroutine in the middle of an upload could truncate a remote file.
            buffer.saveScope.launch {
                try {
                    val encoded = withContext(Dispatchers.IO) { original.encode(text) }
                    if (!overwrite && !EditorFiles.isUnchanged(buffer.path, original.digest)) {
                        conflict = exitAfter
                        return@launch
                    }
                    withContext(Dispatchers.IO) { FileRepository.writeBytes(buffer.path, encoded,
                        if (overwrite) null else original.digest.joinToString("") { "%02x".format(it) }).getOrThrow() }
                    buffer.document = withContext(Dispatchers.IO) { TextDocument.decode(encoded) }
                    buffer.savedRevision = documentRevision
                    buffer.exitAfterSave = exitAfter
                    buffer.savedGeneration++
                } catch (failure: Exception) {
                    reportFailure(failure)
                } finally {
                    buffer.saving = false
                }
            }
        }

        LaunchedEffect(buffer.savedGeneration) {
            if (buffer.savedGeneration > 0) {
                Toast.makeText(this@EditorActivity, R.string.editor_saved, Toast.LENGTH_SHORT).show()
                handle.session?.let { server ->
                    try { server.saved(buffer.document!!.text, buffer.savedRevision) }
                    catch (failure: Exception) {
                        if (failure is CancellationException) throw failure
                        lspStatus = failure.message ?: getString(R.string.editor_lsp_disconnected)
                    }
                }
                if (buffer.exitAfterSave) finish()
            }
        }

        fun connect(settings: LspSettings) {
            if (connecting || buffer.document == null) return
            settings.save(this@EditorActivity, language)
            buffer.lspSettings = settings
            handle.session?.close()
            handle.session = null
            diagnostics = emptyList()
            editor.diagnostics = null
            connecting = true
            lspStatus = getString(R.string.editor_lsp_connecting)
            val initialText = editor.text.toString()
            val initialRevision = editor.text.documentVersion
            scope.launch {
                try {
                    val server = LspSession.start(settings, language, initialText, initialRevision,
                        diagnostics = { items, serverRevision ->
                            runOnUiThread {
                                if (!editor.isReleased && editor.text.documentVersion == serverRevision) {
                                    diagnostics = items
                                    val marks = DiagnosticsContainer()
                                    items.forEach { item ->
                                        val content = editor.text
                                        if (item.startLine in 0 until content.lineCount && item.endLine in 0 until content.lineCount) {
                                            val start = content.getCharIndex(item.startLine, item.startColumn.coerceIn(0, content.getColumnCount(item.startLine)))
                                            val end = content.getCharIndex(item.endLine, item.endColumn.coerceIn(0, content.getColumnCount(item.endLine)))
                                            if (end >= start) marks.addDiagnostic(DiagnosticRegion(start, end,
                                                when (item.severity) { 1 -> DiagnosticRegion.SEVERITY_ERROR; 2 -> DiagnosticRegion.SEVERITY_WARNING; else -> DiagnosticRegion.SEVERITY_TYPO },
                                                0, DiagnosticDetail(item.message)))
                                        }
                                    }
                                    editor.diagnostics = marks
                                }
                            }
                        }, status = { message -> runOnUiThread { if (!editor.isReleased) lspStatus = message } })
                    handle.session = server
                    lspStatus = getString(R.string.editor_lsp_connected, settings.container)
                    server.changed(editor.text.toString(), editor.text.documentVersion)
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    lspStatus = failure.localizedMessage ?: getString(R.string.editor_lsp_disconnected)
                } finally { connecting = false }
            }
        }

        LaunchedEffect(Unit) {
            if (buffer.document == null) {
                try {
                    val bytes = EditorFiles.read(buffer.path)
                    val document = withContext(Dispatchers.IO) { TextDocument.decode(bytes) }
                    buffer.document = document
                    editor.setText(document.text)
                    buffer.content = editor.text
                    editor.isEditable = true
                    updateState()
                } catch (failure: Exception) { reportFailure(failure) }
                finally { loading = false }
            }
            buffer.lspSettings?.let { connect(it) }
        }

        DisposableEffect(editor) {
            editor.subscribeEvent(ContentChangeEvent::class.java) { _, _ ->
                editor.post {
                    if (!editor.isReleased) {
                        updateState()
                        editor.diagnostics = null
                        diagnostics = emptyList()
                        changeJob?.cancel()
                        handle.session?.let { server ->
                            val snapshot = editor.text.toString()
                            val version = editor.text.documentVersion
                            changeJob = scope.launch {
                                delay(180)
                                try { server.changed(snapshot, version) }
                                catch (failure: Exception) {
                                    if (failure is CancellationException) throw failure
                                    lspStatus = failure.message ?: getString(R.string.editor_lsp_disconnected)
                                }
                            }
                        }
                    }
                }
            }
            editor.subscribeEvent(SelectionChangeEvent::class.java) { _, _ ->
                cursorPosition = editor.cursor.leftLine to editor.cursor.leftColumn
            }
            editor.subscribeEvent(PublishSearchResultEvent::class.java) { _, _ -> matchCount = editor.searcher.matchedPositionCount }
            editor.subscribeEvent(EditorKeyEvent::class.java) { event, _ ->
                if (event.action == KeyEvent.ACTION_DOWN && event.isCtrlPressed) {
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_S -> { save(); event.intercept(); event.setResult(true) }
                        KeyEvent.KEYCODE_F -> { showSearch = true; event.intercept(); event.setResult(true) }
                    }
                }
            }
            onDispose {
                buffer.content = editor.text
                changeJob?.cancel()
                handle.session?.close()
                handle.session = null
                editor.release()
                if (editorView === editor) editorView = null
            }
        }

        LaunchedEffect(query, matchCase, regex, showSearch) {
            searchError = null
            if (!showSearch || query.isEmpty()) { editor.searcher.stopSearch(); matchCount = 0 }
            else try { editor.searcher.search(query, EditorSearcher.SearchOptions(!matchCase, regex)) }
            catch (failure: Exception) { editor.searcher.stopSearch(); searchError = failure.message }
        }
        BackHandler { requestExit() }

        Scaffold(topBar = {
            TopAppBar(title = { Text((if (dirty) "• " else "") + buffer.path.substringAfterLast('/'), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = { requestExit() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.editor_close)) } },
                actions = {
                    IconButton(onClick = { save() }, enabled = !loading && !saving && dirty) { Icon(Icons.Default.Save, stringResource(R.string.editor_save)) }
                    IconButton(onClick = { editor.undo() }, enabled = !saving && canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.editor_undo)) }
                    IconButton(onClick = { editor.redo() }, enabled = !saving && canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, stringResource(R.string.editor_redo)) }
                    IconButton(onClick = { showSearch = !showSearch }, enabled = !loading) { Icon(Icons.Default.Search, stringResource(R.string.editor_search)) }
                    Box {
                        IconButton(onClick = { more = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.editor_more)) }
                        DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.editor_lsp_settings)) }, onClick = { more = false; showLspSettings = true })
                            DropdownMenuItem(text = { Text(stringResource(R.string.editor_complete)) }, onClick = { more = false; editor.getComponent(EditorAutoCompletion::class.java).requireCompletion() })
                            DropdownMenuItem(text = { Text(stringResource(R.string.editor_diagnostics, diagnostics.size)) }, onClick = { more = false; showDiagnostics = true })
                            DropdownMenuItem(text = { Text(stringResource(R.string.editor_lsp_disconnect)) }, enabled = handle.session != null, onClick = {
                                more = false; handle.session?.close(); handle.session = null; buffer.lspSettings = null
                                editor.diagnostics = null; diagnostics = emptyList(); lspStatus = getString(R.string.editor_lsp_disconnected)
                            })
                            DropdownMenuItem(text = { Text(stringResource(R.string.editor_goto)) }, onClick = { more = false; gotoLine = ""; showGoto = true })
                            DropdownMenuItem(text = { Text(stringResource(R.string.editor_wrap)) }, onClick = { more = false; buffer.wrap = !buffer.wrap; editor.isWordwrap = buffer.wrap })
                            DropdownMenuItem(text = { Text(stringResource(R.string.editor_font_larger)) }, onClick = { more = false; editor.setTextSize((editor.textSizePx / resources.displayMetrics.scaledDensity + 1).coerceAtMost(48f)) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.editor_font_smaller)) }, onClick = { more = false; editor.setTextSize((editor.textSizePx / resources.displayMetrics.scaledDensity - 1).coerceAtLeast(8f)) })
                        }
                    }
                })
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (showSearch) {
                    Column(Modifier.padding(horizontal = 8.dp)) {
                        OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text(stringResource(R.string.editor_find)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = replacement, onValueChange = { replacement = it }, label = { Text(stringResource(R.string.editor_replace)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = matchCase, onCheckedChange = { matchCase = it }); Text("Aa")
                            Checkbox(checked = regex, onCheckedChange = { regex = it }); Text(".*")
                            TextButton(onClick = { editor.searcher.gotoPrevious() }, enabled = matchCount > 0) { Text("↑") }
                            TextButton(onClick = { editor.searcher.gotoNext() }, enabled = matchCount > 0) { Text("↓") }
                            TextButton(onClick = { editor.searcher.replaceCurrentMatch(replacement) }, enabled = matchCount > 0 && !saving) { Text(stringResource(R.string.editor_replace)) }
                            TextButton(onClick = { editor.searcher.replaceAll(replacement) }, enabled = matchCount > 0 && !saving) { Text(stringResource(R.string.editor_replace_all)) }
                            Text("$matchCount")
                        }
                        searchError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    AndroidView(factory = { editor }, update = { it.isEditable = buffer.document != null && !saving }, modifier = Modifier.fillMaxSize())
                    if (loading || saving) CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
                Text(buffer.path, modifier = Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${buffer.document?.formatLabel.orEmpty()} · ${cursorPosition.first + 1}:${cursorPosition.second + 1}", modifier = Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.labelSmall)
                Text(lspStatus, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }

        if (showExit) AlertDialog(onDismissRequest = { showExit = false }, title = { Text(stringResource(R.string.editor_unsaved)) },
            text = { Text(stringResource(R.string.editor_unsaved_message)) },
            confirmButton = { TextButton(onClick = { showExit = false; save(exitAfter = true) }) { Text(stringResource(R.string.editor_save)) } },
            dismissButton = { Row {
                TextButton(onClick = { showExit = false }) { Text(stringResource(R.string.cancel)) }
                TextButton(onClick = { showExit = false; finish() }) { Text(stringResource(R.string.editor_discard)) }
            } })
        conflict?.let { exitAfter -> AlertDialog(onDismissRequest = { conflict = null }, title = { Text(stringResource(R.string.editor_conflict)) },
            text = { Text(stringResource(R.string.editor_conflict_message)) },
            confirmButton = { TextButton(onClick = { conflict = null; save(overwrite = true, exitAfter = exitAfter) }) { Text(stringResource(R.string.editor_overwrite)) } },
            dismissButton = { TextButton(onClick = { conflict = null }) { Text(stringResource(R.string.cancel)) } }) }
        error?.let { message -> AlertDialog(onDismissRequest = { error = null }, title = { Text(stringResource(R.string.editor_error)) }, text = { Text(message) },
            confirmButton = { TextButton(onClick = { error = null }) { Text(stringResource(R.string.confirm)) } }) }
        if (showLspSettings) LspSettingsDialog(buffer.lspSettings ?: LspSettings.load(this, buffer.path, language), connecting,
            onDismiss = { showLspSettings = false }, onConnect = { showLspSettings = false; connect(it) })
        if (showDiagnostics) AlertDialog(onDismissRequest = { showDiagnostics = false }, title = { Text(stringResource(R.string.editor_diagnostics, diagnostics.size)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                if (diagnostics.isEmpty()) Text(stringResource(R.string.editor_no_diagnostics))
                diagnostics.forEach { item -> TextButton(onClick = {
                    if (item.startLine in 0 until editor.text.lineCount) editor.setSelection(item.startLine, item.startColumn.coerceIn(0, editor.text.getColumnCount(item.startLine)))
                    showDiagnostics = false
                }) { Text("${item.startLine + 1}:${item.startColumn + 1} ${item.message}") } }
            } }, confirmButton = { TextButton(onClick = { showDiagnostics = false }) { Text(stringResource(R.string.confirm)) } })
        if (showGoto) AlertDialog(onDismissRequest = { showGoto = false }, title = { Text(stringResource(R.string.editor_goto)) },
            text = { OutlinedTextField(value = gotoLine, onValueChange = { gotoLine = it.filter(Char::isDigit) }, singleLine = true) },
            confirmButton = { TextButton(enabled = gotoLine.toIntOrNull()?.let { it in 1..editor.text.lineCount } == true, onClick = { editor.setSelection(gotoLine.toInt() - 1, 0); showGoto = false }) { Text(stringResource(R.string.confirm)) } })
    }

    @Composable
    private fun LspSettingsDialog(initial: LspSettings, connecting: Boolean, onDismiss: () -> Unit, onConnect: (LspSettings) -> Unit) {
        var container by remember { mutableStateOf(initial.container) }
        var command by remember { mutableStateOf(initial.command) }
        var root by remember { mutableStateOf(initial.root) }
        var documentPath by remember { mutableStateOf(initial.documentPath) }
        var containers by remember { mutableStateOf<List<String>>(emptyList()) }
        var menu by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { containers = withContext(Dispatchers.IO) { ContainerManager.listContainers(this@EditorActivity).getOrDefault(emptyList()).map { it.name } } }
        val settings = LspSettings(container.trim(), command.trim(), root, documentPath)
        AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.editor_lsp_settings)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.editor_lsp_hint), style = MaterialTheme.typography.bodySmall)
                Box {
                    OutlinedTextField(value = container, onValueChange = { container = it }, singleLine = true, label = { Text(stringResource(R.string.editor_lsp_container)) }, modifier = Modifier.fillMaxWidth(),
                        trailingIcon = { TextButton(onClick = { menu = true }) { Text("▾") } })
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) { containers.forEach { name ->
                        DropdownMenuItem(text = { Text(name) }, onClick = { container = name; menu = false })
                    } }
                }
                OutlinedTextField(value = command, onValueChange = { command = it }, label = { Text(stringResource(R.string.editor_lsp_command)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = root, onValueChange = { root = it }, label = { Text(stringResource(R.string.editor_lsp_root)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = documentPath, onValueChange = { documentPath = it }, label = { Text(stringResource(R.string.editor_lsp_document)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            } },
            confirmButton = { TextButton(onClick = { onConnect(settings) }, enabled = settings.valid && !connecting) { Text(stringResource(R.string.editor_lsp_connect)) } },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
    }

    companion object {
        private const val EXTRA_PATH = "editor.path"
        fun open(context: Context, path: String) {
            context.startActivity(Intent(context, EditorActivity::class.java).putExtra(EXTRA_PATH, path).apply {
                if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }
}

private class EditorBuffer(val path: String) {
    var document by mutableStateOf<TextDocument?>(null)
    var saving by mutableStateOf(false)
    var conflict by mutableStateOf<Boolean?>(null)
    var error by mutableStateOf<String?>(null)
    var savedGeneration by mutableIntStateOf(0)
    var savedRevision = -1L
    var exitAfterSave = false
    val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var content: Content? = null
    var wrap = false
    var lspSettings: LspSettings? = null
}

private class ServerHandle { @Volatile var session: LspSession? = null }
