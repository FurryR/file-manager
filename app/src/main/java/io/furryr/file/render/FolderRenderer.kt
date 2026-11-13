package io.furryr.file.render

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.furryr.file.R
import io.furryr.file.clipboard.ClipboardAction
import io.furryr.file.clipboard.ClipboardState
import io.furryr.file.clipboard.ClipboardSink
import io.furryr.file.core.DirectoryStats
import io.furryr.file.core.Path
import io.furryr.file.core.VFolder
import io.furryr.file.core.model.FileEntry
import io.furryr.file.isHomePath
import io.furryr.file.model.summary
import io.furryr.file.ui.components.ContextMenu
import io.furryr.file.ui.components.CreateDialog
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
    val folder: VFolder? = null,
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
    var path: String,
    var title: String? = null,
    @Transient var cachedInfo: DirectoryStats? = null,
) : PageState()

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
    val target = renderer as? FolderSessionRenderer ?: return
    if (target.state.path.isBlank()) return
    val current = listing.loadedPath ?: return
    val currentPath = runCatching { Path.parse(current) }.getOrNull() ?: return
    val targetPath = runCatching { Path.parse(target.state.path) }.getOrNull() ?: return
    if (currentPath.isViewed || targetPath.isViewed) return
    if (targetPath.parent() != currentPath) return
    val info = listing.directoryInfo
    if (info.totalBytes <= 0 && info.usableBytes <= 0) return
    target.state.cachedInfo = info
}

/**
 * A directory tab as a normal renderer.
 *
 * The session owns the listing and selection, so switching tabs keeps both.
 * The shell only pushes [FolderRendererState]; every app-level action goes
 * through [RendererContext], exactly like the Web and editor renderers.
 */
class FolderRendererSession(
    initialSelection: Set<String>,
    initialSelectedIndex: Int,
    initialState: FolderPageState? = null,
) : PageSession<FolderPageState>(initialState), ListingSession {
    private var host: RendererContext = DetachedRendererContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val listing = FolderListing(scope) { paths, index, entries ->
        host.onSelectionChanged(paths, index, entries)
    }

    init {
        listing.seedSelection(initialSelection, initialSelectedIndex)
    }

    override val state: FolderPageState = seedState ?: FolderPageState("")

    override val isLocationRenderer: Boolean get() = true

    override fun leave() {
        menuEntry = null
        showCreateDialog = false
        showSortDialog = false
        listing.clearDisplayedSelection()
    }

    override val entries: List<FileEntry> get() = listing.entries
    override val selectedEntries: List<FileEntry> get() = listing.selectedEntries
    override val selectedPaths: Set<String> get() = listing.selectedPaths
    override fun selectAll() = listing.selectAll()
    override fun invertSelection() = listing.invertSelection()
    override fun selectSameType() = listing.selectSameType()
    override fun setSelection(paths: Set<String>, index: Int) = listing.setSelection(paths, index)
    override fun clearSelection() = listing.clearSelection()
    override fun clearDisplayedSelection() = listing.clearDisplayedSelection()

    /** Long-press opens the renderer's own entry menu, not a shell dialog. */
    var menuEntry by mutableStateOf<FileEntry?>(null)
        internal set

    /** The renderer's own create dialog, opened by the "+" slot. */
    var showCreateDialog by mutableStateOf(false)
        internal set

    /** The renderer's own sort dialog, opened by its menu. */
    var showSortDialog by mutableStateOf(false)
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

    /** Drop the filter overlay and any row animation before this page commits. */
    internal fun prepareEnter() {
        closeFilter()
        listing.finishAnimation()
    }

    override val locationInfo: DirectoryStats
        get() {
            val live = listing.directoryInfo
            if (live.totalBytes > 0 || live.usableBytes > 0) return live
            return state.cachedInfo ?: live
        }

    override val title: String?
        get() {
            val shown = host.folderView()
            return shown?.folder?.name?.takeIf { it.isNotBlank() }
                ?: state.title?.takeIf { it.isNotBlank() }
                ?: shown?.path?.pathLabel()
                ?: state.path.pathLabel()
        }

    override fun pathBar(context: Context): RendererPath? {
        val path = host.folderView()?.path ?: state.path
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

    override val capabilities: RenderCapabilities
        get() = RenderCapabilities(
            canCreate = listing.loadedCapabilities.canCreate,
        )

    override val parent: VFolder?
        get() = listing.loadedFolder?.parent

    override val terminalPath: String?
        get() = listing.loadedPath?.takeIf { listing.loadedCapabilities.canOpenTerminal }

    override fun matchesLocation(path: String): Boolean = !isHomePath(path)

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
        host.bookmarkMenuItem(context)?.let(::add)
    }

    override fun requestCreate() {
        if (!listing.loadedCapabilities.canCreate || listing.loadedFolder == null) return
        showCreateDialog = true
    }

    internal fun createEntry(context: Context, name: String, directory: Boolean) {
        val folder = listing.loadedFolder ?: return
        showCreateDialog = false
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (directory) folder.mkdir(name).getOrThrow() else folder.create(name).getOrThrow()
                }
            }
            Toast.makeText(
                context,
                result.exceptionOrNull()?.message ?: context.getString(R.string.toast_created, name),
                Toast.LENGTH_SHORT,
            ).show()
            listing.refresh(animate = true)
        }
    }

    override val clipboardSink: ClipboardSink? = object : ClipboardSink {
        override fun accepts(state: ClipboardState): Boolean =
            state.inTransfer && listing.loadedCapabilities.canCreate && listing.loadedFolder != null

        override fun destinationFolder(): VFolder? = listing.loadedFolder
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
        host = DetachedRendererContext
    }
}

