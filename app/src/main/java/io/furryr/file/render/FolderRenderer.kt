package io.furryr.file.render

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.furryr.file.ui.icons.Icons
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.furryr.file.R
import io.furryr.file.clipboard.ClipboardAction
import io.furryr.file.clipboard.ClipboardState
import io.furryr.file.clipboard.ClipboardSink
import io.furryr.file.clipboard.ClipboardStore
import io.furryr.file.core.DirectoryStats
import io.furryr.file.core.Path
import io.furryr.file.core.VFolder
import io.furryr.file.core.Vfs
import io.furryr.file.core.model.FileEntry
import io.furryr.file.home.HomeEntry
import io.furryr.file.home.HomeEntryKind
import io.furryr.file.isHomePath
import io.furryr.file.model.PageKind
import io.furryr.file.model.summary
import io.furryr.file.provider.terminal.ExecProvider
import io.furryr.file.terminal.TermuxTerminalTab
import io.furryr.file.ui.components.BatchRenameDialog
import io.furryr.file.ui.components.ContextMenu
import io.furryr.file.ui.components.CreateDialog
import io.furryr.file.ui.components.DeleteConfirmDialog
import io.furryr.file.ui.components.RenameDialog
import io.furryr.file.ui.util.errorText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Inputs the shell supplies to the directory renderer. Entries, selection,
 * sort, filter and scroll live in [FolderListing] (the session); the shell
 * only supplies the location and the navigation highlights.
 */
data class FolderRendererState(
    val path: String,
    val committedPath: String = path,
    val scrollToPath: String? = null,
    val highlightPath: String? = null,
    val highlightDirectory: String? = null,
    val opening: Boolean = false,
)

/**
 * A directory page. The path and the name known at open time are enough to
 * show a title before the listing loads; [cachedInfo] carries volume facts
 * from the folder that opened this one, so the first frame already has the
 * right free-space summary.
 */
class FolderPageState(
    var path: Path,
    var title: String? = null,
    @Transient var cachedInfo: DirectoryStats? = null,
    @Transient var folder: VFolder? = null,
) : PageState() {
    constructor(path: String, title: String? = null) : this(Path.parse(path), title)

    /** A state seeded from its node: path and title come from the folder. */
    constructor(folder: VFolder) : this(
        path = folder.path,
        title = folder.name,
        folder = folder,
    )
}

/** A folder title available before the listing loads. */
private fun String.pathLabel(): String? {
    if (isBlank()) return null
    val parsed = runCatching { Path.parse(this) }.getOrNull()
    return parsed?.name?.takeIf { it.isNotBlank() }
        ?: substringAfterLast('/').ifBlank { this }
}

/**
 * A folder opened from a loaded listing shares that listing's volume. Carry
 * its already-known facts into the new page state so the first frame keeps
 * the same free-space summary instead of waiting for another stat.
 */
private fun seedVolumeFacts(renderer: RendererBase<*>, listing: FolderListing) {
    val target = renderer as? FolderRenderer ?: return
    if (target.state.path.toString().isBlank()) return
    val current = listing.loadedPath ?: return
    val currentPath = runCatching { Path.parse(current) }.getOrNull() ?: return
    val targetPath = target.state.path
    if (currentPath.isViewed || targetPath.isViewed) return
    if (targetPath.parent() != currentPath) return
    val info = listing.directoryInfo
    if (info.totalBytes <= 0 && info.usableBytes <= 0) return
    target.state.cachedInfo = info
}

/**
 * Resource half of a directory page: the listing, its scope and the host
 * binding. It carries no UI: menus, dialogs and titles belong to
 * [FolderRenderer], which keeps them alive across tab switches exactly like
 * the session keeps the listing.
 */
