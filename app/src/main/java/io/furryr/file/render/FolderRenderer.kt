package io.furryr.file.render

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import io.furryr.file.clipboard.ClipboardStore
import io.furryr.file.clipboard.ClipboardSink
import io.furryr.file.core.VFolder
import io.furryr.file.core.DirectoryStats
import io.furryr.file.core.model.FileEntry
import io.furryr.file.isHomePath
import io.furryr.file.ui.components.ContextMenu
import io.furryr.file.ui.components.CreateDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
 * A directory tab as a normal renderer.
 *
 * The session owns the listing and selection, so switching tabs keeps both.
 * The shell only pushes [FolderRendererState]; every app-level action goes
 * through [RenderHost], exactly like the Web and editor renderers.
 */
class FolderRendererSession(
    private val host: RenderHost,
    private val scope: CoroutineScope,
    initialSelection: Set<String>,
    initialSelectedIndex: Int,
) : RendererSession, ListingSession {
    val listing = FolderListing(scope) { paths, index, entries ->
        host.onSelectionChanged(paths, index, entries)
    }

    init {
        listing.seedSelection(initialSelection, initialSelectedIndex)
    }

    override val isLocationRenderer: Boolean get() = true
    override val retention: Retention get() = Retention.WARM

    override fun onActivate() {
        listing.lockSelection(ClipboardStore.state.inTransfer)
    }

    override fun onDeactivate() {
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
        private set

    /** The renderer's own create dialog, opened by the "+" slot. */
    var showCreateDialog by mutableStateOf(false)
        private set

    /** The renderer's own sort dialog, opened by its menu. */
    var showSortDialog by mutableStateOf(false)
        private set

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

    override val locationInfo: DirectoryStats get() = listing.directoryInfo

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
            canRefresh = true,
            canCreate = listing.loadedCapabilities.canCreate,
            canTerminal = listing.loadedCapabilities.canOpenTerminal,
            canGoParent = parent != null,
        )

    override val parent: VFolder?
        get() = listing.loadedFolder?.parent

    override val terminalPath: String?
        get() = listing.loadedPath?.takeIf { listing.loadedCapabilities.canOpenTerminal }

    override fun matchesLocation(path: String): Boolean = !isHomePath(path)

    override fun refresh(): Boolean {
        listing.refresh(animate = true)
        return true
    }

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
    }

    override fun requestCreate() {
        if (!listing.loadedCapabilities.canCreate || listing.loadedFolder == null) return
        showCreateDialog = true
    }

    private fun createEntry(context: Context, name: String, directory: Boolean) {
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

    @Composable
    override fun Content(darkTheme: Boolean, modifier: Modifier) {
        val state = host.folderView() ?: return
        val context = LocalContext.current
        SideEffect { listing.lockSelection(ClipboardStore.state.inTransfer) }
        Box(modifier) {
            FolderContent(
                listing = listing,
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
                onOpenEntry = host::openEntry,
                onLongPress = { entry -> menuEntry = entry },
                opening = state.opening,
                onCancelOpening = host::cancelOpening,
                onFileDragStart = host::onFileDragStart,
                onFileDragEnd = host::onFileDragEnd,
                errorView = DefaultRendererErrorView,
            )
            menuEntry?.let { entry ->
                val selected = listing.selectedEntries
                val batch = selected.ifEmpty { listOf(entry) }
                val readOnly = state.folder != null
                ContextMenu(
                    entry = entry,
                    onDismiss = { menuEntry = null },
                    onCopy = {
                        host.putOnClipboard(listing.clipboardEntries(batch), ClipboardAction.COPY)
                        menuEntry = null
                    },
                    onMove = {
                        host.putOnClipboard(listing.clipboardEntries(batch), ClipboardAction.MOVE)
                        menuEntry = null
                    },
                    onRename = {
                        host.requestRename(batch)
                        menuEntry = null
                    },
                    onDelete = {
                        host.requestDelete(batch)
                        menuEntry = null
                    },
                    onProperties = {
                        host.requestProperties(entry)
                        menuEntry = null
                    },
                    onShare = {
                        host.requestShare(batch)
                        menuEntry = null
                    },
                    canShare = !readOnly && batch.all { !it.isDirectory },
                    canModify = !readOnly,
                )
            }
            if (showCreateDialog) {
                CreateDialog(
                    initialPath = listing.loadedPath ?: state.path,
                    onDismiss = { showCreateDialog = false },
                    onCreateFile = { name -> createEntry(context, name, directory = false) },
                    onCreateFolder = { name -> createEntry(context, name, directory = true) },
                )
            }
            if (showSortDialog) {
                SortDialog(
                    currentMode = listing.sortMode,
                    currentReverse = listing.sortReverse,
                    onDismiss = { showSortDialog = false },
                    onConfirm = { mode, reverse ->
                        listing.setSort(mode, reverse)
                        showSortDialog = false
                    },
                )
            }
        }
    }

    override fun onRelease() {
        listing.close()
    }

    override fun close() = listing.close()
}
