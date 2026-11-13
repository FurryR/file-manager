package io.furryr.file.render

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.furryr.file.clipboard.ClipboardEntry
import io.furryr.file.clipboard.ClipboardStore
import io.furryr.file.core.DirectoryCapabilities
import io.furryr.file.core.DirectoryStats
import io.furryr.file.core.Path
import io.furryr.file.core.VFile
import io.furryr.file.core.VFolder
import io.furryr.file.core.VNode
import io.furryr.file.core.Vfs
import io.furryr.file.core.model.FileEntry
import io.furryr.file.ui.util.isInternalStoragePath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The live listing of one directory renderer.
 *
 * It lives in the renderer session, not in the composable, so a tab switch
 * keeps its entries, selection and scroll position. The shell only supplies
 * the location/options inputs and observes selection changes through
 * [onSelectionChanged].
 */
class FolderListing(
    private val scope: CoroutineScope,
    private val onSelectionChanged: (Set<String>, Int, List<ClipboardEntry>) -> Unit,
) {
    val listState = LazyListState()

    /** Copy/move has frozen the sources; selection changes are refused. */
    var selectionLocked by mutableStateOf(false)
        private set

    var entries by mutableStateOf<List<FileEntry>>(emptyList())
        private set
    var nodesByName by mutableStateOf<Map<String, VNode>>(emptyMap())
        private set
    var error by mutableStateOf<Throwable?>(null)
        private set
    var isRefreshing by mutableStateOf(false)
    var isLoading by mutableStateOf(false)
        private set
    var showSpinner by mutableStateOf(false)
        private set
    var loadedPath by mutableStateOf<String?>(null)
        private set
    var loadedOverride by mutableStateOf<VFolder?>(null)
        private set
    var loadedFolder by mutableStateOf<VFolder?>(null)
        private set
    var loadedCapabilities by mutableStateOf(DirectoryCapabilities())
        private set
    var sortMode by mutableStateOf(SortMode.NAME)
        private set
    var sortReverse by mutableStateOf(false)
        private set
    var filterText by mutableStateOf("")
        private set
    var directoryInfo by mutableStateOf(DirectoryStats())
        private set
    var isInternal by mutableStateOf(false)
        private set
    var selectedPaths by mutableStateOf<Set<String>>(emptySet())
        private set
    var lastSelectedIndex by mutableIntStateOf(-1)
        private set
    var loadSequence by mutableIntStateOf(0)
        private set
    var animationRun by mutableIntStateOf(0)
        private set
    var activeAnimationRun by mutableIntStateOf(0)
        private set
    var animatedIndices by mutableStateOf<Set<Int>>(emptySet())
        private set
    var animationStartIndex by mutableIntStateOf(0)
        private set
    private var loadJob: Job? = null
    private var spinnerDelayJob: Job? = null
    private var loadGeneration = 0

    /** One listing request: path, view and sort decide whether data is new. */
    private data class LoadRequest(
        val path: String,
        val folderOverride: VFolder?,
        val sortMode: SortMode,
        val sortReverse: Boolean,
    )

    /** Inputs kept from the last listing so a refresh can run without the UI. */
    private data class RefreshSpec(
        val path: String,
        val folderOverride: VFolder?,
        val onNavigationLoaded: (String) -> Unit,
        val onHighlightConsumed: () -> Unit,
    )

    /** The request currently loading, and the one that produced [entries]. */
    private var activeRequest: LoadRequest? = null
    private var loadedRequest: LoadRequest? = null
    private var refreshSpec: RefreshSpec? = null

    val selectedEntries: List<FileEntry>
        get() = entries.filter { it.path in selectedPaths }

    fun seedSelection(paths: Set<String>, index: Int) {
        selectedPaths = paths
        lastSelectedIndex = index
    }

    fun lockSelection(locked: Boolean) {
        selectionLocked = locked
    }

    private fun selectionMutable(): Boolean = !selectionLocked && !ClipboardStore.state.inTransfer

    private fun notifySelection() {
        onSelectionChanged(selectedPaths, lastSelectedIndex, clipboardEntries(selectedEntries))
    }

    fun toggleSelection(entry: FileEntry) {
        if (!selectionMutable()) return
        val next = if (entry.path in selectedPaths) selectedPaths - entry.path else selectedPaths + entry.path
        selectedPaths = next
        lastSelectedIndex = entries.indexOfFirst { it.path == entry.path }
        notifySelection()
    }

    fun rangeSelectTo(entry: FileEntry) {
        if (!selectionMutable()) return
        val index = entries.indexOfFirst { it.path == entry.path }
        if (index < 0) return
        // A refresh can replace the list without clearing the anchor; an
        // anchor outside the current entries would make subList throw.
        val anchor = lastSelectedIndex.takeIf { it in entries.indices } ?: index
        val start = minOf(anchor, index)
        val end = maxOf(anchor, index)
        val next = selectedPaths + entries.subList(start, end + 1).map { it.path }
        selectedPaths = next
        lastSelectedIndex = index
        notifySelection()
    }

    fun selectAll() {
        if (!selectionMutable()) return
        val next = entries.mapTo(HashSet(entries.size)) { it.path }
        selectedPaths = next
        lastSelectedIndex = entries.lastIndex
        notifySelection()
    }

    fun invertSelection() {
        if (!selectionMutable()) return
        val next = entries.map { it.path }.filterNot { it in selectedPaths }.toSet()
        selectedPaths = next
        notifySelection()
    }

    fun selectSameType() {
        if (!selectionMutable()) return
        val selected = selectedEntries
        if (selected.isEmpty()) return
        val hasDir = selected.any { it.isDirectory }
        val extensions = selected.filterNot { it.isDirectory }
            .map { it.name.substringAfterLast('.', "") }
            .distinct()
        val next = entries.filter { entry ->
            if (entry.isDirectory) hasDir
            else entry.name.substringAfterLast('.', "") in extensions
        }.mapTo(HashSet(entries.size)) { it.path }
        selectedPaths = next
        lastSelectedIndex = entries.indexOfFirst { it.path in next }
        notifySelection()
    }

    fun clearSelection() {
        if (!selectionMutable()) return
        clearDisplayedSelection()
    }

    /**
     * Drop the highlighted rows without touching the clipboard. Entering
     * copy/move uses this: the sources are already frozen in the payload, and
     * the list only shows the destination from here on.
     */
    fun clearDisplayedSelection() {
        if (selectedPaths.isEmpty() && lastSelectedIndex == -1) return
        selectedPaths = emptySet()
        lastSelectedIndex = -1
        onSelectionChanged(selectedPaths, lastSelectedIndex, emptyList())
    }

    fun setSelection(paths: Set<String>, index: Int) {
        if (!selectionMutable()) return
        selectedPaths = paths
        lastSelectedIndex = index
        notifySelection()
    }

    fun clipboardEntries(entries: List<FileEntry>): List<ClipboardEntry> =
        entries.mapNotNull { entry ->
            nodesByName[entry.name]?.let { ClipboardEntry(it, entry.name) }
        }

    fun cancelLoading() {
        ++loadGeneration
        loadJob?.cancel()
        spinnerDelayJob?.cancel()
        activeRequest = null
        showSpinner = false
        isLoading = false
        isRefreshing = false
    }

    fun close() {
        ++loadGeneration
        loadJob?.cancel()
        spinnerDelayJob?.cancel()
        activeRequest = null
    }

    fun finishAnimation() {
        activeAnimationRun = 0
        animatedIndices = emptySet()
    }

    /**
     * Refresh the listing already shown, without waiting for the UI to
     * recompose. Used by the shell and the renderer's own actions.
     */
    fun refresh(
        animate: Boolean = true,
        highlightPath: String? = null,
        highlightDirectory: String? = null,
    ) {
        val spec = refreshSpec ?: return
        load(
            path = spec.path,
            folderOverride = spec.folderOverride,
            animate = animate,
            highlightPath = highlightPath,
            highlightDirectory = highlightDirectory,
            onNavigationLoaded = spec.onNavigationLoaded,
            onHighlightConsumed = spec.onHighlightConsumed,
            force = true,
        )
    }

    /** Sort is view state of this session; a change re-lists what is shown. */
    fun setSort(mode: SortMode, reverse: Boolean) {
        if (sortMode == mode && sortReverse == reverse) return
        sortMode = mode
        sortReverse = reverse
        refresh(animate = false)
    }

    /** The filter narrows the loaded entries; it never refetches them. */
    fun setFilter(text: String) {
        filterText = text
    }

    fun load(
        path: String,
        folderOverride: VFolder?,
        animate: Boolean,
        highlightPath: String?,
        highlightDirectory: String?,
        onNavigationLoaded: (String) -> Unit,
        onHighlightConsumed: () -> Unit,
        force: Boolean = false,
    ) {
        val requestedPath = path
        refreshSpec = RefreshSpec(
            path = requestedPath,
            folderOverride = folderOverride,
            onNavigationLoaded = onNavigationLoaded,
            onHighlightConsumed = onHighlightConsumed,
        )
        val request = LoadRequest(
            path = requestedPath,
            folderOverride = folderOverride,
            sortMode = sortMode,
            sortReverse = sortReverse,
        )
        val requestedFilter = filterText
        // A tab switch re-enters the same session and may ask for the listing
        // it already has. Do not cancel the running load or fetch again unless
        // an input actually changed; an explicit refresh bypasses only the
        // "already loaded" check, never a request that is already in flight.
        if (request == activeRequest) return
        if (!force && request == loadedRequest && error == null) {
            isRefreshing = false
            return
        }
        isRefreshing = force
        activeRequest = request
        val generation = ++loadGeneration
        loadJob?.cancel()
        spinnerDelayJob?.cancel()
        showSpinner = false
        isLoading = true
        error = null
        loadJob = scope.launch {
            try {
                spinnerDelayJob = launch {
                    delay(300)
                    if (generation == loadGeneration) showSpinner = true
                }
                val folder = withContext(Dispatchers.IO) {
                    folderOverride ?: Vfs.folder(requestedPath).getOrThrow()
                }
                val listing = withContext(Dispatchers.IO) { folder.listWithNodes() }
                if (generation != loadGeneration) return@launch
                val capabilities = withContext(Dispatchers.IO) { folder.capabilities() }
                if (generation != loadGeneration) return@launch
                val raw = listing.getOrNull()?.first.orEmpty()
                val rawNodes = listing.getOrNull()?.second.orEmpty()
                val (sorted, newNodes) = withContext(Dispatchers.IO) {
                    val sorted = sortEntries(raw, request.sortMode, request.sortReverse)
                    sorted to rawNodes.associateBy { it.name }
                }
                val info = withContext(Dispatchers.IO) { folder.info() }
                if (generation != loadGeneration) return@launch
                val directoryChanged = loadedPath != requestedPath
                val returnPath = if (folderOverride == null) loadedPath?.takeIf {
                    Path.parse(it).navigate("..") == Path.parse(requestedPath)
                } else null
                val returnName = returnPath?.let { Path.parse(it).name }
                val visible = if (requestedFilter.isBlank()) sorted
                    else sorted.filter { it.name.contains(requestedFilter, ignoreCase = true) }
                val firstVisible = if (directoryChanged) {
                    visible.indexOfFirst { returnName != null && it.name == returnName }.coerceAtLeast(0)
                } else listState.firstVisibleItemIndex
                if (directoryChanged) listState.requestScrollToItem(firstVisible)

                // Selection survives a refresh while the entries still exist;
                // a new directory keeps only what it actually lists. A frozen
                // copy/move payload keeps its sources even when the list moves.
                if (!selectionLocked) {
                    var newPaths = if (selectedPaths.isEmpty()) {
                        selectedPaths
                    } else {
                        selectedPaths intersect sorted.mapTo(HashSet(sorted.size)) { it.path }
                    }
                    var selectionIndex = lastSelectedIndex
                    if ((highlightPath != null || highlightDirectory != null) &&
                        (highlightDirectory == null || highlightDirectory == requestedPath)
                    ) {
                        if (highlightPath != null && sorted.any { it.path == highlightPath }) {
                            newPaths = newPaths + highlightPath
                            selectionIndex = sorted.indexOfFirst { it.path == highlightPath }
                        }
                        onHighlightConsumed()
                    }
                    selectedPaths = newPaths
                    lastSelectedIndex = when {
                        newPaths.isEmpty() -> -1
                        selectionIndex !in sorted.indices -> sorted.indexOfFirst { it.path in newPaths }
                        else -> selectionIndex
                    }
                }

                loadedPath = requestedPath
                loadedOverride = folderOverride
                loadedRequest = request
                loadedFolder = folder
                loadedCapabilities = capabilities
                onNavigationLoaded(requestedPath)
                isInternal = folder.path.isBarePath && isInternalStoragePath(folder.path.toString())
                if (animate) {
                    animationStartIndex = firstVisible
                    animatedIndices = (firstVisible until (firstVisible + 24).coerceAtMost(visible.size)).toSet()
                    animationRun++
                    activeAnimationRun = animationRun
                } else {
                    animatedIndices = emptySet()
                    activeAnimationRun = 0
                }
                entries = sorted
                nodesByName = newNodes
                if (!selectionLocked) notifySelection()
                loadSequence++
                error = listing.exceptionOrNull()
                directoryInfo = info
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == loadGeneration) {
                    if (loadedPath != requestedPath) listState.requestScrollToItem(0)
                    loadedPath = requestedPath
                    loadedFolder = folderOverride
                    loadedCapabilities = DirectoryCapabilities()
                    onNavigationLoaded(requestedPath)
                    entries = emptyList()
                    nodesByName = emptyMap()
                    if (selectedPaths.isNotEmpty()) {
                        selectedPaths = emptySet()
                        lastSelectedIndex = -1
                        onSelectionChanged(selectedPaths, lastSelectedIndex, emptyList())
                    }
                    directoryInfo = DirectoryStats()
                    error = e
                }
            } finally {
                if (generation == loadGeneration) {
                    spinnerDelayJob?.cancel()
                    showSpinner = false
                    isRefreshing = false
                    isLoading = false
                    activeRequest = null
                }
            }
        }
    }

    private fun sortEntries(
        raw: List<FileEntry>,
        sortMode: SortMode,
        sortReverse: Boolean,
    ): List<FileEntry> {
        val sorted = when (sortMode) {
            SortMode.NAME -> raw.sortedWith(
                compareByDescending<FileEntry> { it.isDirectory }.thenBy { it.name.lowercase(java.util.Locale.ROOT) }
            )
            SortMode.DATE -> raw.sortedWith(
                compareByDescending<FileEntry> { it.isDirectory }.thenByDescending { it.modifiedAt }
            )
            SortMode.SIZE -> raw.sortedWith(
                compareByDescending<FileEntry> { it.isDirectory }.thenByDescending { it.size }
            )
            SortMode.TYPE -> raw.sortedWith(
                compareByDescending<FileEntry> { it.isDirectory }
                    .thenBy { it.name.substringAfterLast('.', "") }
                    .thenBy { it.name.lowercase(java.util.Locale.ROOT) }
            )
        }
        return if (sortReverse) sorted.reversed() else sorted
    }
}