internal class FolderRendererSession(
    private val vfs: Vfs,
    initialSelection: Set<String>,
    initialSelectedIndex: Int,
    initialState: FolderPageState? = null,
) : PageSession<FolderPageState>(initialState) {
    internal var host: RendererContext? = null
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    internal val listing = FolderListing(
        vfs = vfs,
        scope = scope,
        onSelectionChanged = { paths, index, entries ->
            host?.onSelectionChanged(paths, index, entries)
        },
        onError = onError@ { error ->
            val currentHost = host ?: return@onError
            val address = state.path.toString()
            if (address.isBlank()) return@onError
            currentHost.replace(ErrorRenderer(address, error, locationPage = true))
        },
    )

    init {
        listing.seedSelection(initialSelection, initialSelectedIndex)
    }

    internal val state: FolderPageState = seedState ?: FolderPageState("")

    override val isLocationRenderer: Boolean get() = true

    internal val entries: List<FileEntry> get() = listing.entries
    internal val selectedEntries: List<FileEntry> get() = listing.selectedEntries
    internal val selectedPaths: Set<String> get() = listing.selectedPaths
    internal fun selectAll() = listing.selectAll()
    internal fun invertSelection() = listing.invertSelection()
    internal fun selectSameType() = listing.selectSameType()
    internal fun setSelection(paths: Set<String>, index: Int) = listing.setSelection(paths, index)
    internal fun clearSelection() = listing.clearSelection()
    internal fun clearDisplayedSelection() = listing.clearDisplayedSelection()

    internal fun refreshListing(
        animate: Boolean,
        highlightPath: String?,
        highlightDirectory: String?,
    ) {
        listing.refresh(
            animate = animate,
            highlightPath = highlightPath,
            highlightDirectory = highlightDirectory,
        )
    }

    internal fun useHost(host: RendererContext) {
        this.host = host
    }

    override fun onRelease() {
        listing.close()
    }

    override fun close() {
        listing.close()
        scope.cancel()
    }

    override fun reset() {
        host = null
    }
}

/**
 * A directory tab as a normal renderer.
 *
 * The session owns the listing and selection, so switching tabs keeps both.
 * Every menu, dialog and title mode lives here, next to the view that draws
 * it; the shell only pushes [FolderRendererState].
 */