/** View half of the directory session; all state stays in the session. */
internal class FolderSessionView(
    private val session: FolderRendererSession,
) : SessionView {
    @Composable
    override fun Content(host: RendererContext, darkTheme: Boolean, modifier: Modifier) {
        session.useHost(host)
        val state = host.folderView() ?: return
        (session.state as? FolderPageState)?.path = state.path
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        Box(modifier) {
            FolderContent(
                listing = session.listing,
                path = state.path,
                committedPath = state.committedPath,
                folderOverride = state.folder,
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
                            val opener = host.opener
                            if (opener == null) {
                                host.openEntry(entry, node)
                                return@launch
                            }
                            when (val opened = opener.open(node, null, host)) {
                                is Opened.Renderer -> {
                                    seedVolumeFacts(opened.renderer, session.listing)
                                    host.open(opened.renderer)
                                }
                                is Opened.Failure -> host.reportError(
                                    IllegalStateException(opened.message ?: "Cannot open"),
                                )
                            }
                        }
                    }
                },
                onLongPress = { entry -> session.menuEntry = entry },
                opening = state.opening,
                onCancelOpening = host::cancelOpening,
                onFileDragStart = host::onFileDragStart,
                onFileDragEnd = host::onFileDragEnd,
                errorView = DefaultRendererErrorView,
            )
            session.menuEntry?.let { entry ->
                val selected = session.listing.selectedEntries
                val batch = selected.ifEmpty { listOf(entry) }
                val readOnly = state.folder != null
                ContextMenu(
                    entry = entry,
                    onDismiss = { session.menuEntry = null },
                    onCopy = {
                        host.putOnClipboard(session.listing.clipboardEntries(batch), ClipboardAction.COPY)
                        session.menuEntry = null
                    },
                    onMove = {
                        host.putOnClipboard(session.listing.clipboardEntries(batch), ClipboardAction.MOVE)
                        session.menuEntry = null
                    },
                    onRename = {
                        host.requestRename(batch)
                        session.menuEntry = null
                    },
                    onDelete = {
                        host.requestDelete(batch)
                        session.menuEntry = null
                    },
                    onProperties = {
                        host.requestProperties(entry)
                        session.menuEntry = null
                    },
                    onShare = {
                        host.requestShare(batch)
                        session.menuEntry = null
                    },
                    canShare = !readOnly && batch.all { !it.isDirectory },
                    canModify = !readOnly,
                )
            }
            if (session.showCreateDialog) {
                CreateDialog(
                    initialPath = session.listing.loadedPath ?: state.path,
                    onDismiss = { session.showCreateDialog = false },
                    onCreateFile = { name -> session.createEntry(context, name, directory = false) },
                    onCreateFolder = { name -> session.createEntry(context, name, directory = true) },
                )
            }
            if (session.showSortDialog) {
                SortDialog(
                    currentMode = session.listing.sortMode,
                    currentReverse = session.listing.sortReverse,
                    onDismiss = { session.showSortDialog = false },
                    onConfirm = { mode, reverse ->
                        session.listing.setSort(mode, reverse)
                        session.showSortDialog = false
                    },
                )
            }
        }
    }
}
