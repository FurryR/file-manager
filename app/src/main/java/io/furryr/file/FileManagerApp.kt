package io.furryr.file
import io.furryr.file.model.*
import io.furryr.file.open.IncomingOpen
import io.furryr.file.open.OpenRequest
import io.furryr.file.home.HomeEntry
import io.furryr.file.home.HomeEntryKind
import io.furryr.file.home.HomeStore
import io.furryr.file.ui.components.*
import io.furryr.file.ui.screens.*
import io.furryr.file.ui.util.*
import io.furryr.file.shell.*
import io.furryr.file.ui.shell.*
import io.furryr.file.ui.state.rememberLocationCollections
import io.furryr.file.core.ContentTypes
import io.furryr.file.core.MimeType
import io.furryr.file.core.Path
import io.furryr.file.daemon.DaemonManager
import io.furryr.file.daemon.DaemonStatus
import io.furryr.file.container.ContainerManager
import io.furryr.file.container.ContainerBackend
import io.furryr.file.container.ContainerUris
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
import io.furryr.file.core.model.ProviderStat
import io.furryr.file.render.ErrorRenderer
import io.furryr.file.render.FolderRendererState
import io.furryr.file.render.HomeRenderer
import io.furryr.file.render.SelectCapabilities
import io.furryr.file.render.NodeAddress
import io.furryr.file.render.OpenMode
import io.furryr.file.render.RendererContext
import io.furryr.file.render.RendererMenuItem
import io.furryr.file.render.RendererMenuItemRow
import io.furryr.file.render.RendererPath
import io.furryr.file.render.RendererBase
import io.furryr.file.render.RendererContent
import io.furryr.file.render.RendererSession
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * How long an optimistic page waits for the server's type before the browser
 * keeps the address. A hanging HEAD must not pin the loading cover forever.
 */