class FolderRenderer(
    private val vfs: Vfs,
    private val initialSelection: Set<String> = emptySet(),
    private val initialSelectedIndex: Int = -1,
    initialState: FolderPageState? = null,
) : RendererBase<FolderPageState>(initialState), SelectCapabilities {
    val state: FolderPageState get() = folderSession.state
    override val pagePath: String get() = state.path.toString()
    override val pageKind: PageKind = PageKind.LOCATION

    override val entries: List<FileEntry> get() = listing.entries
    override val selectedEntries: List<FileEntry> get() = listing.selectedEntries
    override val selectedPaths: Set<String> get() = listing.selectedPaths
    override fun selectAll() = listing.selectAll()
    override fun invertSelection() = listing.invertSelection()
    override fun selectSameType() = listing.selectSameType()
    override fun setSelection(paths: Set<String>, index: Int) = listing.setSelection(paths, index)
    override fun clearSelection() = listing.clearSelection()
    override fun clearDisplayedSelection() = listing.clearDisplayedSelection()
    override fun refreshListing(
        animate: Boolean,
        highlightPath: String?,
        highlightDirectory: String?,
    ) {
        listing.refresh(
            animate = animate,
            highlightPath = highlightPath,
            highlightDirectory = highlightDirectory,
        )
    }

    override fun createSession(state: FolderPageState?): PageSession<FolderPageState> =
        FolderRendererSession(vfs, initialSelection, initialSelectedIndex, state)

    private val folderSession: FolderRendererSession get() = session as FolderRendererSession
    internal val listing: FolderListing get() = folderSession.listing

    internal fun bindHost(host: RendererContext) = folderSession.useHost(host)

    /** Keep the page's durable path in step with the shell's location. */
    internal fun syncPath(path: String) {
        state.path = Path.parse(path)
    }

    /** The node this page was opened from, if any. */
    internal fun folderNode(): VFolder? = state.folder

    /** True for structural views and folder roots with no modify operations. */
    internal fun isReadOnly(): Boolean =
        state.folder?.addressable == false ||
            (listing.loadedCapabilities.rename == null && listing.loadedCapabilities.delete == null)

    override val isLocationRenderer: Boolean get() = true

    /** Long-press opens the renderer's own entry menu, not a shell dialog. */
    var menuEntry by mutableStateOf<FileEntry?>(null)
        internal set

    /** The renderer's own create dialog, opened by the "+" slot. */
    var showCreateDialog by mutableStateOf(false)
        internal set

    /** The renderer's own sort dialog, opened by its menu. */
    var showSortDialog by mutableStateOf(false)
        internal set

    /** The renderer's own delete confirmation. */
    var deleteEntries by mutableStateOf<List<FileEntry>?>(null)
        internal set

    /** The renderer's own single-entry rename dialog. */
    var renameEntry by mutableStateOf<FileEntry?>(null)
        internal set

    /** The renderer's own batch rename dialog and its frozen targets. */
    var batchRenameEntries by mutableStateOf<List<FileEntry>?>(null)
        internal set

    private var filterText by mutableStateOf("")
    private var filterOpen by mutableStateOf(false)

    private fun setFilter(text: String) {
        filterText = text
        listing.setFilter(text)
    }

    private fun toggleFilter() {
        filterOpen = !filterOpen
        if (!filterOpen) setFilter("")
    }

    private fun closeFilter() {
        filterOpen = false
        setFilter("")
    }

    override suspend fun enter(host: RendererContext) {
        // A filter and its row animation belong to the frame that showed them.
        // Reset both before the new body commits, so no stale overlay or
        // entrance animation survives the transition.
        closeFilter()
        listing.finishAnimation()
        listing.lockSelection(ClipboardStore.state.inTransfer)
        host.commit { h, dark, modifier ->
            FolderSessionView(this).Content(h, dark, modifier)
        }
    }

    override val locationInfo: DirectoryStats
        get() {
            val live = listing.directoryInfo
            if (live.totalBytes > 0 || live.usableBytes > 0) return live
            return state.cachedInfo ?: live
        }

    override fun bookmarkEntry(): HomeEntry? {
        val path = listing.loadedPath ?: state.path.toString()
        if (path.isBlank() || isHomePath(path)) return null
        val label = state.title?.takeIf { it.isNotBlank() }
            ?: state.path.name
            ?: path
        return HomeEntry(HomeEntryKind.FOLDER, path, label, path)
    }

    override val title: String?
        get() {
            val shown = folderSession.host?.folderView()
            return state.folder?.name?.takeIf { it.isNotBlank() }
                ?: state.title?.takeIf { it.isNotBlank() }
                ?: shown?.path?.pathLabel()
                ?: state.path.toString().pathLabel()
        }

    override fun pathBar(context: Context): RendererPath? {
        val path = folderSession.host?.folderView()?.path ?: state.path.toString()
        if (path.isBlank()) return null
        return RendererPath(
            title = path,
            caption = locationInfo.summary(context).takeIf { it.isNotEmpty() },
            editText = path,
        )
    }

    override fun titleSlot(): TitleSlot? {
        if (!filterOpen) return null
        return { modifier ->
            FilterTitle(
                text = filterText,
                onTextChange = ::setFilter,
                onClose = ::closeFilter,
                modifier = modifier,
            )
        }
    }

    internal val terminalPath: String?
        get() = listing.loadedPath?.takeIf {
            val folder = listing.loadedFolder ?: return@takeIf false
            val exec = folder.provider as? ExecProvider ?: return@takeIf false
            runCatching { exec.terminalPath(folder, folder) }.getOrNull() != null
        }

    override fun matchesLocation(path: String): Boolean = !isHomePath(path)

    override fun menuItems(context: Context): List<RendererMenuItem> = buildList {
        add(
            RendererMenuItem(
                title = context.getString(R.string.refresh),
                icon = Icons.Default.Refresh,
                onClick = { listing.refresh(animate = true) },
            ),
        )
        add(
            RendererMenuItem(
                title = context.getString(R.string.filter_title),
                icon = Icons.Default.FilterAlt,
                onClick = ::toggleFilter,
            ),
        )
        add(
            RendererMenuItem(
                title = context.getString(R.string.sort_title),
                icon = Icons.AutoMirrored.Filled.Sort,
                onClick = { showSortDialog = true },
            ),
        )
        bookmarkMenuItem(context, bookmarkEntry())?.let(::add)
    }

    override val capabilities: RenderCapabilities
        get() {
            val folder = listing.loadedFolder
            val up = folder?.parent?.let { parent -> { openParent(parent) } }
            val terminal = folder?.let { current ->
                val exec = current.provider as? ExecProvider ?: return@let null
                if (runCatching { exec.terminalPath(current, current) }.getOrNull() == null) {
                    return@let null
                }
                TerminalFactory { ctx ->
                    TermuxTerminalTab.start(ctx, current.name, exec, current, vfs = vfs) { rows, cols ->
                        exec.terminal(current, rows, cols)
                    }
                }
            }
            val caps = listing.loadedCapabilities
            return RenderCapabilities(
                create = if (caps.createFile != null || caps.createDirectory != null) {
                    { showCreateDialog = true }
                } else {
                    null
                },
                rename = if (caps.rename != null) ::requestRename else null,
                delete = if (caps.delete != null) ::requestDelete else null,
                up = up,
                terminal = terminal,
                select = this,
            )
        }

    /** Up: route the owning folder and append it as a new stop. */
    private fun openParent(parent: VFolder) {
        val session = folderSession
        val host = session.host ?: return
        session.scope.launch {
            try {
                host.open(host.opener.route(parent))
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                host.reportError(error)
            }
        }
    }

    internal fun createEntry(context: Context, name: String, directory: Boolean) {
        val caps = listing.loadedCapabilities
        showCreateDialog = false
        folderSession.scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (directory) {
                        caps.createDirectory?.invoke(name)?.getOrThrow()
                            ?: error("cannot create a folder here")
                    } else {
                        caps.createFile?.invoke(name)?.getOrThrow()
                            ?: error("cannot create a file here")
                    }
                }
            }
            Toast.makeText(
                context,
                result.exceptionOrNull()?.let { context.errorText(it) }
                    ?: context.getString(R.string.toast_created, name),
                Toast.LENGTH_SHORT,
            ).show()
            listing.refresh(animate = true)
        }
    }

    /** Open the renderer's own delete confirmation for [entries]. */
    internal fun requestDelete(entries: List<FileEntry>) {
        deleteEntries = entries
    }

    /** Open a single rename, or the batch rename when several are selected. */
    internal fun requestRename(entries: List<FileEntry>) {
        if (entries.size > 1) {
            batchRenameEntries = entries
        } else {
            renameEntry = entries.firstOrNull()
        }
    }

    internal fun delete(context: Context, entries: List<FileEntry>) {
        deleteEntries = null
        folderSession.scope.launch {
            var failure: Throwable? = null
            withContext(Dispatchers.IO) {
                val delete = listing.loadedCapabilities.delete ?: error("cannot delete here")
                for (entry in entries) {
                    val result = runCatching { delete(entry.name).getOrThrow() }
                    if (result.isFailure) {
                        failure = result.exceptionOrNull()
                        break
                    }
                }
            }
            val error = failure
            Toast.makeText(
                context,
                when {
                    error != null -> context.errorText(error)
                    entries.size == 1 -> context.getString(R.string.toast_deleted, entries.first().name)
                    else -> context.getString(R.string.toast_deleted_count, entries.size)
                },
                Toast.LENGTH_SHORT,
            ).show()
            listing.clearSelection()
            listing.refresh(animate = true)
        }
    }

    internal fun rename(context: Context, entry: FileEntry, newName: String) {
        renameEntry = null
        folderSession.scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val rename = listing.loadedCapabilities.rename ?: error("cannot rename here")
                    rename(entry.name, newName).getOrThrow()
                }
            }
            Toast.makeText(
                context,
                if (result.isSuccess) context.getString(R.string.toast_renamed, newName)
                else context.errorText(result.exceptionOrNull()),
                Toast.LENGTH_SHORT,
            ).show()
            listing.refresh(animate = true)
        }
    }

    internal fun batchRename(context: Context, entries: List<FileEntry>, pattern: String, replacement: String) {
        batchRenameEntries = null
        val regex = runCatching { Regex(pattern) }.getOrNull()
        folderSession.scope.launch {
            val failures = if (regex == null) {
                listOf(IllegalArgumentException(context.getString(R.string.toast_failed)))
            } else {
                withContext(Dispatchers.IO) {
                    val rename = listing.loadedCapabilities.rename ?: error("cannot rename here")
                    entries.mapNotNull { entry ->
                        val newName = regex.replace(entry.name, replacement)
                        if (newName.isBlank() || newName == entry.name) {
                            null
                        } else {
                            runCatching { rename(entry.name, newName).getOrThrow() }
                                .exceptionOrNull()
                        }
                    }
                }
            }
            Toast.makeText(
                context,
                if (failures.isEmpty()) context.getString(R.string.toast_renamed, entries.size.toString())
                else context.errorText(failures.first()),
                Toast.LENGTH_SHORT,
            ).show()
            if (failures.isEmpty()) listing.clearSelection()
            listing.refresh(animate = true)
        }
    }

    override val clipboardSink: ClipboardSink? = object : ClipboardSink {
        override fun accepts(state: ClipboardState): Boolean {
            val caps = listing.loadedCapabilities
            return state.inTransfer && listing.loadedFolder != null &&
                (caps.createFile != null || caps.createDirectory != null)
        }

        override fun destinationFolder(): VFolder? = listing.loadedFolder
    }

    override fun blur() {
        menuEntry = null
        showCreateDialog = false
        showSortDialog = false
        listing.clearDisplayedSelection()
    }

    override fun onRelease() = folderSession.onRelease()

    override fun close() = folderSession.close()

    override fun reset() = folderSession.reset()
}

