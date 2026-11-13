package io.furryr.file
import io.furryr.file.model.*
import io.furryr.file.home.HomeEntry
import io.furryr.file.home.HomeEntryKind
import io.furryr.file.home.HomeStore
import io.furryr.file.ui.components.*
import io.furryr.file.ui.screens.*
import io.furryr.file.ui.util.*
import io.furryr.file.shell.*
import io.furryr.file.ui.shell.*
import io.furryr.file.ui.state.rememberLocationCollections
import io.furryr.file.core.ContentInfo
import io.furryr.file.core.ContentTypes
import io.furryr.file.core.Path
import io.furryr.file.daemon.DaemonManager
import io.furryr.file.daemon.DaemonStatus
import io.furryr.file.container.ContainerManager
import io.furryr.file.container.ContainerBackend
import io.furryr.file.core.ContainerUris
import io.furryr.file.core.DirectoryCapabilities
import io.furryr.file.terminal.SessionManager
import io.furryr.file.terminal.TerminalSheet
import io.furryr.file.provider.model.LocalLocation
import io.furryr.file.provider.model.SafLocation
import io.furryr.file.provider.model.FtpServer
import io.furryr.file.provider.model.SftpServer
import io.furryr.file.store.LocationsRepository
import io.furryr.file.core.Folder
import io.furryr.file.core.VFolder
import io.furryr.file.core.VNode
import io.furryr.file.core.VFile
import io.furryr.file.core.NodeOps
import io.furryr.file.core.Vfs
import io.furryr.file.render.FolderRendererState
import io.furryr.file.render.HomeRenderer
import io.furryr.file.render.HomeRendererState
import io.furryr.file.render.ListingHandle
import io.furryr.file.render.NodeAddress
import io.furryr.file.render.RenderCapabilities
import io.furryr.file.render.RendererContext
import io.furryr.file.render.RendererMenuItem
import io.furryr.file.render.RendererMenuItemRow
import io.furryr.file.render.RendererPath
import io.furryr.file.render.NodeOpener
import io.furryr.file.render.Opened
import io.furryr.file.render.RendererBase
import io.furryr.file.render.RendererContent
import io.furryr.file.render.RendererSession
import io.furryr.file.render.RendererSessions
import io.furryr.file.provider.web.WebTransfers
import io.furryr.file.provider.remote.RemoteLocation
import android.app.Activity
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle

import io.furryr.file.clipboard.ClipboardEntry
import io.furryr.file.clipboard.ClipboardAction
import io.furryr.file.clipboard.ClipboardStore
import io.furryr.file.clipboard.ClipboardTransfer
import io.furryr.file.core.DirectoryStats
import io.furryr.file.core.model.FileEntry
import io.furryr.file.model.FileTab
import io.furryr.file.model.OperationProgress
import io.furryr.file.ui.components.BottomToolbar
import io.furryr.file.ui.components.FileDrawer
import io.furryr.file.ui.util.OperationNotification
import io.furryr.file.ui.util.ContainerNotification
import io.furryr.file.ui.util.formatSize

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FileManagerApp(
    darkTheme: Boolean = true,
    onToggleTheme: () -> Unit = {},
    terminalOpenRequest: Int = 0,
    containerErrorRequest: String? = null,
) {
    val context = LocalContext.current

    /** The Home page's title/path bar before its renderer is first built. */
    fun homePage(): Page = Page(
        path = HomePath,
        kind = PageKind.LOCATION,
        title = context.getString(R.string.home_title),
        pathInfo = HomeRenderer.pathBar(context),
    )

    fun newHomeTab(): FileTab = FileTab(path = HomePath, pages = listOf(homePage()))

    val tabs = remember {
        FileTabs.tabs ?: mutableStateListOf(newHomeTab()).also {
            FileTabs.tabs = it
            if (FileTabs.activeId == null) {
                FileTabs.activeId = mutableStateOf<String?>(it.first().id)
            }
        }
    }
    val activeTabId: MutableState<String?> = remember {
        FileTabs.activeId ?: mutableStateOf<String?>(tabs.first().id).also { FileTabs.activeId = it }
    }
    val selectedTabIndex: Int =
        tabs.indexOfFirst { it.id == activeTabId.value }.takeIf { it >= 0 } ?: 0

    fun selectTab(index: Int) {
        activeTabId.value = tabs.getOrNull(index)?.id
    }
    var containerError by remember(containerErrorRequest) { mutableStateOf(containerErrorRequest) }
    var pendingTabIndex by remember { mutableIntStateOf(-1) }
    var titleEditing by remember { mutableStateOf(false) }

    // Context menu & file operations state
    var showDeleteConfirm by remember { mutableStateOf<List<FileEntry>?>(null) }
    var showRenameDialog by remember { mutableStateOf<FileEntry?>(null) }
    var showBatchRenameDialog by remember { mutableStateOf(false) }
    var showEntryInfo by remember { mutableStateOf<FileEntry?>(null) }

    var operationProgress by remember { mutableStateOf<OperationProgress?>(null) }
    var operationJob by remember { mutableStateOf<Job?>(null) }
    var operationTaskIds: List<Long>? = null  // kept only for notification UI reference
    var operationHidden by remember { mutableStateOf(false) }
    var highlightPath by remember { mutableStateOf<String?>(null) }
    var highlightDirectory by remember { mutableStateOf<String?>(null) }
    var pendingScrollPath by remember { mutableStateOf<String?>(null) }
    // A file open can spend hundreds of milliseconds reading a zip index or a
    // remote probe. The list shows its existing spinner overlay after 300 ms.
    var nodeOpeningTab by remember { mutableStateOf<String?>(null) }
    var nodeOpenJob by remember { mutableStateOf<Job?>(null) }
    var nodeOpenGeneration by remember { mutableIntStateOf(0) }
    var showTerminalSheet by remember { mutableStateOf(false) }
    // Existing terminals stay reachable from a tab that cannot create a new
    // one (web pages, editors, archives); only the create action is disabled.
    var hasTerminalSessions by remember { mutableStateOf(SessionManager.getAllSessions().isNotEmpty()) }
    var terminalDropAccepted by remember { mutableStateOf(false) }
    var terminalDragGeneration by remember { mutableIntStateOf(0) }
    var showSessionManager by remember { mutableStateOf(false) }
    var showPages by remember { mutableStateOf(false) }
    // A `here` request that arrived while an editor held unsaved changes waits
    // for the exit dialog instead of silently discarding the buffer.
    var pendingHereRequest by remember { mutableStateOf<HereNavigation.Request?>(null) }
    // Pixels of the page panel revealed by an in-flight drag from the toolbar.
    var pagesDragPx by remember { mutableFloatStateOf(0f) }
    // How far the bar must be pulled before the page panel stays open.
    val pagesRevealThresholdPx = with(LocalDensity.current) { 96.dp.toPx() }
    // Measured so the page panel can dock above the toolbar.
    var bottomBarHeightPx by remember { mutableIntStateOf(0) }
    // Pages own their renderers. The shell only looks them up by page id;
    // there is no separate live-session map.
    // Assigned once rendererContextFor() exists below; rendererOf() uses it to mint
    // sessions with the same host surface every other renderer gets.
    lateinit var contextFor: (String, String?) -> ShellRendererContext
    // Assigned after their local functions are declared; the host methods run
    // only from user events, so they never need a forward declaration body.
    lateinit var openVFileFn: (VFile, String, String, ContentInfo?, Boolean, Boolean, Boolean) -> Unit
    lateinit var openHomeEntryFn: (HomeEntry) -> Unit
    lateinit var toggleHomeBookmarkFn: (HomeEntry) -> Unit
    lateinit var cancelOpeningFn: () -> Unit
    lateinit var bookmarkEntryFn: () -> HomeEntry?
    // One shared leave-confirmation dialog for every renderer. The editor
    // offers Save/Discard/Cancel; a WebView beforeunload prompt offers
    // Leave/Cancel because the page owns its own storage.
    var leavePrompt by remember { mutableStateOf<LeavePrompt?>(null) }
    var leaveSaving by remember { mutableStateOf(false) }
    // Bumped whenever the home store changes, so the home screen re-reads it.
    var homeVersion by remember { mutableIntStateOf(0) }
    LaunchedEffect(terminalOpenRequest) {
        if (terminalOpenRequest > 0) {
            showTerminalSheet = true
        }
    }
    // The terminal sheet stacks above the page panel; collapse the panel when
    // the terminal opens.
    LaunchedEffect(showTerminalSheet) {
        if (showTerminalSheet) showPages = false
    }
    DisposableEffect(Unit) {
        val listener: () -> Unit = {
            hasTerminalSessions = SessionManager.getAllSessions().isNotEmpty()
        }
        SessionManager.addSessionChangeListener(listener)
        onDispose { SessionManager.removeSessionChangeListener(listener) }
    }
    var containerRefreshKey by remember { mutableIntStateOf(0) }
    var drawerSortMode by remember { mutableStateOf(false) }
    var containerNameOrder by remember { mutableStateOf<List<String>>(emptyList()) }

    // Tracks the last daemon connection generation we've reacted to.
    // When DaemonLauncher establishes a fresh connection (after start or
    // recovery) this counter increments, triggering file list + drawer refresh.
    var lastRefreshDaemonGen by remember { mutableIntStateOf(DaemonManager.generation) }

    val locations = rememberLocationCollections()

    val safFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            LocationsRepository.insertSaf(uri.toString())
            locations.refreshSaf()
        }
    }


    LaunchedEffect(operationHidden, operationJob) {
        if (operationHidden && operationJob != null) {
            while (operationJob?.isActive == true) {
                delay(500)
                if (MainActivity.pendingReopenOperation) {
                    MainActivity.pendingReopenOperation = false
                    operationHidden = false
                    break
                }
            }
        }
    }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    // Typed locators resolve through the shared view walker (`Vfs.node`):
    // opener registry, no format knowledge here.

    // Exit sort mode when drawer closes
    // Any tap outside the inline path editor dismisses it, like an address
    // bar. Compose touch targets do not move focus on their own, so the root
    // sees every press before its children and clears focus for out-of-bounds
    // presses only.
    val focusManager = LocalFocusManager.current
    var titleBounds by remember { mutableStateOf<Rect?>(null) }
    var topBarBounds by remember { mutableStateOf<Rect?>(null) }
    var rootCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    LaunchedEffect(drawerState.isClosed) {
        if (drawerState.isClosed) drawerSortMode = false
    }
    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    fun currentTab(): FileTab = tabs[selectedTabIndex]

    // Navigations in flight are per tab. A slow remote probe must stay with the
    // tab that started it: switching tabs neither drops the pending location
    // nor lets the result commit into whichever tab is selected later.
    //
    // The value is a candidate tab: its current page already owns the renderer
    // that is preparing the new view, but the committed tab below stays on
    // screen until that renderer calls commit().
    val pendingNavigations = remember {
        FileTabs.pending ?: mutableStateMapOf<String, FileTab>().also {
            FileTabs.pending = it
        }
    }

    fun pendingOf(tabId: String): FileTab? = pendingNavigations[tabId]

    /** Composable content committed by each page; shell executes it here. */
    val pageContents = remember { mutableStateMapOf<String, RendererContent>() }

    /** Release hook then close; sessions may run this only once. */
    fun releaseSession(session: RendererSession) {
        runCatching { session.onRelease() }
        session.close()
    }

    /** Only the committed tab is visible; a pending renderer loads hidden. */
    fun activeTabState(tabId: String): FileTab? =
        tabs.firstOrNull { it.id == tabId }

    fun activePageId(tabId: String): String? = activeTabState(tabId)?.currentPage?.id

    fun allPages(): Sequence<Page> =
        tabs.asSequence().flatMap { it.pages.asSequence() } +
            pendingNavigations.values.asSequence().flatMap { it.pages.asSequence() }

    fun pageOf(pageId: String): Page? =
        allPages().firstOrNull { it.id == pageId }

    /** A renderer currently on screen (not the one still preparing). */
    fun activeSession(tabId: String): RendererBase<*>? =
        activeTabState(tabId)?.currentPage?.renderer

    /** True when [session] is the committed renderer or its pending renderer. */
    fun isActiveSession(tabId: String, session: RendererSession): Boolean =
        activeTabState(tabId)?.currentPage?.renderer === session ||
            pendingNavigations[tabId]?.currentPage?.renderer === session

    fun isActivePage(tabId: String, pageId: String): Boolean =
        activeTabState(tabId)?.currentPage?.id == pageId ||
            pendingNavigations[tabId]?.currentPage?.id == pageId

    /** Close and detach the renderer of one page. */
    fun releasePage(pageId: String) {
        val page = pageOf(pageId) ?: return
        val renderer = page.renderer ?: return
        page.renderer = null
        releaseSession(renderer)
    }

    fun leavePage(pageId: String?) {
        pageId ?: return
        pageOf(pageId)?.renderer?.leave()
    }

    /** Force-release every page a tab (or its pending location) owns. */
    fun releaseStops(vararg states: FileTab?) {
        states.filterNotNull()
            .flatMap { it.pages }
            .distinctBy { it.id }
            .forEach(Page::close)
    }

    /** Close only the pages that exist solely in [pending], not committed history. */
    fun releasePendingPages(pending: FileTab) {
        val committed = tabs.firstOrNull { it.id == pending.id }?.pages.orEmpty()
        pending.pages.forEach { page ->
            if (committed.none { it === page }) page.close()
        }
    }

    /** Drop a navigation that has not committed, releasing its extra pages. */
    fun cancelPending(tabId: String): FileTab? {
        val pending = pendingNavigations.remove(tabId) ?: return null
        releasePendingPages(pending)
        return pending
    }

    /** Mint the directory/Home renderer for a location page. */
    fun newLocationSession(tab: FileTab, page: Page): RendererBase<*> {
        val renderer = RendererSessions.location(
            path = page.path,
            initialSelection = tab.selectedPaths,
            initialSelectedIndex = tab.lastSelectedIndex,
            homeTitle = context.getString(R.string.home_title),
        )
        page.renderer = renderer
        return renderer
    }

    /**
     * Freeze [entries] into copy/move mode. The source rows stop being shown:
     * from here the list only serves to pick a destination.
     */
    fun startClipboard(entries: List<ClipboardEntry>, action: ClipboardAction) {
        if (entries.isEmpty()) return
        ClipboardStore.begin(entries, action)
        allPages().forEach { page ->
            page.renderer?.listingHandle?.clearDisplayedSelection()
        }
    }

    /** The live tab view, whichever renderer owns it. */
    fun rendererOf(id: String): RendererBase<*>? {
        val tab = activeTabState(id) ?: return null
        val page = tab.currentPage
        page.renderer?.let { return it }
        // A page or an edited file is rebuilt from its page; only a directory
        // or Home has a renderer the shell can mint on demand.
        if (page.kind != PageKind.LOCATION) return null
        // Background tabs keep only renderers they already built.
        if (id != currentTab().id) return null
        return newLocationSession(tab, page)
    }

    fun pendingForCurrent(): FileTab? = pendingNavigations[currentTab().id]

    fun recordHome(kind: HomeEntryKind, location: String, title: String, detail: String = "") {
        if (HomeStore.record(kind, location, title, detail)) homeVersion++
    }

    fun replaceTab(tab: FileTab) {
        val index = tabs.indexOfFirst { it.id == tab.id }
        if (index < 0) return
        val previous = tabs[index]
        tabs[index] = tab
        // A new navigation truncates the forward pages. Close the renderers
        // those pages still held.
        if (previous.pages.size != tab.pages.size ||
            previous.pages.lastOrNull()?.id != tab.pages.lastOrNull()?.id
        ) {
            val kept = tab.pages.mapTo(mutableSetOf()) { it.id }
            previous.pages.forEach { page ->
                if (page.id !in kept) page.close()
            }
        }
    }

    fun replaceCurrent(tab: FileTab) = replaceTab(tab)

    /**
     * Promote [pending] into the tab's real page list.
     *
     * The outgoing view is deactivated and (when this open replaced it) closed.
     * One frame is allowed to dispose it before the caller continues.
     */
    fun commitPending(
        tabId: String,
        pending: FileTab,
    ) {
        if (pendingNavigations[tabId] !== pending) return
        val index = tabs.indexOfFirst { it.id == tabId }
        if (index < 0) {
            cancelPending(tabId)
            return
        }
        val current = tabs[index]
        val oldPage = current.currentPage
        val nextPage = pending.currentPage
        pendingNavigations.remove(tabId)
        oldPage.renderer?.leave()
        if (nextPage.id == oldPage.id) {
            // A page rebuilt in place keeps its identity but gets a new renderer.
            oldPage.close()
        }
        replaceTab(pending)
    }

    fun updateTab(id: String, transform: (FileTab) -> FileTab) {
        val index = tabs.indexOfFirst { it.id == id }
        if (index >= 0) tabs[index] = transform(tabs[index])
    }

    /** Forget a prompt whose owner is leaving; its session cancels the rest. */
    fun clearLeavePrompt(id: String) {
        if (leavePrompt?.ownerTabId == id) {
            leavePrompt = null
            leaveSaving = false
        }
    }

    /** Primary line for a page row: only the renderer cache, no path parsing. */
    fun pageRowLabel(page: Page): String =
        page.title?.takeIf { it.isNotBlank() }
            ?: page.pathInfo?.title?.takeIf { it.isNotBlank() }
            ?: ""

    /** Secondary line for a page row: the renderer's cached address. */
    fun pageRowDetail(page: Page): String =
        page.pathInfo?.editText ?: page.pathInfo?.caption ?: ""

    /** The sheet's projection of the tab's one page list. */
    fun pageRows(tab: FileTab = currentTab()): List<PageRow> =
        tab.pages.mapIndexed { index, page ->
            PageRow(
                page = page,
                label = pageRowLabel(page),
                detail = pageRowDetail(page),
                current = index == tab.pageIndex,
            )
        }

    /**
     * Dismiss the active overlay (a page or an editor) and reveal the stop
     * behind it. A directory/Home is the tab location, not an overlay, so this
     * is a no-op there.
     */
    fun closeRenderer(id: String) {
        val tab = tabs.firstOrNull { it.id == id } ?: return
        val page = tab.currentPage
        if (page.kind == PageKind.LOCATION) return
        val renderer = page.renderer
        if (renderer?.isLocationRenderer == true) return
        clearLeavePrompt(id)
        page.close()
        val index = tab.pages.indexOfFirst { it.id == page.id }
        if (index < 0) return
        val stops = tab.pages.toMutableList().apply { removeAt(index) }
        if (stops.isEmpty()) {
            replaceTab(
                tab.copy(pages = emptyList()).appended(
                    if (isHomePath(tab.path)) homePage()
                    else Page(path = tab.path, folder = tab.folder),
                ),
            )
            return
        }
        val nextIndex = (index - 1).coerceIn(0, stops.lastIndex)
        replaceTab(tab.copy(pages = stops, pageIndex = nextIndex).movedTo(nextIndex))
    }

    /**
     * Give [id] a renderer without touching the visible page yet.
     *
     * The candidate tab owns the renderer; the shell renders it in the hidden
     * pending layer until the renderer calls commit(). WebView adoption
     * is intentionally not used here: a pending page must be a fresh session so
     * the outgoing page stays intact.
     */
    fun showRenderer(
        id: String,
        renderer: RendererBase<*>,
        host: RendererContext,
        address: String,
        pushPage: Boolean = true,
    ) {
        clearLeavePrompt(id)
        cancelPending(id)
        val tab = tabs.firstOrNull { it.id == id } ?: run {
            releaseSession(renderer)
            return
        }
        val current = tab.currentPage
        val kind = if (renderer.webAddress != null || renderer.isLive) PageKind.PAGE else PageKind.FILE
        val title = renderer.title?.removePrefix("• ")?.takeIf { it.isNotBlank() }
        // A location can never be replaced by an overlay: even a caller that
        // asked for no page push gets a new page above the folder.
        val appendPage = pushPage || current.kind == PageKind.LOCATION
        val target = if (appendPage) {
            Page(path = address, kind = kind, title = title)
        } else {
            current.copy(path = address, kind = kind, title = title)
        }
        target.renderer = renderer
        pendingNavigations[id] = if (appendPage) tab.appended(target) else tab.replacedCurrent(target)
    }

    /** Replace any pending prompt, cancelling the previous owner cleanly. */
    fun requestLeave(prompt: LeavePrompt) {
        val previous = leavePrompt
        leavePrompt = null
        leaveSaving = false
        previous?.onCancel?.invoke()
        leavePrompt = prompt
    }

    /**
     * Run [action] after the tab's renderer lets go. The session owns whether a
     * leave prompt is needed; the tab only supplies the dialog host and the
     * post-close cleanup.
     */
    fun guardRenderer(id: String, action: () -> Unit): Boolean {
        val page = activeTabState(id)?.currentPage ?: return false
        val renderer = page.renderer ?: return false
        if (!renderer.needsLeaveGuard) return false
        renderer.requestClose(contextFor(id, null).also { it.bindPage(page.id) }) {
            page.close()
            action()
        }
        return true
    }

    /**
     * Navigate to [path] as a plain location. [view] is a node the caller has
     * already resolved; it is kept only when it is a view, so a provider
     * directory is still listed through its own path (and stays writable).
     */
    fun navigateTo(
        path: String,
        tabId: String = currentTab().id,
        record: Boolean = true,
        view: VFolder? = null,
        /** Append even when [path] is already the tab's location. */
        force: Boolean = false,
    ) {
        if (guardRenderer(tabId) { navigateTo(path, tabId, record, view, force) }) return
        val tab = tabs.firstOrNull { it.id == tabId } ?: return
        if (pendingNavigations[tab.id] != null) return
        // Re-entering the current plain location is not a step; when an overlay
        // covers it, the same address still has to reveal the list.
        if (!force && path == tab.path && tab.currentPage.kind == PageKind.LOCATION) return
        if (record && !isHomePath(path)) {
            recordHome(HomeEntryKind.FOLDER, path, Path.parse(path).name ?: path, path)
        }
        val page = if (isHomePath(path)) homePage() else Page(
            path = path,
            folder = view,
            kind = PageKind.LOCATION,
        )
        val next = tab.appended(page)
        page.renderer = RendererSessions.location(
            path = path,
            initialSelection = tab.selectedPaths,
            initialSelectedIndex = tab.lastSelectedIndex,
            homeTitle = context.getString(R.string.home_title),
        )
        if (tabId == currentTab().id) {
            highlightPath = null
            highlightDirectory = null
            pendingScrollPath = null
        }
        // Home has no resource to load, so it commits in this frame instead of
        // waiting for a renderer round trip.
        if (isHomePath(path) || isHomePath(tab.path)) {
            leavePage(tab.currentPage.id)
            replaceTab(next)
        } else {
            pendingNavigations[next.id] = next
        }
    }

    fun navigateToFolder(
        folder: VFolder,
        tabId: String = currentTab().id,
        /** Replace an overlay stop with this folder instead of appending. */
        replaceCurrent: Boolean = false,
    ) {
        if (guardRenderer(tabId) { navigateToFolder(folder, tabId, replaceCurrent) }) return
        val tab = tabs.firstOrNull { it.id == tabId } ?: return
        if (pendingNavigations[tab.id] != null) return
        if (folder.addressable) {
            recordHome(HomeEntryKind.FOLDER, folder.path.toString(), folder.name, folder.path.toString())
        }
        val page = Page(path = folder.path.toString(), folder = folder, kind = PageKind.LOCATION)
        page.renderer = RendererSessions.location(
            path = page.path,
            initialSelection = tab.selectedPaths,
            initialSelectedIndex = tab.lastSelectedIndex,
            homeTitle = context.getString(R.string.home_title),
        )
        if (tabId == currentTab().id) {
            highlightPath = null
            highlightDirectory = null
            pendingScrollPath = null
        }
        // A rejected page guess is one page, not a page plus an archive above
        // it: replace it in place and let the directory renderer take over.
        if (replaceCurrent && tab.currentPage.kind != PageKind.LOCATION) {
            val next = tab.replacedCurrent(page)
            if (isHomePath(tab.path)) {
                leavePage(tab.currentPage.id)
                replaceTab(next)
            } else {
                pendingNavigations[next.id] = next
            }
            return
        }
        val next = tab.appended(page)
        if (isHomePath(tab.path)) {
            leavePage(tab.currentPage.id)
            replaceTab(next)
        } else {
            pendingNavigations[next.id] = next
        }
    }

    /** Put an open outcome on screen for [tabId]. */
    fun applyOpen(
        tabId: String,
        file: VFile?,
        address: String,
        name: String,
        content: ContentInfo?,
        host: RendererContext,
        pushPage: Boolean,
        outcome: AppOpenOutcome,
    ) {
        when (outcome) {
            is AppOpenOutcome.Folder ->
                navigateToFolder(outcome.node, tabId, replaceCurrent = !pushPage)
            is AppOpenOutcome.Renderer -> showRenderer(tabId, outcome.renderer, host, address, pushPage)
            AppOpenOutcome.External -> if (file != null) {
                openFile(
                    context,
                    FileEntry(
                        name = name,
                        path = address,
                        isDirectory = false,
                        modifiedAt = 0L,
                        size = content?.size ?: 0L,
                    ),
                    file,
                    content,
                )
            } else {
                Toast.makeText(
                    context,
                    context.getString(R.string.toast_cannot_open, name),
                    Toast.LENGTH_SHORT,
                ).show()
            }
            is AppOpenOutcome.Failure -> Toast.makeText(
                context,
                outcome.message ?: context.getString(R.string.toast_cannot_open, name),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    /**
     * Settle an optimistic page against the answer the server actually gave.
     *
     * The tab already shows [session], so this only decides ownership. A
     * declared page type, an answer that names no type at all, or a server that
     * refuses `HEAD` leaves the page alone; a concrete non-page type makes the
     * tab reopen the same node through [reopen] — the route table the eager
     * probe would have used, minus the round trip the page has already spent on
     * its own body.
     *
     * [reopen] is a parameter because the table call belongs to the caller: a
     * probe that knew how to open things would be a second router.
     */
    fun confirmPage(
        tabId: String,
        live: VFile,
        session: RendererSession,
        reopen: suspend (ContentInfo) -> Unit,
    ) {
        val expected = session.address ?: return
        val guessedStop = pendingOf(tabId)?.currentPage?.id
            ?: tabs.firstOrNull { it.id == tabId }?.currentPage?.id
            ?: return
        // Keep the page's body behind the loading cover until the server has
        // said what it is, so a hand-back never flashes a frame of it.
        session.beginConfirmation()
        scope.launch {
            val declared = withContext(Dispatchers.IO) { live.peek().getOrNull() }
            // Headers that name no usable type need the body sniff the eager
            // probe runs anyway: the same request, now overlapped with the
            // transfer the WebView already started.
            val confirmed = if (WebPagePolicy.verdict(declared, settled = false) ==
                WebPagePolicy.Verdict.UNDECIDED
            ) {
                withContext(Dispatchers.IO) { live.describe().getOrNull() }
            } else {
                declared
            }
            // Something else may have taken the tab while the server answered;
            // only the page this probe belongs to may be handed back.
            if (!isActiveSession(tabId, session) || session.address != expected) return@launch
            if (confirmed == null ||
                WebPagePolicy.verdict(confirmed, settled = true) != WebPagePolicy.Verdict.NOT_PAGE
            ) {
                // A page, or an address whose type nobody named: the browser
                // keeps it, exactly where the eager path would have put it.
                session.settleConfirmation()
                return@launch
            }
            // Not a page: reopen the same stop with the real type. If the
            // route hands it to an external viewer, no renderer replaced the
            // guess and the stop is dropped here.
            reopen(confirmed)
            if (pageOf(guessedStop)?.renderer === session) closeRenderer(tabId)
        }
    }

    /**
     * Shared opener/renderer dispatch. Every file — a listed entry, an archive
     * entry, a link hit, a typed address — takes this path: an opener claims it
     * first (archives), then a renderer, then the platform viewer.
     *
     * A live address with no facts yet goes straight to the browser renderer
     * when nothing about the address itself says "file": the page is what the
     * server is about to send anyway, so asking for its type first would push
     * the first byte back by a full round trip. [confirmPage] settles that guess
     * and reopens the node through this same function when it was wrong.
     */
    suspend fun routeVFile(
        tabId: String,
        file: VFile,
        address: String,
        name: String,
        contentHint: ContentInfo? = null,
        host: RendererContext,
        /** A document the user opened: it becomes a home row. */
        recordHome: Boolean = true,
        /** A new location: the outgoing one becomes a Back stop. */
        pushPage: Boolean = true,
    ) {
        val live = file.liveAddress != null
        val optimistic = contentHint == null && live &&
            WebPagePolicy.likelyPage(address)
        // An optimistic address deliberately keeps its facts unknown: that is
        // what makes the route table hand it to the browser without a request.
        val content = contentHint ?: if (optimistic) {
            null
        } else {
            withContext(Dispatchers.IO) { file.describe().getOrNull() }
        }
        val outcome = withContext(Dispatchers.IO) {
            OpenTable.route(file, content, OpenContext(host))
        }
        // Only a plain provider path becomes a home row; a view address is
        // rebuilt from its own text when it is reopened. A live resource is a
        // page row, every other node a file row — the node itself decides.
        if (recordHome && !file.path.isViewed) {
            val kind = if (file.liveAddress != null) HomeEntryKind.WEB else HomeEntryKind.FILE
            recordHome(kind, address, name, address)
        }
        applyOpen(tabId, file, address, name, content, host, pushPage, outcome)
        if (optimistic) {
            // The renderer that took the tab is the one whose address is a
            // page; the optimistic open made it the current stop already.
            val page = pendingOf(tabId)?.currentPage?.renderer ?: activeSession(tabId)
            if (page?.webAddress != null) confirmPage(tabId, file, page) { facts ->
                // The reopen carries the facts, so the table cannot guess a
                // second time. It pushes no page and records no home row:
                // the optimistic open already made this address the current
                // stop and its own Home row. A fresh host belongs to the
                // replacement session.
                routeVFile(
                    tabId = tabId,
                    file = file,
                    address = address,
                    name = name,
                    contentHint = facts,
                    host = contextFor(tabId, null),
                    recordHome = false,
                    pushPage = false,
                )
            }
        }
    }


    fun rendererContextFor(id: String, pendingPageId: String? = null): ShellRendererContext {
        val hostScope = scope
        val hostContext = context
        val host = object : ShellRendererContext {
            override var boundPageId: String? = null
            override val scope: CoroutineScope get() = hostScope
            override val context: Context? get() = hostContext

            /** The uncommitted renderer this host is rendering, if any. */
            fun boundPending(): FileTab? {
                val pid = pendingPageId ?: return null
                val pageId = boundPageId ?: return null
                return pendingNavigations[id]?.takeIf {
                    it.currentPage.id == pid && it.currentPage.id == pageId
                }
            }

            /** The committed page this host is rendering, if any. */
            fun boundCommitted(): FileTab? {
                if (pendingPageId != null) return null
                val pageId = boundPageId ?: return null
                return tabs.firstOrNull { it.id == id }?.takeIf {
                    it.currentPage.id == pageId
                }
            }

            override fun bindPage(pageId: String?) {
                boundPageId = pageId
            }

            override fun focus() {
                val pageId = boundPageId ?: return
                val tab = tabs.firstOrNull { candidate ->
                    candidate.pages.any { it.id == pageId }
                } ?: return
                if (pendingNavigations[tab.id] != null) return
                // Asking to be seen cancels the pending close: the renderer's
                // own dialog is about to take over.
                if (leavePrompt?.ownerPageId == pageId) {
                    leavePrompt = null
                    leaveSaving = false
                }
                activeTabId.value = tab.id
                val index = tab.pages.indexOfFirst { it.id == pageId }
                if (index < 0 || tab.pageIndex == index) return
                leavePage(tab.currentPage.id)
                replaceTab(tab.movedTo(index))
            }

            override fun submitPath(path: RendererPath?) {
                val pageId = boundPageId ?: return
                pageOf(pageId)?.pathInfo = path
            }

            override fun submitTitle(title: String?) {
                val pageId = boundPageId ?: return
                pageOf(pageId)?.title = title
            }

            override val opener: NodeOpener get() = OpenTable

            override fun open(renderer: RendererBase<*>) {
                val tab = currentTab()
                val page = Page(
                    path = renderer.pagePath,
                    kind = renderer.pageKind,
                    title = renderer.title,
                )
                page.renderer = renderer
                cancelPending(tab.id)
                pendingNavigations[tab.id] = tab.appended(page)
            }

            override fun navigate(node: io.furryr.file.core.VNode, rendererId: String?) {
                if (node is VFolder) {
                    if (node.addressable) navigateTo(node.path.toString(), id)
                    else navigateToFolder(node, id)
                }
            }

            override fun updateTitle(title: String?) {
                // A late title from a renderer that no longer owns the page
                // must not rename another page.
                val pageId = boundPageId ?: return
                if (boundPending() == null && boundCommitted() == null) return
                val page = pageOf(pageId) ?: return
                val label = title?.removePrefix("• ")?.trim()?.takeIf { it.isNotBlank() }
                if (label != null) {
                    page.title = label
                }
                if (title != null) {
                    val renderer = page.renderer
                    if (renderer?.isLive == true) {
                        val address = renderer.address ?: return
                        HomeStore.updateTitle(address, title)
                        homeVersion++
                    }
                }
            }

            /**
             * A page moved itself. The tab holds the pages, so a moved page
             * either lands on a stop the tab already made (the page's own
             * back/forward) or appends the step it just took.
             */
            override fun onPageVisited(address: String, replacePrevious: String?) {
                val pageId = boundPageId ?: return
                val pending = boundPending()
                if (pending != null) {
                    val current = pending.currentPage
                    if (current.path == address) return
                    val moved = current.renderer ?: return
                    val updated = current.copy(path = address, kind = PageKind.PAGE, title = null)
                    updated.renderer = moved
                    pendingNavigations[id] = pending.replacedCurrent(updated)
                    return
                }
                if (boundCommitted() == null) return
                val moved = pageOf(pageId)?.renderer ?: return
                // A page-driven step supersedes a folder load in this tab.
                cancelPending(id)
                val tab = tabs.firstOrNull { it.id == id } ?: return
                val current = tab.currentPage
                if (current.path == address) return
                // A redirect never rendered its source page, so its provisional
                // stop is replaced in place, not pushed.
                if (replacePrevious != null && current.path == replacePrevious) {
                    val redirected = current.copy(path = address, kind = PageKind.PAGE, title = null)
                    replaceTab(
                        tab.replacedCurrent(redirected),
                    )
                    return
                }
                // The page being left keeps the title it last reported.
                val titled = tab.replacedCurrent(
                    current.copy(
                        title = current.title,
                    ),
                )
                val known = titled.pages.indexOfLast {
                    it.path == address && it.kind == PageKind.PAGE && it.id != current.id
                }
                if (known >= 0) {
                    val target = titled.pages[known]
                    // The same WebView now shows the target page. Move its
                    // renderer instead of releasing it; the old page is a
                    // cache hit, not an owner.
                    current.renderer = null
                    target.close()
                    target.renderer = moved
                    replaceTab(
                        titled.movedTo(known),
                    )
                    return
                }
                val next = Page(path = address, kind = PageKind.PAGE)
                current.renderer = null
                next.renderer = moved
                replaceTab(titled.appended(next))
            }

            override fun updateCapabilities(capabilities: RenderCapabilities) = Unit

            override fun confirmLeave(
                message: String?,
                canSave: Boolean,
                onSave: (suspend () -> Result<Unit>)?,
                onLeave: () -> Unit,
                onCancel: () -> Unit,
            ) {
                val pageId = boundPageId ?: return onLeave()
                requestLeave(
                    LeavePrompt(
                        ownerTabId = id,
                        ownerPageId = pageId,
                        message = message,
                        canSave = canSave,
                        onSave = onSave,
                        onLeave = onLeave,
                        onCancel = onCancel,
                    )
                )
            }

            override fun openFile(file: VFile, content: ContentInfo?) {
                // The node already carries its address, view levels included.
                // A link is a navigation inside the tab, not a document the
                // user opened, so it does not become a home entry.
                openVFileFn(
                    file,
                    file.path.toString(),
                    file.name,
                    content,
                    false,
                    true,
                    false,
                )
            }

            override fun copyFile(file: VFile, content: ContentInfo?) {
                // Put the node on the clipboard; the user picks the destination
                // by switching tabs and pasting there.
                startClipboard(
                    listOf(
                        ClipboardEntry(
                            node = file,
                            name = content?.name?.takeIf { it.isNotBlank() } ?: file.name,
                        ),
                    ),
                    ClipboardAction.COPY,
                )
            }

            override fun shareFile(file: VFile, content: ContentInfo?) {
                io.furryr.file.ui.util.shareFile(context, file, content)
            }

            override fun openEntry(entry: FileEntry, node: VNode?) {
                if (entry.isDirectory) {
                    when (node) {
                        is VFolder -> if (node.addressable) navigateTo(node.path.toString(), id)
                        else navigateToFolder(node, id)
                        else -> navigateTo(entry.path, id)
                    }
                } else {
                    when (node) {
                        is VFile -> openVFileFn(
                            node,
                            entry.path,
                            entry.name,
                            null,
                            true,
                            true,
                            true,
                        )
                        else -> openFile(context, entry)
                    }
                }
            }

            override fun requestShare(entries: List<FileEntry>) {
                scope.launch { shareEntries(context, entries) }
            }

            override fun requestDelete(entries: List<FileEntry>) {
                showDeleteConfirm = entries
            }

            override fun requestRename(entries: List<FileEntry>) {
                if (entries.size > 1) showBatchRenameDialog = true
                else entries.firstOrNull()?.let { showRenameDialog = it }
            }

            override fun requestProperties(entry: FileEntry) {
                showEntryInfo = entry
            }

            override fun putOnClipboard(entries: List<ClipboardEntry>, action: ClipboardAction) {
                startClipboard(entries, action)
            }

            override fun onSelectionChanged(
                paths: Set<String>,
                lastIndex: Int,
                entries: List<ClipboardEntry>,
            ) {
                val pageId = boundPageId ?: return
                if (boundCommitted() == null) return
                val tabIndex = tabs.indexOfFirst { it.id == id }
                if (tabIndex >= 0) {
                    tabs[tabIndex] = tabs[tabIndex].copy(
                        selectedPaths = paths,
                        lastSelectedIndex = lastIndex,
                    )
                }
                if (id == currentTab().id) ClipboardStore.select(entries)
            }

            override fun onFolderLoaded(path: String) {
                // commit() promotes the pending folder once the listing
                // has produced entries (or its error view).
            }

            override fun onFolderCancelled() {
                val pageId = boundPageId ?: return
                if (boundPending() == null && boundCommitted() == null) return
                cancelPending(id)
                highlightPath = null
                pendingScrollPath = null
            }

            override fun onScrollConsumed() {
                pendingScrollPath = null
            }

            override fun onHighlightConsumed() {
                highlightPath = null
                highlightDirectory = null
            }

            override fun onFileDragStart() {
                terminalDragGeneration++
                terminalDropAccepted = false
                showTerminalSheet = true
            }

            override fun onFileDragEnd() {
                val generation = terminalDragGeneration
                scope.launch {
                    // Give the platform drop (ACTION_DROP) a chance to arrive
                    // before deciding whether this drag was cancelled.
                    delay(150)
                    if (generation == terminalDragGeneration && !terminalDropAccepted) {
                        showTerminalSheet = false
                    }
                }
            }

            override fun openHome(entry: HomeEntry) = openHomeEntryFn(entry)

            override fun toggleBookmark(entry: HomeEntry) = toggleHomeBookmarkFn(entry)

            override fun removeRecent(key: String) {
                HomeStore.removeRecent(key)
                homeVersion++
            }

            override fun removeBookmark(key: String) {
                HomeStore.removeBookmark(key)
                homeVersion++
            }

            override fun clearRecent() {
                HomeStore.clearRecent()
                homeVersion++
            }

            override fun clearBookmarks() {
                HomeStore.clearBookmarks()
                homeVersion++
            }

            override fun cancelOpening() = cancelOpeningFn()

            override fun bookmarkEntry(): HomeEntry? = bookmarkEntryFn()

            override fun isBookmarked(entry: HomeEntry): Boolean =
                HomeStore.isBookmarked(entry.kind, entry.location)

            override fun folderView(): FolderRendererState? {
                val pending = boundPending()
                if (pending != null) {
                    val page = pending.currentPage
                    return FolderRendererState(
                        path = page.path,
                        committedPath = page.path,
                        folder = page.folder,
                        scrollToPath = pendingScrollPath,
                        highlightPath = highlightPath,
                        highlightDirectory = highlightDirectory,
                        opening = false,
                    )
                }
                val tab = boundCommitted() ?: return null
                return FolderRendererState(
                    path = tab.path,
                    committedPath = tab.path,
                    folder = tab.folder,
                    scrollToPath = pendingScrollPath,
                    highlightPath = highlightPath,
                    highlightDirectory = highlightDirectory,
                    opening = nodeOpeningTab == id,
                )
            }

            override fun homeView(): HomeRendererState? {
                if (boundPending() == null && boundCommitted() == null) return null
                // Reading the version subscribes this getter to HomeStore edits.
                homeVersion
                return HomeRendererState(
                    recent = HomeStore.recent(),
                    bookmarks = HomeStore.bookmarks(),
                    opening = nodeOpeningTab == id,
                )
            }

            override fun reportError(error: Throwable) {
                Toast.makeText(context, error.message ?: "Renderer failed", Toast.LENGTH_SHORT).show()
            }

            override fun requestClose() {
                val pending = boundPending()
                if (pending != null) {
                    if (pendingNavigations[id] === pending) {
                        pendingNavigations.remove(id)
                        releasePendingPages(pending)
                    }
                    return
                }
                closeRenderer(id)
            }

            override fun commit(content: RendererContent) {
                val pageId = boundPageId ?: return
                pageContents[pageId] = content
                val pending = pendingNavigations[id]?.takeIf {
                    it.currentPage.id == pageId && it.currentPage.id == pendingPageId
                } ?: return
                commitPending(id, pending)
            }
        }
        return host
    }
    contextFor = ::rendererContextFor

    fun cancelNodeOpening() {
        ++nodeOpenGeneration
        nodeOpenJob?.cancel()
        nodeOpenJob = null
        nodeOpeningTab = null
    }
    cancelOpeningFn = ::cancelNodeOpening

    /**
     * Run an asynchronous open under the tab's opening indicator. A remote
     * probe or archive index may take seconds; the previous view must keep
     * showing a loading overlay instead of appearing frozen.
     */
    fun trackNodeOpen(tabId: String, block: suspend () -> Unit) {
        val generation = ++nodeOpenGeneration
        nodeOpenJob?.cancel()
        nodeOpeningTab = tabId
        nodeOpenJob = scope.launch {
            try {
                block()
            } finally {
                if (generation == nodeOpenGeneration) {
                    nodeOpeningTab = null
                    nodeOpenJob = null
                }
            }
        }
    }

    /**
     * The one fire-and-forget file open for the current tab. Every caller that
     * already holds a node — a directory row, a link hit, a typed address, a
     * home row — lands here; [routeVFile] owns the describe → route → apply
     * pipeline. [tracked] wraps it in the shell's opening indicator.
     */
    fun openVFile(
        file: VFile,
        address: String,
        name: String,
        contentHint: ContentInfo? = null,
        recordHome: Boolean = true,
        pushPage: Boolean = true,
        tracked: Boolean = false,
    ) {
        val tabId = currentTab().id
        val host = rendererContextFor(tabId)
        if (tracked) {
            trackNodeOpen(tabId) {
                routeVFile(tabId, file, address, name, contentHint, host, recordHome, pushPage)
            }
        } else {
            scope.launch {
                routeVFile(tabId, file, address, name, contentHint, host, recordHome, pushPage)
            }
        }
    }
    openVFileFn = ::openVFile

    fun toggleHomeBookmark(entry: HomeEntry) {
        HomeStore.toggleBookmark(entry)
        homeVersion++
    }
    toggleHomeBookmarkFn = ::toggleHomeBookmark

    fun currentBookmarkEntry(): HomeEntry? {
        val tab = currentTab()
        val path = tab.path
        // A page or an opened file is bookmarkable even when it was opened
        // from home; only the directory branch needs a real location. The
        // session knows whether its address is live or a projected file.
        val session = activeSession(tab.id)
        if (session != null && session.address != null) {
            if (session.suppressBookmark) return null
            val address = session.address!!
            val kind = if (session.isLive) HomeEntryKind.WEB else HomeEntryKind.FILE
            return HomeEntry(kind, address, session.title ?: tab.currentPage.title ?: address, address)
        }
        if (isHomePath(path)) return null
        // A loaded directory is bookmarkable even though it has a folder hint.
        val folderPath = tab.folder?.path?.toString() ?: path
        if (isHomePath(folderPath)) return null
        return HomeEntry(
            HomeEntryKind.FOLDER,
            folderPath,
            Path.parse(folderPath).name ?: folderPath,
            folderPath,
        )
    }

    fun toggleCurrentBookmark() {
        currentBookmarkEntry()?.let { toggleHomeBookmark(it) }
    }
    bookmarkEntryFn = ::currentBookmarkEntry

    /** The one path→node step: address text enters the node graph here. */
    suspend fun resolveNode(path: Path): VNode? =
        withContext(Dispatchers.IO) { Vfs.node(path).getOrNull() }

    /** The directory node for [path], reusing [hint] when the tab already has one. */
    suspend fun folderFor(path: String, hint: VFolder? = null): VFolder? =
        hint ?: withContext(Dispatchers.IO) { Vfs.folder(path).getOrNull() }

    /**
     * Go to [address] in [tabId]: the provider decides what it is.
     *
     * A folder becomes the tab's location (the loader lists it), a file goes
     * through the same opener/renderer table a directory row uses. This is the
     * single entry for a typed address, a home row and a page — nothing
     * here reads a scheme, and no caller needs a second "open a URL" path.
     */
    /** The resolve-and-route half of [openAddress], usable under an opening indicator. */
    suspend fun openAddressNow(address: String, tabId: String) {
        if (tabs.none { it.id == tabId }) return
        val parsed = NodeAddress.parse(address)
        val node = resolveNode(parsed)
        when (node) {
            // A view level already resolved to a node: keep it, so an
            // archive root is not re-opened from its path.
            is VFolder -> if (parsed.isViewed) navigateToFolder(node, tabId)
            else navigateTo(address, tabId, view = node.takeIf { it.isView })
            is VFile -> routeVFile(tabId, node, address, node.name, host = rendererContextFor(tabId))
            null -> navigateTo(address, tabId, record = false)
        }
    }

    fun openAddress(address: String, tabId: String = currentTab().id) {
        if (guardRenderer(tabId) { openAddress(address, tabId) }) return
        scope.launch { openAddressNow(address, tabId) }
    }

    /** Open a home row through the same path a directory row uses. */
    fun openHomeEntry(entry: HomeEntry) {
        val tabId = currentTab().id
        when (entry.kind) {
            // A directory commits its renderer in this frame; that renderer
            // owns the delayed list spinner.
            HomeEntryKind.FOLDER -> navigateTo(entry.location)
            // Resolving a stored file or URL may take a remote round trip, so
            // home keeps its own overlay until the route hands over.
            HomeEntryKind.WEB, HomeEntryKind.FILE ->
                trackNodeOpen(tabId) { openAddressNow(entry.location, tabId) }
        }
    }
    openHomeEntryFn = ::openHomeEntry

    /** Show the home page, even when a page opened from home is still active. */
    fun goHome() {
        if (guardRenderer(currentTab().id) { goHome() }) return
        val tab = currentTab()
        // Empty-address navigation supersedes a load still in flight.
        cancelPending(tab.id)
        if (!isHomePath(tab.path)) {
            // The current page/file stays a Back stop.
            navigateTo(HomePath)
            return
        }
        // A renderer opened from home sits on top of the home location. The
        // leave guard may already have removed its session, so the stop kind,
        // not the map, decides whether there is an overlay to drop.
        if (tab.currentPage.kind != PageKind.LOCATION) {
            closeRenderer(tab.id)
            return
        }
        replaceCurrent(tab.copy(folder = null))
    }

    /** Reveal [address] selected inside [parent], the jump-dialog behaviour. */
    fun selectInParent(parent: VFolder, address: String) {
        val tabId = currentTab().id
        if (parent.path.toString() == currentTab().path) {
            closeRenderer(tabId)
            val listing = rendererOf(tabId)?.listingHandle
            val index = listing?.entries?.indexOfFirst { it.path == address } ?: -1
            listing?.setSelection(setOf(address), index)
            pendingScrollPath = address
            return
        }
        navigateTo(parent.path.toString())
        highlightPath = address
        highlightDirectory = parent.path.toString()
        pendingScrollPath = address
    }

    /**
     * Address-bar submit.
     *
     * One resolve answers what the text is: a folder becomes the location, a
     * file opens (or is shown selected inside its own folder, the old jump
     * behaviour), and text that is neither becomes a search. A web address is
     * just the case where the provider answering is the live one.
     */
    fun jumpTo(raw: String) {
        if (guardRenderer(currentTab().id) { jumpTo(raw) }) return
        val input = raw.trim()
        // An empty address is the way back to the home page.
        if (input.isEmpty()) {
            goHome()
            return
        }
        val tabId = currentTab().id
        // Every `!` level is explicit, so one resolve reaches any depth: the
        // provider says what the address is and the opener registry how a file
        // is entered, so no boundary is found by probing the filesystem.
        val address = NodeAddress.parse(input)
        scope.launch {
            val node = resolveNode(address)
            when (node) {
                is VFolder -> {
                    if (address.isViewed) navigateToFolder(node, tabId)
                    else navigateTo(input, tabId, view = node.takeIf { it.isView })
                }
                is VFile -> {
                    // A listed file is shown selected in its own folder; a node
                    // without a browsable parent (a live address, a view root)
                    // is simply opened.
                    val parent = node.parent
                    if (parent != null && !address.isViewed) {
                        selectInParent(parent, input)
                    } else {
                        openVFile(node, address.toString(), node.name)
                    }
                }
                else -> when {
                    // A missing absolute path or scheme URI is still an
                    // address: show its error state instead of searching.
                    NodeAddress.isAddress(input) -> navigateTo(input, record = false)
                    else -> BrowseAddress.normalize(input)?.let { url -> openAddress(url) }
                        ?: openAddress(SearchSettings.searchUrl(context, input))
                }
            }
        }
    }

    /**
     * Rebuild a page/file stop whose renderer is gone (Back into an editor, an
     * activity recreate, or a page the WebView no longer caches).
     */
    fun restorePageNode(tabId: String, stop: Page) {
        if (stop.restoring) return
        stop.restoring = true
        scope.launch {
            try {
                val node = resolveNode(NodeAddress.parse(stop.path))
                // The tab may have moved on while the address was resolving.
                val current = tabs.firstOrNull { it.id == tabId }
                if (current?.currentPage?.id != stop.id ||
                    pendingNavigations[tabId] != null ||
                    pageOf(stop.id)?.renderer != null
                ) {
                    return@launch
                }
                when (node) {
                    is VFile -> {
                        routeVFile(
                            tabId = tabId,
                            file = node,
                            address = stop.path,
                            name = node.name,
                            contentHint = null,
                            host = rendererContextFor(tabId),
                            recordHome = false,
                            pushPage = false,
                        )
                        // The route may have handed the file to an external
                        // viewer. A file/page stop with no renderer would only
                        // draw a blank tab; reveal the location behind it.
                        val tab = tabs.firstOrNull { it.id == tabId }
                        if (pendingNavigations[tabId] == null &&
                            tab?.currentPage?.id == stop.id &&
                            tab.currentPage.kind != PageKind.LOCATION &&
                            pageOf(stop.id)?.renderer == null
                        ) {
                            closeRenderer(tabId)
                        }
                    }
                    is VFolder -> updateTab(tabId) { tab ->
                        // A stop recorded as a file/page can turn out to be a
                        // folder after the fact; the directory renderer takes it.
                        if (tab.currentPage.id == stop.id) {
                            tab.replacedCurrent(
                                stop.copy(kind = PageKind.LOCATION, folder = node, title = null),
                            )
                        } else {
                            tab
                        }
                    }
                    null -> Unit
                }
            } finally {
                stop.restoring = false
            }
        }
    }

    /**
     * Make stop [index] the tab's location.
     *
     * A live page the WebView already holds is retraced without a request; a
     * directory reuses its warm session; every other stop is rebuilt through
     * the same route table a directory row uses.
     */
    fun moveToPage(tab: FileTab, pageId: String) {
        if (tab.id != currentTab().id) return
        if (pendingNavigations[tab.id] != null) return
        val index = tab.pages.indexOfFirst { it.id == pageId }
        if (index < 0 || index == tab.pageIndex) return
        val target = tab.pages[index]
        val current = tab.currentPage
        val session = current.renderer
        // A WebView is one view: moving to a page in its own cache transfers
        // the renderer instead of releasing and rebuilding it.
        if (target.kind == PageKind.PAGE && session != null && session.retrace(target.path)) {
            current.renderer = null
            target.close()
            target.renderer = session
            replaceTab(tab.movedTo(index))
            return
        }
        clearLeavePrompt(tab.id)
        leavePage(current.id)
        val moved = tab.movedTo(index)
        replaceTab(moved)
        when (target.kind) {
            PageKind.LOCATION -> Unit
            PageKind.FILE, PageKind.PAGE -> {
                // A stale renderer for the target would otherwise be replaced
                // without its release hook.
                target.close()
                restorePageNode(tab.id, target)
            }
        }
    }

    fun goBack() {
        val tab = currentTab()
        if (pendingNavigations[tab.id] != null) return
        if (!tab.canGoBack) {
            // A trimmed list can leave an overlay without a location behind it;
            // dismissing the overlay is still the right undo.
            if (tab.currentPage.kind != PageKind.LOCATION &&
                !guardRenderer(tab.id) { goBack() }
            ) {
                closeRenderer(tab.id)
            }
            return
        }
        if (guardRenderer(tab.id) { goBack() }) return
        moveToPage(tab, tab.pages[tab.pageIndex - 1].id)
    }

    fun goForward() {
        val tab = currentTab()
        if (pendingNavigations[tab.id] != null) return
        if (!tab.canGoForward) return
        if (guardRenderer(tab.id) { goForward() }) return
        moveToPage(tab, tab.pages[tab.pageIndex + 1].id)
    }

    /** Select a page in the page panel; the same move as Back or Forward. */
    fun jumpToPage(pageId: String) {
        val tab = currentTab()
        if (pendingNavigations[tab.id] != null) return
        if (tab.pages.none { it.id == pageId } || tab.currentPage.id == pageId) return
        if (guardRenderer(tab.id) { jumpToPage(pageId) }) return
        moveToPage(tab, pageId)
    }

    fun goParent() {
        val tab = currentTab()
        val renderer = activeSession(tab.id)
        val rendererParent = renderer?.parent
        if (rendererParent != null) {
            // Up means the folder that owns the edited file, not the folder
            // the tab happened to show before the file was opened.
            val parentAddress = NodeAddress.format(rendererParent.path)
            // A document opened from its own folder sits over that folder, so
            // "up" appends the list as a new stop and keeps the document as a
            // Back stop, rather than discarding it.
            if (parentAddress == tab.path && tab.currentPage.kind != PageKind.LOCATION) {
                if (rendererParent.addressable) {
                    navigateTo(parentAddress, force = true)
                } else {
                    navigateToFolder(rendererParent)
                }
                return
            }
            // A provider folder is addressed by its path; only a view folder
            // needs its node kept as a hint.
            if (rendererParent.addressable) navigateTo(parentAddress)
            else navigateToFolder(rendererParent)
            return
        }
        val activePath = pendingOf(tab.id)?.path ?: tab.path
        if (isHomePath(activePath)) return
        val activeFolder = pendingOf(tab.id)?.folder ?: tab.folder
        if (activeFolder != null) {
            val parent = activeFolder.parent ?: return
            if (parent.addressable) navigateTo(parent.path.toString()) else navigateToFolder(parent)
            return
        }
        val parent = Path.parse(activePath).parent()?.toString() ?: return
        navigateTo(parent)
    }

    /** Single refresh entry point for the directory listing. */
    fun refreshFileList(
        animate: Boolean = true,
        highlightPath: String? = null,
        highlightDirectory: String? = null,
    ) {
        val listing = rendererOf(currentTab().id)?.listingHandle ?: return
        listing.refreshListing(animate, highlightPath, highlightDirectory)
    }

    /** Capabilities of whatever owns the current tab: renderer or directory. */
    fun currentCapabilities(): RenderCapabilities {
        val tab = currentTab()
        val state = activeTabState(tab.id) ?: tab
        val renderer = rendererOf(tab.id)
        if (renderer != null) {
            return renderer.capabilities.copy(
                // Pages belong to the tab. A renderer that can dismiss
                // itself (a page or the editor) also makes Back available.
                canGoBack = state.canGoBack || renderer.canDismiss,
                canGoForward = state.canGoForward,
            )
        }
        return RenderCapabilities(
            canGoBack = state.canGoBack,
            canGoForward = state.canGoForward,
            canCreate = false,
        )
    }

    /** Up exists only when the owner exposes a parent node. */
    fun currentCanGoParent(): Boolean {
        val tab = currentTab()
        val renderer = rendererOf(tab.id)
        if (renderer != null) return renderer.parent != null
        val folder = tab.folder
        return folder?.parent != null ||
            (folder == null && Path.parse(tab.path).parent() != null)
    }

    val handleHereRequest by rememberUpdatedState(newValue = { request: HereNavigation.Request ->
        val tab = currentTab()
        clearLeavePrompt(tab.id)
        // A here request supersedes an in-flight navigation in the current tab.
        cancelPending(tab.id)
        showTerminalSheet = false
        highlightPath = request.selectedPath
        highlightDirectory = request.directory
        pendingScrollPath = request.selectedPath
        // Re-entering the same plain directory is not a step; an overlay over
        // that directory still gets a new location stop behind it.
        if (tab.currentPage.kind == PageKind.LOCATION && tab.path == request.directory) {
            replaceCurrent(
                tab.copy(
                    folder = null,
                    selectedPaths = emptySet(),
                    lastSelectedIndex = -1,
                ),
            )
            refreshFileList(
                highlightPath = highlightPath,
                highlightDirectory = highlightDirectory,
            )
        } else {
            val page = Page(path = request.directory, kind = PageKind.LOCATION)
            page.renderer = RendererSessions.location(
                path = request.directory,
                initialSelection = tab.selectedPaths,
                initialSelectedIndex = tab.lastSelectedIndex,
                homeTitle = context.getString(R.string.home_title),
            )
            pendingNavigations[tab.id] = tab.appended(page)
        }
    })
    fun dispatchHere(request: HereNavigation.Request) {
        val id = currentTab().id
        if (guardRenderer(id) { pendingHereRequest = request }) return
        handleHereRequest(request)
    }
    LaunchedEffect(pendingHereRequest) {
        pendingHereRequest?.let {
            pendingHereRequest = null
            handleHereRequest(it)
        }
    }
    val activityLifecycle = (context as? LifecycleOwner)?.lifecycle
    LaunchedEffect(activityLifecycle) {
        if (activityLifecycle != null) {
            activityLifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                HereNavigation.requests.collect { dispatchHere(it) }
            }
        } else {
            HereNavigation.requests.collect { dispatchHere(it) }
        }
    }

    // Pages own their renderers. An Activity recreate resets every session's
    // view; the model state stays and the next render rebuilds it.
    DisposableEffect(Unit) {
        onDispose {
            allPages().forEach(Page::reset)
        }
    }

    val activeRenderer = rendererOf(currentTab().id)
    val activePending = pendingOf(currentTab().id)
    var pendingSpinner by remember { mutableStateOf(false) }
    LaunchedEffect(activePending?.currentPage?.id) {
        pendingSpinner = false
        if (activePending != null) {
            delay(300)
            pendingSpinner = true
        }
    }
    // An activity recreate keeps the page and its renderer but drops views.
    // Rebuild a page/file page when its renderer is gone; a directory mints
    // its renderer on demand from rendererOf().
    val composedPage = currentTab().currentPage
    LaunchedEffect(composedPage.id) {
        val tab = currentTab()
        if (composedPage.kind == PageKind.LOCATION) return@LaunchedEffect
        if (pendingNavigations[tab.id] != null) return@LaunchedEffect
        if (composedPage.renderer != null) return@LaunchedEffect
        restorePageNode(tab.id, composedPage)
    }
    // Block reads of remote HTTP(S) handles (archive index, proxy descriptor)
    // publish their progress here; the top strip below mirrors page loading.
    val remoteTransfers by WebTransfers.progress.collectAsState()
    val homeRecent = remember(homeVersion) { HomeStore.recent() }
    val homeBookmarks = remember(homeVersion) { HomeStore.bookmarks() }
    val pathSuggestionSource = remember(homeBookmarks, homeRecent) {
        object : PathSuggestionSource {
            override fun local(query: String): List<PathSuggestion> =
                PathSuggestions.local(query, homeBookmarks, homeRecent)

            override suspend fun remote(query: String): List<PathSuggestion> =
                PathSuggestions.remote(context, query)
        }
    }
    fun chooseTitleSuggestion(suggestion: PathSuggestion) {
        if (suggestion.source == SuggestionSource.INTERNET) {
            jumpTo(suggestion.target)
        } else {
            openAddress(suggestion.target)
        }
    }

    fun closeTabNow(tabId: String) {
        if (tabs.size <= 1) return
        val index = tabs.indexOfFirst { it.id == tabId }
        if (index < 0) return
        val closing = tabs.removeAt(index)
        clearLeavePrompt(closing.id)
        releaseStops(closing, pendingNavigations.remove(closing.id))
        selectTab(selectedTabIndex.coerceAtMost(tabs.lastIndex))
    }

    /**
     * The close button asks the renderer, page by page. A history page keeps
     * its live session too, so every page gets the same chance to veto; the
     * shell only supplies the context and the final removal.
     */
    fun closeTab(index: Int) {
        if (tabs.size <= 1) return
        val tab = tabs.getOrNull(index) ?: return
        val pages = tab.pages.filter { it.renderer != null }
        fun step(at: Int) {
            val page = pages.getOrNull(at)
            if (page == null) {
                closeTabNow(tab.id)
                return
            }
            val renderer = page.renderer ?: return step(at + 1)
            if (renderer.canClose()) return step(at + 1)
            renderer.requestClose(contextFor(tab.id, null).also { it.bindPage(page.id) }) {
                step(at + 1)
            }
        }
        step(0)
    }

    fun moveTab(from: Int, to: Int) {
        if (from == to || from !in tabs.indices || to !in tabs.indices) return
        val selectedId = currentTab().id
        val moved = tabs.removeAt(from)
        tabs.add(to, moved)
        selectTab(tabs.indexOfFirst { it.id == selectedId }.coerceAtLeast(0))
    }

    fun moveTabBy(id: String, direction: Int): Boolean {
        val index = tabs.indexOfFirst { it.id == id }
        if (index < 0) return false
        val target = index + direction
        if (target !in tabs.indices) return false
        moveTab(index, target)
        return true
    }

    /**
     * Close every tab pointing into `container://<name>/...`.
     *
     * Called when a container is killed: its filesystem disappears, so any
     * lingering tab would only show errors. When the last tab is closed a
     * fresh home tab is opened so the app never ends up with zero tabs.
     */
    fun closeTabsForContainer(name: String) {
        val matches = tabs.indices.filter { index ->
            val tab = tabs[index]
            val active = pendingNavigations[tab.id]?.path ?: tab.path
            val parsed = Path.parse(active)
            parsed.scheme == ContainerUris.SCHEME && parsed.host == name
        }
        if (matches.isEmpty()) return
        matches.sortedDescending().forEach {
            val removed = tabs.removeAt(it)
            clearLeavePrompt(removed.id)
            releaseStops(removed, pendingNavigations.remove(removed.id))
        }
        if (tabs.isEmpty()) {
            tabs.add(newHomeTab())
            selectTab(0)
        } else {
            selectTab(selectedTabIndex.coerceAtMost(tabs.lastIndex))
        }
    }

    /** The tab's directory as a node: the one path→node step operations use. */
    suspend fun folderOf(tab: FileTab): VFolder? = folderFor(tab.path, tab.folder)

    /** A listed entry as a node of [folder]. */
    suspend fun nodeOf(folder: VFolder?, name: String): VNode? =
        if (folder == null) null else withContext(Dispatchers.IO) { folder.child(name).getOrNull() }

    val clipboardController = ClipboardController(
        context = context,
        scope = scope,
        currentRenderer = { rendererOf(currentTab().id) },
        currentJob = { operationJob },
        allPages = ::allPages,
        onHidden = { operationHidden = it },
        onJob = { operationJob = it },
        onProgress = { operationProgress = it },
        onTaskIds = { operationTaskIds = it },
        onRefresh = ::refreshFileList,
    )

    fun currentListing(): ListingHandle? = clipboardController.listing()

    fun selectedEntries(): List<FileEntry> = clipboardController.selectedEntries()

    fun clearSelection() {
        clipboardController.clearSelection()
    }

    fun clearClipboard() {
        clipboardController.clear()
    }

    fun performPaste() {
        clipboardController.paste()
    }

    LaunchedEffect(pendingTabIndex) {
        if (pendingTabIndex >= 0) {
            selectTab(pendingTabIndex)
            pendingTabIndex = -1
        }
    }

    var lastRefreshedTabIndex by remember { mutableIntStateOf(selectedTabIndex) }
    LaunchedEffect(selectedTabIndex) {
        if (lastRefreshedTabIndex != selectedTabIndex) {
            // A plain selection belongs to one tab: leaving it discards the
            // selection. A copy/move payload is frozen and allowed to cross.
            if (ClipboardStore.state.action == ClipboardAction.NONE) {
                val previous = tabs.getOrNull(lastRefreshedTabIndex)
                if (previous != null && previous.id != currentTab().id) {
                    val listing = previous.currentPage.renderer?.listingHandle
                    if (listing != null) {
                        previous.currentPage.renderer?.leave()
                        if (listing.selectedPaths.isNotEmpty()) listing.clearSelection()
                    } else {
                        updateTab(previous.id) {
                            it.copy(selectedPaths = emptySet(), lastSelectedIndex = -1)
                        }
                    }
                }
                ClipboardStore.clear()
            }
            lastRefreshedTabIndex = selectedTabIndex
            highlightPath = null
            highlightDirectory = null
            pendingScrollPath = null
            // No refresh token here: the folder renderer reloads when its
            // `path` changes, and bumping the token as well would queue a
            // second load that cancels the first mid-flight. On a remote
            // backend that cancelled command keeps writing into the shared
            // channel and corrupts the next listing.
        }
    }

    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "root_enabled" || key == "su_path" || key == "shell_enabled") {
                refreshFileList(animate = false)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    // Refresh listings and stats after initial startup or a fresh on-demand daemon launch.
    LaunchedEffect(Unit) {
        DaemonManager.status.collect { state ->
            if (state is DaemonStatus.Connected) {
                val currentGen = DaemonManager.generation
                if (currentGen > lastRefreshDaemonGen) {
                    lastRefreshDaemonGen = currentGen
                    refreshFileList(animate = false)
                    containerRefreshKey++
                }
            }
        }
    }

    // Container liveness is the named daemon's, so refresh when the daemon set
    // changes rather than when a terminal opens or closes.
    DisposableEffect(Unit) {
        val listener: () -> Unit = { containerRefreshKey++ }
        DaemonManager.addNamedChangeListener(listener)
        onDispose { DaemonManager.removeNamedChangeListener(listener) }
    }

    LaunchedEffect(Unit) {
        val permissions = storagePermissions().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        }
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            openAllFilesAccessSettings(context)
        }
    }

    BackHandler {
        if (currentTab().selectedPaths.isNotEmpty()) {
            clearSelection()
        } else if (drawerState.isOpen) {
            scope.launch { drawerState.close() }
        } else if (pendingForCurrent() != null) {
            cancelPending(currentTab().id)
            highlightPath = null
            pendingScrollPath = null
        } else if (currentTab().canGoBack || activeRenderer?.canDismiss == true) {
            // Back is the undo: it closes an editor, retraces a page and steps
            // out of a folder through pages first. "Up" is the parent action,
            // and only exists where something has a parent.
            goBack()
        } else if (isHomePath(currentTab().path)) {
            if (tabs.size > 1) {
                closeTab(selectedTabIndex)
            } else {
                (context as? Activity)?.finish()
            }
        } else {
            val currentPath = currentTab().path
            if (Path.parse(currentPath).parent() != null) {
                goParent()
            } else if (tabs.size > 1) {
                closeTab(selectedTabIndex)
            } else {
                (context as? Activity)?.finish()
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        // A WebView consumes horizontal scrolling itself, and the editor uses
        // horizontal gestures for selection, so the open-from-edge gesture
        // stays off for both. An already open drawer keeps the gesture so it
        // can still be swiped shut.
        gesturesEnabled = activeRenderer?.consumesEdgeGestures != true || drawerState.isOpen,
        drawerContent = {
            FileDrawerHost(
                darkTheme = darkTheme,
                onToggleTheme = onToggleTheme,
                locations = locations,
                refreshKey = containerRefreshKey,
                sortMode = drawerSortMode,
                containerOrder = containerNameOrder,
                onToggleSortMode = {
                    drawerSortMode = !drawerSortMode
                    if (drawerSortMode) {
                        scope.launch {
                            containerNameOrder = withContext(Dispatchers.IO) {
                                ContainerManager.listContainers(context)
                                    .getOrDefault(emptyList()).map { it.name }
                            }
                        }
                    } else {
                        containerNameOrder = emptyList()
                    }
                },
                onContainerReorder = { from, to ->
                    val mutable = containerNameOrder.toMutableList()
                    if (from in mutable.indices && to in mutable.indices) {
                        val item = mutable.removeAt(from)
                        mutable.add(to, item)
                        containerNameOrder = mutable
                    }
                },
                onNavigate = { address ->
                    navigateTo(address)
                    scope.launch { drawerState.close() }
                },
                onOpenAddress = { url ->
                    openAddress(url)
                    scope.launch { drawerState.close() }
                },
                onAddSaf = {
                    safFolderLauncher.launch(
                        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                            .setPackage("com.android.documentsui")
                    )
                },
                onSettingsClick = {
                    drawerSortMode = false
                    context.startActivity(Intent(context, SettingsActivity::class.java))
                },
                onContainersChanged = { containerRefreshKey++ },
                onKillTabs = { closeTabsForContainer(it) },
            )
        }
    ) {
        Box(Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier
                .onGloballyPositioned { rootCoordinates = it }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.type != PointerEventType.Press) continue
                            val change = event.changes.firstOrNull() ?: continue
                            val bounds = titleBounds
                            val coordinates = rootCoordinates
                            if (bounds == null || coordinates == null) continue
                            val position = coordinates.localToWindow(change.position)
                            if (!bounds.contains(position)) focusManager.clearFocus()
                        }
                    }
                },
            topBar = {
                ShellTopBar(
                    activeTab = currentTab(),
                    activeRenderer = activeRenderer,
                    tabRenderer = rendererOf(currentTab().id),
                    operationProgress = operationProgress,
                    operationHidden = operationHidden,
                    copyMode = ClipboardStore.state.inTransfer,
                    remoteTransfers = remoteTransfers,
                    suggestionSource = pathSuggestionSource,
                    suggestionAnchor = topBarBounds,
                    onBounds = { topBarBounds = it },
                    onTitleBounds = { titleBounds = it },
                    onNavigate = ::jumpTo,
                    onSuggestion = ::chooseTitleSuggestion,
                    onEditingChange = { titleEditing = it },
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onSettings = {
                        context.startActivity(Intent(context, SettingsActivity::class.java))
                    },
                    onExit = {
                        val activity = context as? Activity
                        val finish: () -> Unit = { activity?.finish() }
                        if (!guardRenderer(currentTab().id, finish)) finish()
                    },
                )
            },
            bottomBar = {
                ShellBottomBar(
                    tab = currentTab(),
                    renderer = rendererOf(currentTab().id),
                    capabilities = currentCapabilities(),
                    canGoParent = currentCanGoParent(),
                    canOpenTerminal = rendererOf(currentTab().id)?.terminalFactory != null ||
                        hasTerminalSessions,
                    pagesVisible = showPages,
                    onHeight = { bottomBarHeightPx = it },
                    onBack = ::goBack,
                    onForward = ::goForward,
                    onParent = ::goParent,
                    onTerminal = { showTerminalSheet = true },
                    onPagesDrag = { if (!showPages) pagesDragPx = it },
                    onPagesDragEnd = { revealed ->
                        pagesDragPx = 0f
                        if (!showPages && revealed >= pagesRevealThresholdPx) {
                            showPages = true
                        }
                    },
                    onShare = {
                        val entries = rendererOf(currentTab().id)
                            ?.listingHandle?.selectedEntries.orEmpty()
                        scope.launch { shareEntries(context, entries) }
                    },
                    onCopyMoveConfirm = ::performPaste,
                    onCopyMoveCancel = ::clearClipboard,
                )
            }
        ) { padding ->
            Box(modifier = Modifier.padding(padding)) {
                Column(modifier = Modifier.fillMaxSize()) {
                FileTabStrip(
                    tabs = tabs,
                    selectedIndex = selectedTabIndex,
                    onSelect = ::selectTab,
                    onClose = ::closeTab,
                    onMove = ::moveTabBy,
                    onNewTab = {
                        tabs.add(newHomeTab())
                        pendingTabIndex = tabs.lastIndex
                    },
                    onShowSessions = { showSessionManager = true },
                )

                val tab = currentTab()
                ShellContentHost(
                    tab = tab,
                    activeRenderer = activeRenderer,
                    pageContents = pageContents,
                    pending = pendingOf(tab.id),
                    pendingSpinner = pendingSpinner,
                    darkTheme = darkTheme,
                    contextFor = contextFor,
                    onCancel = {
                        if (nodeOpeningTab == tab.id) cancelOpeningFn()
                        cancelPending(tab.id)
                        highlightPath = null
                        highlightDirectory = null
                        pendingScrollPath = null
                    },
                )
            }
            }
        }
        PathEditScrim(
            visible = titleEditing,
            topBarHeightPx = topBarBounds?.height ?: 0f,
        )
        }
    }

    DeleteEntriesDialog(
        entries = showDeleteConfirm,
        context = context,
        scope = scope,
        currentTab = ::currentTab,
        folderOf = ::folderOf,
        nodeOf = ::nodeOf,
        onDismiss = { showDeleteConfirm = null },
        onSelectionClear = ::clearSelection,
        onRefresh = ::refreshFileList,
    )

    RenameEntryDialog(
        entry = showRenameDialog,
        context = context,
        scope = scope,
        currentTab = ::currentTab,
        folderOf = ::folderOf,
        onDismiss = { showRenameDialog = null },
        onRefresh = ::refreshFileList,
    )

    BatchRenameEntriesDialog(
        visible = showBatchRenameDialog,
        entries = if (showBatchRenameDialog) selectedEntries() else emptyList(),
        context = context,
        scope = scope,
        currentTab = ::currentTab,
        folderOf = ::folderOf,
        onDismiss = { showBatchRenameDialog = false },
        onSelectionClear = ::clearSelection,
        onRefresh = ::refreshFileList,
    )

    EntryInfoOverlay(
        entry = showEntryInfo,
        onDismiss = { showEntryInfo = null },
    )

    val operationClipboard = ClipboardStore.state
    OperationProgressOverlay(
        progress = operationProgress,
        hidden = operationHidden,
        sourceParent = operationClipboard.sourceParent,
        targetPath = currentTab().path,
        onHide = {
            operationHidden = true
            OperationNotification.show(
                context,
                operationProgress!!.isCopy,
                operationClipboard.sourceParent.ifBlank { operationProgress!!.currentName },
            )
        },
        onCancel = {
            OperationNotification.cancel(context)
            operationJob?.cancel()
            operationJob = null
            operationProgress = null
            refreshFileList()
        },
    )

    ContainerErrorOverlay(
        reason = containerError,
        onDismiss = { containerError = null },
    )

    SessionManagerOverlay(
        visible = showSessionManager,
        tabs = tabs.toList(),
        selectedIndex = selectedTabIndex,
        onSelect = {
            selectTab(it)
            showSessionManager = false
        },
        onClose = { closeTab(it) },
        onMove = { from, to -> moveTab(from, to) },
        onDismiss = { showSessionManager = false },
    )

    PageSheetOverlay(
        visible = showPages,
        dragPx = pagesDragPx,
        rows = pageRows(),
        bottomInset = with(LocalDensity.current) { bottomBarHeightPx.toDp() },
        onSelect = { row ->
            jumpToPage(row.page.id)
            showPages = false
        },
        onDismiss = { showPages = false },
    )

    TerminalSheetOverlay(
        visible = showTerminalSheet,
        terminalFactory = rendererOf(currentTab().id)?.terminalFactory,
        onDismiss = { showTerminalSheet = false },
        onShow = { showTerminalSheet = true },
        onDropAccepted = { terminalDropAccepted = true },
    )

    val promptModalActive = leavePrompt?.let { pageOf(it.ownerPageId)?.renderer?.modalActive == true } == true
    val prompt = leavePrompt
    LeavePromptOverlay(
        prompt = prompt,
        saving = leaveSaving,
        modalActive = promptModalActive,
        onDismiss = {
            if (!leaveSaving) {
                leavePrompt = null
                prompt?.onCancel()
            }
        },
        onCancel = {
            leavePrompt = null
            prompt?.onCancel()
        },
        onLeave = {
            leavePrompt = null
            prompt?.onLeave()
        },
        onSave = {
            val active = prompt
            val save = active?.onSave
            if (active != null && save != null) {
                scope.launch {
                    leaveSaving = true
                    val result = try {
                        save()
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (failure: Exception) {
                        Result.failure(failure)
                    }
                    leaveSaving = false
                    if (result.isSuccess) {
                        if (leavePrompt === active) leavePrompt = null
                        active.onLeave()
                    } else {
                        if (leavePrompt === active) leavePrompt = null
                        active.onCancel()
                    }
                }
            }
        },
    )
}