private const val PAGE_CONFIRM_TIMEOUT_MS = 5_000L

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FileManagerApp(
    world: AppWorld,
    darkTheme: Boolean = true,
    onToggleTheme: () -> Unit = {},
    terminalOpenRequest: Int = 0,
    containerErrorRequest: String? = null,
    openRequest: OpenRequest? = null,
) {
    val context = LocalContext.current

    /** The Home page's title/path bar before its renderer is first built. */
    fun homePage(): Page = Page(
        path = HomePath,
        kind = PageKind.LOCATION,
        location = true,
        title = context.getString(R.string.home_title),
        pathInfo = RendererPath(
            title = context.getString(R.string.home_title),
            editText = "",
        ),
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
    lateinit var openVFileFn: (VFile, String, String, MimeType?, Boolean, Boolean, Boolean) -> Unit
    lateinit var cancelOpeningFn: () -> Unit
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
    // A renderer that opened a popup answered within the pending window, so the
    // 300 ms page cover must not appear over it.
    val popupPages = remember { mutableStateMapOf<String, Boolean>() }
    // Page load progress reported through RendererContext.setProgress.
    val progressPages = remember { mutableStateMapOf<String, Int>() }

    /** LIFO modal stack: only the top entry is composed. */
    val modalStack = remember { mutableStateListOf<ShellModal>() }

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
        popupPages.remove(pageId)
        progressPages.remove(pageId)
        val page = pageOf(pageId) ?: return
        val renderer = page.renderer ?: return
        page.renderer = null
        releaseSession(renderer)
    }

    fun blurPage(pageId: String?) {
        pageId ?: return
        pageOf(pageId)?.renderer?.blur()
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
    fun isPageVisible(pageId: String): Boolean {
        val tab = currentTab()
        return tab.currentPage.id == pageId ||
            pendingNavigations[tab.id]?.currentPage?.id == pageId
    }

    fun cancelModals(tabId: String, pageId: String? = null) {
        modalStack
            .filter { it.tabId == tabId && (pageId == null || it.pageId == pageId) }
            .forEach { entry ->
                if (modalStack.remove(entry)) entry.cancel(null)
            }
    }

    fun cancelPending(tabId: String): FileTab? {
        val pending = pendingNavigations.remove(tabId) ?: return null
        cancelModals(tabId, pending.currentPage.id)
        releasePendingPages(pending)
        return pending
    }

    /** Mint the directory/Home renderer for a location page. */
    fun newLocationSession(tab: FileTab, page: Page): RendererBase<*>? {
        if (isHomePath(page.path)) {
            val renderer = HomeRenderer(homeTitle = context.getString(R.string.home_title))
            page.renderer = renderer
            return renderer
        }
        // A committed location lost its renderer: rebuild it from the node the
        // address resolves to. The page shows its placeholder until then.
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                world.vfs.node(Path.parse(page.path))
            }
            val node = result.getOrNull()
            if (node !is VFolder) {
                page.renderer = ErrorRenderer(
                    page.path,
                    result.exceptionOrNull()
                        ?: IllegalStateException("not a folder: ${page.path}"),
                    locationPage = true,
                )
                return@launch
            }
            val renderer = try {
                world.opener.route(node)
            } catch (error: Throwable) {
                page.renderer = ErrorRenderer(page.path, error, locationPage = true)
                return@launch
            }
            renderer.capabilities.select?.setSelection(tab.selectedPaths, tab.lastSelectedIndex)
            page.renderer = renderer
        }
        return null
    }

    /**
     * Freeze [entries] into copy/move mode. The source rows stop being shown:
     * from here the list only serves to pick a destination.
     */
    fun startClipboard(entries: List<ClipboardEntry>, action: ClipboardAction) {
        if (entries.isEmpty()) return
        ClipboardStore.begin(entries, action)
        allPages().forEach { page ->
            page.renderer?.capabilities?.select?.clearDisplayedSelection()
        }
    }

    /** The live tab view, whichever renderer owns it. */
    fun rendererOf(id: String): RendererBase<*>? {
        val tab = activeTabState(id) ?: return null
        val page = tab.currentPage
        page.renderer?.let { return it }
        // A page or an edited file is rebuilt from its page; only a directory
        // or Home has a renderer the shell can mint on demand.
        if (!page.location) return null
        // Background tabs keep only renderers they already built.
        if (id != currentTab().id) return null
        return newLocationSession(tab, page)
    }

    fun pendingForCurrent(): FileTab? = pendingNavigations[currentTab().id]

    fun recordHome(kind: HomeEntryKind, location: String, title: String, detail: String = "") {
        HomeStore.record(kind, location, title, detail)
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
        cancelModals(tabId, oldPage.id)
        oldPage.renderer?.blur()
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

    /** Forget every modal owned by a leaving tab. */
    fun clearModals(id: String) {
        cancelModals(tabId = id)
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
        if (page.location) return
        val renderer = page.renderer
        if (renderer?.isLocationRenderer == true) return
        clearModals(id)
        page.close()
        val index = tab.pages.indexOfFirst { it.id == page.id }
        if (index < 0) return
        val stops = tab.pages.toMutableList().apply { removeAt(index) }
        if (stops.isEmpty()) {
            replaceTab(
                tab.copy(pages = emptyList()).appended(
                    if (isHomePath(tab.path)) homePage()
                    else Page(path = tab.path, location = true),
                ),
            )
            return
        }
        val nextIndex = (index - 1).coerceIn(0, stops.lastIndex)
        replaceTab(tab.copy(pages = stops, pageIndex = nextIndex).movedTo(nextIndex))
    }

    /**
     * Run [action] after the tab's renderer lets go. The session owns whether a
     * leave prompt is needed; the tab only supplies the dialog host and the
     * post-close cleanup.
     */
    fun guardRenderer(id: String, action: () -> Unit): Boolean {
        val page = activeTabState(id)?.currentPage ?: return false
        val renderer = page.renderer ?: return false
        if (!renderer.needsUnloadGuard) return false
        scope.launch {
            val host = contextFor(id, null).also { it.bindPage(page.id) }
            if (renderer.requestUnload(host)) {
                page.close()
                action()
            }
        }
        return true
    }

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
        host: RendererContext,
        reopen: suspend (MimeType?) -> Unit,
    ) {
        val expected = session.address ?: return
        val guessedStop = pendingOf(tabId)?.currentPage?.id
            ?: tabs.firstOrNull { it.id == tabId }?.currentPage?.id
            ?: return
        if (!isActiveSession(tabId, session)) return
        // Keep the page's body behind the loading cover until the server has
        // said what it is, so a hand-back never flashes a frame of it.
        session.beginConfirmation()
        scope.launch {
            // The stat runs on its own job: a blocking HEAD cannot be
            // interrupted by a timeout, so the cover waits on a deferred
            // instead of on the call itself.
            val verdict = CompletableDeferred<MimeType?>()
            val probe = scope.launch(Dispatchers.IO) {
                val mime = try {
                    live.stat().getOrNull()?.mime
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    null
                }
                verdict.complete(mime)
            }
            var handedBack = false
            try {
                // A page that already moved on (a redirect, a link) no longer
                // waits for this verdict; the timeout keeps a hanging HEAD from
                // pinning the cover either.
                val declared = withTimeoutOrNull(PAGE_CONFIRM_TIMEOUT_MS) { verdict.await() }
                // Something else may have taken the tab while the server
                // answered; only the page this probe belongs to may be handed
                // back.
                if (!isActiveSession(tabId, session) || session.address != expected) {
                    return@launch
                }
                if (WebPagePolicy.verdict(declared, live.name) != WebPagePolicy.Verdict.NOT_PAGE) {
                    // A page, or an address whose type nobody named: the browser
                    // keeps it, exactly where the eager path would have put it.
                    return@launch
                }
                // Not a page: reopen the same stop with the real type. If the
                // route hands it to an external viewer, no renderer replaced the
                // guess and the stop is dropped here.
                handedBack = true
                reopen(declared)
                if (pageOf(guessedStop)?.renderer === session) closeRenderer(tabId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                // The hand-back could not replace the guess; the browser keeps
                // the address and the failure is reported like any other open.
                handedBack = false
                host.reportError(error)
            } finally {
                if (!verdict.isCompleted) probe.cancel()
                // The cover was only waiting on this verdict. A page that moved
                // away, a probe that timed out, or a hand-back that failed must
                // never leave the spinner up for good.
                if (!handedBack && isActiveSession(tabId, session)) {
                    session.settleConfirmation()
                }
            }
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
        mimeHint: MimeType? = null,
        host: RendererContext,
        /** A document the user opened: it becomes a home row. */
        recordHome: Boolean = true,
        /** A new location: the outgoing one becomes a Back stop. */
        pushPage: Boolean = true,
    ) {
        val live = file.liveAddress != null
        val optimistic = mimeHint == null && live &&
            WebPagePolicy.likelyPage(address)
        // A live address that already looks like a page keeps its facts
        // unknown: the route table hands it to the browser with a page hint, so
        // the server's first byte is not delayed by a HEAD. Every other open
        // asks the file's stat for its MIME, then falls back to the extension.
        // No body is read here.
        val routedMime = mimeHint ?: if (optimistic) "text/html" else null
        val renderer = withContext(Dispatchers.IO) {
            host.opener.route(file, routedMime)
        }
        // Only a plain provider path becomes a home row; a view address is
        // rebuilt from its own text when it is reopened. A live resource is a
        // page row, every other node a file row — the node itself decides.
        if (recordHome && !file.path.isViewed) {
            val kind = if (file.liveAddress != null) HomeEntryKind.WEB else HomeEntryKind.FILE
            recordHome(kind, address, name, address)
        }
        if (pushPage) host.open(renderer) else host.replace(renderer)
        if (optimistic) {
            // The renderer that took the tab is the one whose address is a
            // page; the optimistic open made it the current stop already.
            val page = pendingOf(tabId)?.currentPage?.renderer ?: activeSession(tabId)
            if (page?.webAddress != null) confirmPage(tabId, file, page, host) { facts ->
                // The reopen carries the MIME, so the table cannot guess a
                // second time. It pushes no page and records no home row:
                // the optimistic open already made this address the current
                // stop and its own Home row. A fresh host belongs to the
                // replacement session.
                routeVFile(
                    tabId = tabId,
                    file = file,
                    address = address,
                    name = name,
                    mimeHint = facts,
                    host = contextFor(tabId, null),
                    recordHome = false,
                    pushPage = false,
                )
            }
        }
    }


    /** The one path→node step: address text enters the node graph here. */
    suspend fun resolveNode(path: Path): Result<VNode> =
        withContext(Dispatchers.IO) { world.vfs.node(path) }

    /** A failed address is still a page: install the retryable error view. */
    fun openErrorPage(address: String, failure: Throwable, tabId: String = currentTab().id) {
        if (tabs.none { it.id == tabId }) return
        contextFor(tabId, null).open(ErrorRenderer(address, failure))
    }

    /** Install [folder] as the tab's location; the renderer arrives asynchronously. */
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
        val page = Page(path = folder.path.toString(), kind = PageKind.LOCATION, location = true)
        if (tabId == currentTab().id) {
            highlightPath = null
            highlightDirectory = null
            pendingScrollPath = null
        }
        // A rejected page guess is one page, not a page plus an archive above
        // it: replace it in place and let the directory renderer take over.
        val next = if (replaceCurrent && !tab.currentPage.location) {
            tab.replacedCurrent(page)
        } else {
            tab.appended(page)
        }
        pendingNavigations[next.id] = next
        scope.launch {
            val renderer = try {
                world.opener.route(folder)
            } catch (error: Throwable) {
                openErrorPage(folder.path.toString(), error, tabId)
                return@launch
            }
            renderer.capabilities.select?.setSelection(tab.selectedPaths, tab.lastSelectedIndex)
            page.renderer = renderer
        }
    }

    /**
     * The stop an already-built renderer becomes. Its address and role come
     * from the renderer; the shell only mints the page identity.
     */
    fun rendererPage(renderer: RendererBase<*>): Page {
        val page = Page(
            path = renderer.pagePath,
            kind = renderer.pageKind,
            location = renderer.isLocationRenderer,
            title = renderer.title,
        )
        page.renderer = renderer
        return page
    }

    /**
     * Install [renderer] as a tab of its own and activate it.
     *
     * The new tab starts at the calling tab's location so Back and Up keep a
     * folder to return to; the opened page stays pending until its renderer
     * commits, exactly like a same-tab open. A renderer that only contributes
     * a dialog (the chooser) therefore leaves the tab on that location instead
     * of blanking it.
     */
    fun openInNewTab(from: FileTab, renderer: RendererBase<*>) {
        val base = if (isHomePath(from.path)) homePage() else Page(
            path = from.path,
            kind = PageKind.LOCATION,
            location = true,
            // Until the base location's renderer reports, the strip keeps the
            // title the calling tab last gave that location.
            title = from.pages
                .take((from.pageIndex + 1).coerceAtLeast(0))
                .lastOrNull { it.location }
                ?.title,
        )
        val fresh = FileTab(path = from.path, pages = listOf(base))
        tabs.add(fresh)
        selectTab(tabs.lastIndex)
        pendingNavigations[fresh.id] = fresh.appended(rendererPage(renderer))
    }

    /**
     * Navigate to [path]. Home is the only renderer the shell builds itself;
     * every other address resolves to a node and goes through the opener.
     */
    fun navigateTo(
        path: String,
        tabId: String = currentTab().id,
        record: Boolean = true,
        folder: VFolder? = null,
        /** Append even when [path] is already the tab's location. */
        force: Boolean = false,
    ) {
        if (guardRenderer(tabId) { navigateTo(path, tabId, record, folder, force) }) return
        val tab = tabs.firstOrNull { it.id == tabId } ?: return
        if (pendingNavigations[tab.id] != null) return
        if (!force && path == tab.path && tab.currentPage.location) return
        if (isHomePath(path)) {
            val page = homePage()
            page.renderer = HomeRenderer(homeTitle = context.getString(R.string.home_title))
            val next = tab.appended(page)
            blurPage(tab.currentPage.id)
            replaceTab(next)
            return
        }
        if (folder != null) {
            navigateToFolder(folder, tabId)
            return
        }
        scope.launch {
            val result = resolveNode(Path.parse(path))
            when (val node = result.getOrNull()) {
                is VFolder -> navigateToFolder(node, tabId)
                is VFile -> routeVFile(
                    tabId = tabId,
                    file = node,
                    address = path,
                    name = node.name,
                    host = contextFor(tabId, null),
                )
                null -> openErrorPage(
                    path,
                    result.exceptionOrNull() ?: IllegalStateException("Cannot open $path"),
                    tabId,
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
            override val vfs = world.vfs
            override val worldId: String = world.id
            override val opener = world.opener

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
                activeTabId.value = tab.id
                val index = tab.pages.indexOfFirst { it.id == pageId }
                if (index < 0 || tab.pageIndex == index) return
                blurPage(tab.currentPage.id)
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

            override fun open(
                renderer: RendererBase<*>,
                mode: OpenMode,
                pushPage: Boolean,
            ) {
                val tab = tabs.firstOrNull { it.id == id } ?: run {
                    releaseSession(renderer)
                    return
                }
                if (mode == OpenMode.NewTab) {
                    // The calling tab keeps its own stops untouched: only the
                    // new tab's page is pending, and its own modals stay.
                    openInNewTab(tab, renderer)
                    return
                }
                val page = rendererPage(renderer)
                clearModals(tab.id)
                cancelPending(tab.id)
                // A location can only be replaced by another location. An
                // overlay still gets a page above it even when the caller
                // asked for no push.
                val appendPage = pushPage ||
                    (tab.currentPage.location && !renderer.isLocationRenderer)
                pendingNavigations[tab.id] = if (appendPage) {
                    tab.appended(page)
                } else {
                    tab.replacedCurrent(page)
                }
            }

            override fun navigate(node: io.furryr.file.core.VNode, rendererId: String?) {
                if (node is VFolder) {
                    navigateToFolder(node, id)
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
                    current.path = address
                    current.kind = PageKind.PAGE
                    current.title = null
                    pendingNavigations[id] = pending
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
                    current.path = address
                    current.kind = PageKind.PAGE
                    current.title = null
                    return
                }
                // The page being left keeps the title it last reported.
                val titled = tab
                val known = titled.pages.indexOfLast {
                    it.path == address && it.id != current.id
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

            override fun openFile(file: VFile, stat: ProviderStat?) {
                // The node already carries its address, view levels included.
                // A link is a navigation inside the tab, not a document the
                // user opened, so it does not become a home entry.
                openVFileFn(
                    file,
                    file.path.toString(),
                    file.name,
                    stat?.mime,
                    false,
                    true,
                    false,
                )
            }

            override fun copyFile(file: VFile, stat: ProviderStat?) {
                // Put the node on the clipboard; the user picks the destination
                // by switching tabs and pasting there.
                startClipboard(
                    listOf(
                        ClipboardEntry(
                            node = file,
                            name = file.name,
                        ),
                    ),
                    ClipboardAction.COPY,
                )
            }

            override fun shareFile(file: VFile, stat: ProviderStat?) {
                io.furryr.file.ui.util.shareFile(context, file, stat)
            }

            override fun openEntry(entry: FileEntry, node: VNode?) {
                if (entry.isDirectory) {
                    when (node) {
                        is VFolder -> navigateToFolder(node, id)
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

            override fun requestShare(entries: List<ClipboardEntry>) {
                scope.launch { shareEntries(context, entries.map { it.node }) }
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

            override fun cancelOpening() = cancelOpeningFn()

            override fun folderView(): FolderRendererState? {
                val pending = boundPending()
                if (pending != null) {
                    val page = pending.currentPage
                    return FolderRendererState(
                        path = page.path,
                        committedPath = page.path,
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
                    scrollToPath = pendingScrollPath,
                    highlightPath = highlightPath,
                    highlightDirectory = highlightDirectory,
                    opening = nodeOpeningTab == id,
                )
            }

            override fun reportError(error: Throwable) {
                Toast.makeText(context, context.errorText(error), Toast.LENGTH_SHORT).show()
            }

            override fun setProgress(progress: Int?) {
                val pageId = boundPageId ?: return
                if (progress == null) progressPages.remove(pageId) else progressPages[pageId] = progress
            }

            override fun dismissPending() {
                val pending = boundPending() ?: return
                if (pendingNavigations[id] === pending) cancelPending(id)
            }

            @Suppress("UNCHECKED_CAST")
            override suspend fun <T> popup(
                content: @Composable ((T) -> Unit) -> Unit,
            ): T? {
                val pageId = boundPageId ?: return null
                popupPages[pageId] = true
                val deferred = CompletableDeferred<T?>()
                val entry = ShellModal(
                    tabId = id,
                    pageId = pageId,
                    content = { content { value -> deferred.complete(value as T?) } },
                    cancel = { deferred.complete(null) },
                )
                modalStack.add(entry)
                if (!isPageVisible(pageId)) focus()
                if (!isPageVisible(pageId)) {
                    modalStack.remove(entry)
                    deferred.complete(null)
                    return null
                }
                return try {
                    deferred.await()
                } finally {
                    modalStack.remove(entry)
                    if (!deferred.isCompleted) deferred.complete(null)
                }
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
     * The one fire-and-forget file open for the current tab. Every caller that
     * already holds a node — a directory row, a link hit, a typed address, a
     * home row — lands here; [routeVFile] owns the describe → route → apply
     * pipeline. [tracked] wraps it in the shell's opening indicator.
     */
    fun openVFile(
        file: VFile,
        address: String,
        name: String,
        mimeHint: MimeType? = null,
        recordHome: Boolean = true,
        pushPage: Boolean = true,
        tracked: Boolean = false,
    ) {
        val tabId = currentTab().id
        val host = rendererContextFor(tabId)
        if (tracked) {
            trackNodeOpen(tabId) {
                routeVFile(tabId, file, address, name, mimeHint, host, recordHome, pushPage)
            }
        } else {
            scope.launch {
                routeVFile(tabId, file, address, name, mimeHint, host, recordHome, pushPage)
            }
        }
    }
    openVFileFn = ::openVFile

    /** The directory node for [path], reusing [hint] when the tab already has one. */
    suspend fun folderFor(path: String, hint: VFolder? = null): VFolder? =
        hint ?: withContext(Dispatchers.IO) { world.vfs.folder(path).getOrNull() }

    /**
     * Go to [address] in [tabId]: the provider decides what it is.
     *
     * A folder becomes the tab's location (the loader lists it), a file goes
     * through the same opener/renderer table a directory row uses. This is the
     * single entry for a typed address, a home row and a page — nothing
     * here reads a scheme, and no caller needs a second "open a URL" path.
     */
    /** The resolve-and-route half of [openAddress], usable under an opening indicator. */
    suspend fun openAddressNow(address: String, tabId: String, recordHome: Boolean = true) {
        if (tabs.none { it.id == tabId }) return
        val parsed = NodeAddress.parse(address)
        val result = resolveNode(parsed)
        when (val node = result.getOrNull()) {
            // A view level already resolved to a node: keep it, so an
            // archive root is not re-opened from its path.
            is VFolder -> navigateToFolder(node, tabId)
            is VFile -> routeVFile(
                tabId,
                node,
                address,
                node.name,
                host = rendererContextFor(tabId),
                recordHome = recordHome,
            )
            null -> openErrorPage(
                address,
                result.exceptionOrNull() ?: IllegalStateException("Cannot open $address"),
                tabId,
            )
        }
    }

    fun openAddress(
        address: String,
        tabId: String = currentTab().id,
        recordHome: Boolean = true,
    ) {
        if (guardRenderer(tabId) { openAddress(address, tabId, recordHome) }) return
        scope.launch { openAddressNow(address, tabId, recordHome) }
    }

    /**
     * Open what another app handed over: a tapped file, a picked document or a
     * link. The first address lands in the current tab, every additional one
     * gets its own tab; an address the system delivered is not a home row, so
     * it never outlives the grant that came with the intent.
     */
    LaunchedEffect(openRequest?.id) {
        val request = openRequest ?: return@LaunchedEffect
        request.addresses.forEachIndexed { index, address ->
            if (index > 0) {
                tabs.add(newHomeTab())
                selectTab(tabs.lastIndex)
            }
            val document = withContext(Dispatchers.IO) { IncomingOpen.document(context, address) }
            if (document != null) {
                openVFileFn(
                    document, address, document.name, null,
                    // One delivered document may hold the whole screen; does not
                    // record a home row. A single address may cancel the last one.
                    false, true, index == 0 && request.addresses.size == 1,
                )
            } else {
                openAddress(address, recordHome = false)
            }
        }
    }

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
        if (!tab.currentPage.location) {
            closeRenderer(tab.id)
            return
        }
        replaceCurrent(tab.copy())
    }

    /** Reveal [address] selected inside [parent], the jump-dialog behaviour. */
    fun selectInParent(parent: VFolder, address: String) {
        val tabId = currentTab().id
        if (parent.path.toString() == currentTab().path) {
            closeRenderer(tabId)
            val listing = rendererOf(tabId)?.capabilities?.select
            val index = listing?.entries?.indexOfFirst { it.path == address } ?: -1
            listing?.setSelection(setOf(address), index)
            pendingScrollPath = address
            return
        }
        openAddress(parent.path.toString())
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
            val result = resolveNode(address)
            when (val node = result.getOrNull()) {
                is VFolder -> navigateToFolder(node, tabId)
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
                null -> when {
                    // A missing absolute path or scheme URI is still an
                    // address: show its error state instead of searching.
                    NodeAddress.isAddress(input) -> openErrorPage(
                        input,
                        result.exceptionOrNull() ?: IllegalStateException("Cannot open $input"),
                        tabId,
                    )
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
                val result = resolveNode(NodeAddress.parse(stop.path))
                // The tab may have moved on while the address was resolving.
                val current = tabs.firstOrNull { it.id == tabId }
                if (current?.currentPage?.id != stop.id ||
                    pendingNavigations[tabId] != null ||
                    pageOf(stop.id)?.renderer != null
                ) {
                    return@launch
                }
                val node = result.getOrNull()
                if (node == null) {
                    pageOf(stop.id)?.renderer = ErrorRenderer(
                        stop.path,
                        result.exceptionOrNull()
                            ?: IllegalStateException("Cannot open ${stop.path}"),
                        locationPage = stop.location,
                    )
                    updateTab(tabId) { it }
                    return@launch
                }
                when (node) {
                    is VFile -> {
                        routeVFile(
                            tabId = tabId,
                            file = node,
                            address = stop.path,
                            name = node.name,
                            mimeHint = null,
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
                            !tab.currentPage.location &&
                            pageOf(stop.id)?.renderer == null
                        ) {
                            closeRenderer(tabId)
                        }
                    }
                    is VFolder -> updateTab(tabId) { tab ->
                        // A stop recorded as a file/page can turn out to be a
                        // folder after the fact; the directory renderer takes it.
                        if (tab.currentPage.id == stop.id) {
                            stop.kind = PageKind.LOCATION
                            stop.location = true
                            stop.title = null
                            if (tab.path != stop.path) tab.copy(path = stop.path) else tab
                        } else {
                            tab
                        }
                    }
                    else -> Unit
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
        if (session?.webAddress != null && session.retrace(target.path)) {
            current.renderer = null
            target.close()
            target.renderer = session
            replaceTab(tab.movedTo(index))
            return
        }
        clearModals(tab.id)
        blurPage(current.id)
        val moved = tab.movedTo(index)
        replaceTab(moved)
        if (!target.location) {
            // A stale renderer for the target would otherwise be replaced
            // without its release hook.
            target.close()
            restorePageNode(tab.id, target)
        }
    }

    fun goBack() {
        val tab = currentTab()
        if (pendingNavigations[tab.id] != null) return
        if (!tab.canGoBack) {
            // A trimmed list can leave an overlay without a location behind it;
            // dismissing the overlay is still the right undo.
            if (!tab.currentPage.location &&
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
        val up = renderer?.capabilities?.up
        if (up != null) {
            up()
            return
        }
        val activePath = pendingOf(tab.id)?.path ?: tab.path
        if (isHomePath(activePath)) return
        val parent = Path.parse(activePath).parent()?.toString() ?: return
        openAddress(parent)
    }

    /** Single refresh entry point for the directory listing. */
    fun refreshFileList(
        animate: Boolean = true,
        highlightPath: String? = null,
        highlightDirectory: String? = null,
    ) {
        val listing = rendererOf(currentTab().id)?.capabilities?.select ?: return
        listing.refreshListing(animate, highlightPath, highlightDirectory)
    }

    /** Up exists when the active renderer supplies its own parent action. */
    fun currentCanGoParent(): Boolean {
        val tab = currentTab()
        val renderer = rendererOf(tab.id)
        if (renderer != null) return renderer.capabilities.up != null
        return Path.parse(tab.path).parent() != null
    }

    val handleHereRequest by rememberUpdatedState(newValue = { request: HereNavigation.Request ->
        val tab = currentTab()
        clearModals(tab.id)
        // A here request supersedes an in-flight navigation in the current tab.
        cancelPending(tab.id)
        showTerminalSheet = false
        highlightPath = request.selectedPath
        highlightDirectory = request.directory
        pendingScrollPath = request.selectedPath
        // Re-entering the same plain directory is not a step; an overlay over
        // that directory still gets a new location stop behind it.
        if (tab.currentPage.location && tab.path == request.directory) {
            replaceCurrent(
                tab.copy(
                    selectedPaths = emptySet(),
                    lastSelectedIndex = -1,
                ),
            )
            refreshFileList(
                highlightPath = highlightPath,
                highlightDirectory = highlightDirectory,
            )
        } else {
            scope.launch {
                val result = resolveNode(Path.parse(request.directory))
                when (val node = result.getOrNull()) {
                    is VFolder -> navigateToFolder(node, tab.id)
                    null -> openErrorPage(
                        request.directory,
                        result.exceptionOrNull()
                            ?: IllegalStateException("Cannot open ${request.directory}"),
                        tab.id,
                    )
                    else -> Unit
                }
            }
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
    // A renderer that already opened a popup must never trigger the page cover.
    val activePendingOverlay = activePending?.currentPage?.id?.let { popupPages[it] } == true
    var pendingSpinner by remember { mutableStateOf(false) }
    LaunchedEffect(activePending?.currentPage?.id, activePendingOverlay) {
        pendingSpinner = false
        if (activePending != null && !activePendingOverlay) {
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
        if (composedPage.location) return@LaunchedEffect
        if (pendingNavigations[tab.id] != null) return@LaunchedEffect
        if (composedPage.renderer != null) return@LaunchedEffect
        restorePageNode(tab.id, composedPage)
    }
    // Block reads of remote HTTP(S) handles (archive index, proxy descriptor)
    // publish their progress here; the top strip below mirrors page loading.
    val remoteTransfers by WebTransfers.progress.collectAsState()
    // HomeStore owns its revision; reading it here keeps suggestions in step
    // with visits, title refreshes and renderer-made bookmark edits.
    val homeRevision = HomeStore.revision()
    val homeRecent = remember(homeRevision) { HomeStore.recent() }
    val homeBookmarks = remember(homeRevision) { HomeStore.bookmarks() }
    val pathSuggestionSource = remember(homeBookmarks, homeRecent, world) {
        object : PathSuggestionSource {
            override fun local(query: String): List<PathSuggestion> =
                PathSuggestions.local(query, homeBookmarks, homeRecent)

            override suspend fun remote(query: String): List<PathSuggestion> =
                PathSuggestions.remote(context, query)

            override suspend fun listing(query: String): List<PathSuggestion> =
                PathSuggestions.listing(world.files, query)
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
        clearModals(closing.id)
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
            scope.launch {
                val host = contextFor(tab.id, null).also { it.bindPage(page.id) }
                if (renderer.requestUnload(host)) step(at + 1)
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
            clearModals(removed.id)
            releaseStops(removed, pendingNavigations.remove(removed.id))
        }
        if (tabs.isEmpty()) {
            tabs.add(newHomeTab())
            selectTab(0)
        } else {
            selectTab(selectedTabIndex.coerceAtMost(tabs.lastIndex))
        }
    }

    val clipboardController = ClipboardController(
        context = context,
        nodeOps = world.nodeOps,
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

    fun currentListing(): SelectCapabilities? = clipboardController.listing()

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
                    val listing = previous.currentPage.renderer?.capabilities?.select
                    if (listing != null) {
                        previous.currentPage.renderer?.blur()
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
        } else if (currentTab().canGoBack || !currentTab().currentPage.location) {
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
        // stays off for both. A path edit dims the content from below the top
        // bar, so its drawer must not open over the scrim either. An already
        // open drawer keeps the gesture so it can still be swiped shut.
        gesturesEnabled = !titleEditing &&
            (activeRenderer?.consumesEdgeGestures != true || drawerState.isOpen),
        drawerContent = {
            FileDrawerHost(
                vfs = world.vfs,
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
                    openAddress(address)
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
                    loadProgress = progressPages[currentTab().currentPage.id],
                    suggestionSource = pathSuggestionSource,
                    suggestionAnchor = topBarBounds,
                    onBounds = { topBarBounds = it },
                    onTitleBounds = { titleBounds = it },
                    onNavigate = ::jumpTo,
                    onSuggestion = ::chooseTitleSuggestion,
                    onEditingChange = { titleEditing = it },
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    drawerEnabled = !titleEditing,
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
                val barTab = currentTab()
                val barRenderer = rendererOf(barTab.id)
                val barState = activeTabState(barTab.id) ?: barTab
                ShellBottomBar(
                    tab = barTab,
                    renderer = barRenderer,
                    canGoBack = barState.canGoBack || !barState.currentPage.location,
                    canGoForward = barState.canGoForward,
                    canGoParent = currentCanGoParent(),
                    canOpenTerminal = barRenderer?.capabilities?.terminal != null ||
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
                        val entries = ClipboardStore.state.entries
                        scope.launch { shareEntries(context, entries.map { it.node }) }
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
        terminalFactory = rendererOf(currentTab().id)?.capabilities?.terminal,
        onDismiss = { showTerminalSheet = false },
        onShow = { showTerminalSheet = true },
        onDropAccepted = { terminalDropAccepted = true },
    )

    // LIFO modal stack. Session manager, renderer popups and unload prompts
    // all compose here; only the top entry is visible and interactive.
    ModalStackHost(modalStack)
}