/** View half of the directory renderer; all state stays in the renderer. */
internal class FolderSessionView(
    private val renderer: FolderRenderer,
) : SessionView {
    @Composable
    override fun Content(host: RendererContext, darkTheme: Boolean, modifier: Modifier) {
        val listing = renderer.listing
        renderer.bindHost(host)
        val state = host.folderView() ?: return
        renderer.syncPath(state.path)
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        Box(modifier) {
            FolderContent(
                listing = listing,
                path = state.path,
                worldId = host.worldId,
                committedPath = state.committedPath,
                folderOverride = renderer.folderNode(),
                onNavigationLoaded = host::onFolderLoaded,
                onNavigationCancelled = host::onFolderCancelled,
                scrollToPath = state.scrollToPath,
                highlightPath = state.highlightPath,
                highlightDirectory = state.highlightDirectory,
                onScrollConsumed = host::onScrollConsumed,
                onHighlightConsumed = host::onHighlightConsumed,
                onOpenEntry = { entry, node ->
                    if (node == null) {
                        host.openEntry(entry, null)
                    } else {
                        scope.launch {
                            try {
                                val opened = host.opener.route(node)
                                seedVolumeFacts(opened, listing)
                                host.open(opened)
                            } catch (error: kotlinx.coroutines.CancellationException) {
                                throw error
                            } catch (error: Throwable) {
                                host.reportError(error)
                            }
                        }
                    }
                },
                onLongPress = { entry -> renderer.menuEntry = entry },
                opening = state.opening,
                onCancelOpening = host::cancelOpening,
                onFileDragStart = host::onFileDragStart,
                onFileDragEnd = host::onFileDragEnd,
            )
            renderer.menuEntry?.let { entry ->
                val selected = listing.selectedEntries
                val batch = selected.ifEmpty { listOf(entry) }
                val readOnly = renderer.isReadOnly()
                ContextMenu(
                    entry = entry,
                    onDismiss = { renderer.menuEntry = null },
                    onCopy = {
                        host.putOnClipboard(listing.clipboardEntries(batch), ClipboardAction.COPY)
                        renderer.menuEntry = null
                    },
                    onMove = {
                        host.putOnClipboard(listing.clipboardEntries(batch), ClipboardAction.MOVE)
                        renderer.menuEntry = null
                    },
                    onRename = {
                        renderer.requestRename(batch)
                        renderer.menuEntry = null
                    },
                    onDelete = {
                        renderer.requestDelete(batch)
                        renderer.menuEntry = null
                    },
                    onProperties = {
                        host.requestProperties(entry)
                        renderer.menuEntry = null
                    },
                    onShare = {
                        host.requestShare(listing.clipboardEntries(batch))
                        renderer.menuEntry = null
                    },
                    canCopy = true,
                    canMove = !readOnly,
                    canShare = batch.all { !it.isDirectory },
                    canModify = !readOnly,
                )
            }
            if (renderer.showCreateDialog) {
                CreateDialog(
                    initialPath = listing.loadedPath ?: state.path,
                    onDismiss = { renderer.showCreateDialog = false },
                    onCreateFile = { name -> renderer.createEntry(context, name, directory = false) },
                    onCreateFolder = { name -> renderer.createEntry(context, name, directory = true) },
                )
            }
            if (renderer.showSortDialog) {
                SortDialog(
                    currentMode = listing.sortMode,
                    currentReverse = listing.sortReverse,
                    onDismiss = { renderer.showSortDialog = false },
                    onConfirm = { mode, reverse ->
                        listing.setSort(mode, reverse)
                        renderer.showSortDialog = false
                    },
                )
            }
            renderer.deleteEntries?.let { entries ->
                DeleteConfirmDialog(
                    entries = entries,
                    onDismiss = { renderer.deleteEntries = null },
                    onConfirm = { renderer.delete(context, entries) },
                )
            }
            renderer.renameEntry?.let { entry ->
                RenameDialog(
                    entry = entry,
                    onDismiss = { renderer.renameEntry = null },
                    onConfirm = { newName -> renderer.rename(context, entry, newName) },
                )
            }
            renderer.batchRenameEntries?.let { entries ->
                BatchRenameDialog(
                    count = entries.size,
                    onDismiss = { renderer.batchRenameEntries = null },
                    onConfirm = { pattern, replacement ->
                        renderer.batchRename(context, entries, pattern, replacement)
                    },
                )
            }
        }
    }
}
