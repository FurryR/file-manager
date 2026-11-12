package io.furryr.file
import io.furryr.file.model.*
import io.furryr.file.home.HomeEntry
import io.furryr.file.home.HomeEntryKind
import io.furryr.file.home.HomeStore
import io.furryr.file.ui.components.*
import io.furryr.file.ui.screens.*
import io.furryr.file.ui.util.*
import io.furryr.file.ui.state.rememberLocationCollections
import io.furryr.file.core.ContentInfo
import io.furryr.file.core.ContentTypes
import io.furryr.file.core.Path
import io.furryr.file.daemon.DaemonManager
import io.furryr.file.daemon.DaemonStatus
import io.furryr.file.container.ContainerManager
import io.furryr.file.container.ContainerBackend
import io.furryr.file.container.ContainerUris
import io.furryr.file.core.DirectoryCapabilities
import io.furryr.file.agent.SessionManager
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
import io.furryr.file.render.HomeRendererState
import io.furryr.file.render.ListingSession
import io.furryr.file.render.NodeAddress
import io.furryr.file.render.RenderCapabilities
import io.furryr.file.render.RenderHost
import io.furryr.file.render.RendererMenuItem
import io.furryr.file.render.RendererSession
import io.furryr.file.render.RendererSessions
import io.furryr.file.render.Retention
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
import android.view.WindowManager
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
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

/**
 * One leave confirmation for every renderer. The editor supplies [onSave];
 * a WebView beforeunload prompt only supplies [onLeave].
 */
private data class LeavePrompt(
    val ownerTabId: String,
    val message: String? = null,
    val canSave: Boolean = false,
    val onSave: (suspend () -> Result<Unit>)? = null,
    val onLeave: () -> Unit,
    val onCancel: () -> Unit = {},
)

/**
 * Tabs belong to the shell's process, not to one activity instance: changing
 * the app language recreates [MainActivity], and the open tabs must survive
 * that rebuild. Process death still starts from Home.
 */
private object TabSession {
    var tabs: SnapshotStateList<FileTab>? = null
    var selectedIndex: MutableIntState? = null
}

/**
 * Shell-owned host with a session back-reference.
 *
 * Callbacks are gated by the session object rather than a stop id: an adopted
 * WebView moves to a new stop without losing its host, and a warm session that
 * is no longer active cannot rename the tab it left.
 */
private interface ShellRenderHost : RenderHost {
    var boundSession: RendererSession?
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FileManagerApp(
    darkTheme: Boolean = true,
    onToggleTheme: () -> Unit = {},
    terminalOpenRequest: Int = 0,
    containerErrorRequest: String? = null,
) {
    val context = LocalContext.current
    val tabs = remember {
        TabSession.tabs ?: mutableStateListOf(FileTab(path = HomePath)).also { TabSession.tabs = it }
    }
    var selectedTabIndex by remember {
        TabSession.selectedIndex ?: mutableIntStateOf(0).also { TabSession.selectedIndex = it }
    }
    var containerError by remember(containerErrorRequest) { mutableStateOf(containerErrorRequest) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var menuChildren by remember { mutableStateOf<List<RendererMenuItem>?>(null) }
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
    var showHistory by remember { mutableStateOf(false) }
    // A `here` request that arrived while an editor held unsaved changes waits
    // for the exit dialog instead of silently discarding the buffer.
    var pendingHereRequest by remember { mutableStateOf<HereNavigation.Request?>(null) }
    // Pixels of history panel revealed by an in-flight drag from the toolbar.
    var historyDragPx by remember { mutableFloatStateOf(0f) }
    // How far the bar must be pulled before the panel stays open.
    val historyRevealThresholdPx = with(LocalDensity.current) { 96.dp.toPx() }
    // Measured so the history panel can dock above the toolbar instead of
    // sliding over it from the bottom of the window.
    var bottomBarHeightPx by remember { mutableIntStateOf(0) }
    // One live renderer session per history stop, keyed by HistoryStop.id.
    // Directory, Home, Web and editor sessions all live here; leaving a stop
    // deactivates it, and its retention decides whether it survives.
    val rendererSessions = remember { mutableMapOf<String, RendererSession>() }
    // Warm sessions in activation order, oldest first. The budget is coarse on
    // purpose: a directory is cheap and recreating it is always possible.
    val warmSessions = remember { ArrayDeque<String>() }
    val warmBudget = 16
    // Stops whose renderer is being rebuilt after a history move or an
    // activity recreate; prevents a second restore from the composition.
    val restoringStops = remember { mutableSetOf<String>() }
    // Assigned once renderHostFor() exists below; rendererOf() uses it to mint
    // sessions with the same host surface every other renderer gets.
    lateinit var hostFor: (String) -> ShellRenderHost
    // Assigned after their local functions are declared; the host methods run
    // only from user events, so they never need a forward declaration body.
    lateinit var openResolvedNodeFn: (VFile, FileEntry) -> Unit
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
    // The terminal sheet stacks above everything, so a history panel left open
    // underneath it would only be dead weight; collapse it when the terminal opens.
    LaunchedEffect(showTerminalSheet) {
        if (showTerminalSheet) showHistory = false
        // The terminal lives at the bottom and must stay above the IME. Every
        // other screen keeps adjustNothing, so its keyboard cannot move the
        // bottom toolbar.
        val window = (context as? Activity)?.window
        window?.setSoftInputMode(terminalSoftInputMode(showTerminalSheet))
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

    fun folderLabel(folder: VFolder): String {
        // A view folder carries its own address (provider path plus view
        // levels); no format-specific formatting belongs here.
        runCatching { NodeAddress.format(folder.path) }.getOrNull()?.let { return it }
        val ancestry = generateSequence(folder) { it.parent }.toList().asReversed()
        val providerIndex = ancestry.indexOfFirst { it is Folder }
        if (providerIndex < 0) return ancestry.joinToString("/") { it.name }
        // Keep the real provider path as the base; nested segments are appended
        // without inventing a new scheme. This keeps the address bar showing
        // /storage/emulated/0/a.zip/docs instead of "Local Storage / ...".
        val base = (ancestry[providerIndex] as Folder).path.toString().trimEnd('/')
        val nested = ancestry.drop(providerIndex + 1).joinToString("/") { it.name }
        return when {
            base.isEmpty() && nested.isEmpty() -> "/"
            base.isEmpty() -> "/$nested"
            nested.isEmpty() -> base
            else -> "$base/$nested"
        }
    }

    // Navigations in flight are per tab. A slow remote probe must stay with the
    // tab that started it: switching tabs neither drops the pending location
    // nor lets the result commit into whichever tab is selected later.
    val pendingNavigations = remember { mutableStateMapOf<String, FileTab>() }

    fun pendingOf(tabId: String): FileTab? = pendingNavigations[tabId]

    /** Release hook then close; sessions may run this only once. */
    fun releaseSession(session: RendererSession) {
        runCatching { session.onRelease() }
        session.close()
    }

    /** The tab state the user sees: a pending location wins over the old one. */
    fun activeTabState(tabId: String): FileTab? =
        pendingNavigations[tabId] ?: tabs.firstOrNull { it.id == tabId }

    fun activeStopId(tabId: String): String? = activeTabState(tabId)?.currentStop?.id

    fun activeSession(tabId: String): RendererSession? {
        val stop = activeTabState(tabId)?.currentStop ?: return null
        return rendererSessions[stop.id]
    }

    /** True when [session] is the renderer the tab is showing right now. */
    fun isActiveSession(tabId: String, session: RendererSession): Boolean =
        rendererSessions[activeStopId(tabId)] === session

    /** Bind a freshly created host to the session it was made for. */
    fun bindHost(host: RenderHost, session: RendererSession) {
        (host as? ShellRenderHost)?.boundSession = session
    }

    /** Release the oldest warm stops once the coarse budget is exceeded. */
    fun evictWarm() {
        val total = warmSessions.sumOf { rendererSessions[it]?.cacheCost ?: 0 }
        if (total <= warmBudget) return
        val protected = buildSet {
            tabs.forEach { add(it.currentStop.id) }
            pendingNavigations.values.forEach { add(it.currentStop.id) }
            addAll(restoringStops)
        }
        var excess = total - warmBudget
        for (stopId in warmSessions.toList()) {
            if (excess <= 0) break
            if (stopId in protected) continue
            val session = rendererSessions[stopId] ?: continue
            if (session.retention != Retention.WARM) continue
            warmSessions.remove(stopId)
            rendererSessions.remove(stopId)?.let(::releaseSession)
            excess -= session.cacheCost
        }
    }

    fun touchWarm(stopId: String) {
        warmSessions.remove(stopId)
        warmSessions.addLast(stopId)
        evictWarm()
    }

    /**
     * Deactivate the stop being left. A [Retention.WARM] session stays in the
     * map for Back; a [Retention.NONE] one is released immediately.
     */
    fun deactivateStop(stopId: String?) {
        stopId ?: return
        val session = rendererSessions[stopId] ?: return
        session.onDeactivate()
        if (session.retention == Retention.NONE) {
            rendererSessions.remove(stopId)?.let(::releaseSession)
            warmSessions.remove(stopId)
        }
    }

    /** Force-release every session a tab (or its pending location) owns. */
    fun releaseStops(vararg states: FileTab?) {
        states.filterNotNull()
            .flatMap { it.history }
            .distinctBy { it.id }
            .forEach { stop ->
                rendererSessions.remove(stop.id)?.let(::releaseSession)
                warmSessions.remove(stop.id)
            }
    }

    /** Drop a navigation that has not committed, releasing its extra stops. */
    fun cancelPending(tabId: String): FileTab? {
        val pending = pendingNavigations.remove(tabId) ?: return null
        val active = tabs.firstOrNull { it.id == tabId }?.currentStop?.id
        pending.history.forEach { stop ->
            if (stop.id != active) {
                rendererSessions.remove(stop.id)?.let(::releaseSession)
                warmSessions.remove(stop.id)
            }
        }
        return pending
    }

    /** Mint the directory/Home session for a location stop. */
    fun newLocationSession(tab: FileTab, stop: HistoryStop): RendererSession {
        val host = hostFor(tab.id)
        val session = RendererSessions.location(
            host = host,
            scope = scope,
            path = stop.path,
            initialSelection = tab.selectedPaths,
            initialSelectedIndex = tab.lastSelectedIndex,
        )
        bindHost(host, session)
        rendererSessions[stop.id] = session
        touchWarm(stop.id)
        session.onActivate()
        return session
    }

    /**
     * Freeze [entries] into copy/move mode. The source rows stop being shown:
     * from here the list only serves to pick a destination.
     */
    fun startClipboard(entries: List<ClipboardEntry>, action: ClipboardAction) {
        if (entries.isEmpty()) return
        ClipboardStore.begin(entries, action)
        rendererSessions.values.forEach { session ->
            (session as? ListingSession)?.clearDisplayedSelection()
        }
    }

    /** The live tab view, whichever renderer owns it. */
    fun rendererOf(id: String): RendererSession? {
        val tab = activeTabState(id) ?: return null
        val stop = tab.currentStop
        rendererSessions[stop.id]?.let { return it }
        // A page or an edited file is rebuilt from its stop; only a directory
        // or Home has a session the shell can mint on demand.
        if (stop.kind != StopKind.LOCATION) return null
        // Background tabs keep only sessions they already built.
        if (id != currentTab().id) return null
        return newLocationSession(tab, stop)
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
        // A new navigation truncates the forward stops. Release the warm
        // sessions those stops still held instead of waiting for LRU.
        if (previous.history.size != tab.history.size ||
            previous.history.lastOrNull()?.id != tab.history.lastOrNull()?.id
        ) {
            val kept = tab.history.mapTo(mutableSetOf()) { it.id }
            previous.history.forEach { stop ->
                if (stop.id !in kept) {
                    rendererSessions.remove(stop.id)?.let(::releaseSession)
                    warmSessions.remove(stop.id)
                }
            }
        }
    }

    fun replaceCurrent(tab: FileTab) = replaceTab(tab)

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

    /**
     * Display label of a web stop when it never reported a title: the document
     * name the address carries, else its host.
     */
    fun pageLabel(address: String): String =
        BrowseAddress.displayName(address) ?: address

    /** Primary line for a history stop: its last title, folder or segment. */
    fun historyLabel(stop: HistoryStop): String = when {
        isHomePath(stop.path) -> "Home"
        !stop.title.isNullOrBlank() -> stop.title.orEmpty()
        stop.folder != null -> stop.folder.name
        stop.kind == StopKind.PAGE || BrowseAddress.isWebUrl(stop.path) -> pageLabel(stop.path)
        else -> Path.parse(stop.path).name ?: stop.path
    }

    /**
     * Secondary line for a history stop: the full address. Folder views keep
     * their provider path plus nested segments, web pages keep the whole URL.
     */
    fun historyDetail(stop: HistoryStop): String =
        if (isHomePath(stop.path)) "" else stop.folder?.let(::folderLabel) ?: stop.path

    /** The sheet's projection of the tab's one history list. */
    fun historyRows(tab: FileTab = currentTab()): List<HistoryRow> =
        tab.history.mapIndexed { index, stop ->
            HistoryRow(
                stop = stop,
                label = historyLabel(stop),
                detail = historyDetail(stop),
                current = index == tab.historyIndex,
            )
        }

    /**
     * Dismiss the active overlay (a page or an editor) and reveal the stop
     * behind it. A directory/Home is the tab location, not an overlay, so this
     * is a no-op there.
     */
    fun closeRenderer(id: String) {
        val tab = tabs.firstOrNull { it.id == id } ?: return
        val stop = tab.currentStop
        if (stop.kind == StopKind.LOCATION) return
        val session = rendererSessions[stop.id]
        if (session?.isLocationRenderer == true) return
        clearLeavePrompt(id)
        if (session != null) {
            rendererSessions.remove(stop.id)?.let(::releaseSession)
            warmSessions.remove(stop.id)
        }
        val index = tab.history.indexOfFirst { it.id == stop.id }
        if (index < 0) return
        val stops = tab.history.toMutableList().apply { removeAt(index) }
        if (stops.isEmpty()) {
            replaceTab(tab.copy(history = emptyList()).appended(HistoryStop(path = tab.path, folder = tab.folder)))
            return
        }
        val nextIndex = (index - 1).coerceIn(0, stops.lastIndex)
        replaceTab(tab.copy(history = stops, historyIndex = nextIndex).movedTo(nextIndex))
    }

    /**
     * Give [id] a renderer.
     *
     * When the current renderer can adopt the new location without a rebuild
     * (a live page navigating to another live page), it keeps its view and the
     * session just created is closed. Otherwise the new session owns a new
     * stop, and the location being left becomes a Back stop.
     */
    fun showRenderer(
        id: String,
        session: RendererSession,
        host: RenderHost,
        address: String,
        pushHistory: Boolean = true,
    ) {
        clearLeavePrompt(id)
        cancelPending(id)
        var tab = tabs.firstOrNull { it.id == id } ?: run {
            releaseSession(session)
            return
        }
        var current = tab.currentStop
        val existing = rendererSessions[current.id]
        val kind = if (session.webAddress != null || session.isLive) StopKind.PAGE else StopKind.FILE
        val title = session.title?.removePrefix("• ")?.takeIf { it.isNotBlank() }
        // A location can never be replaced by an overlay: even a caller that
        // asked for no history push gets a new stop above the folder.
        val appendStop = pushHistory || current.kind == StopKind.LOCATION
        val target = if (appendStop) {
            HistoryStop(path = address, kind = kind, title = title)
        } else {
            current.copy(path = address, kind = kind, title = title)
        }

        // A page being left keeps the title it last reported, in its own stop.
        if (appendStop && current.kind != StopKind.LOCATION) {
            val titled = current.copy(
                title = tab.webTitle?.removePrefix("• ")?.takeIf { it.isNotBlank() } ?: current.title,
            )
            tab = tab.replacedCurrent(titled)
            current = tab.currentStop
        }

        if (existing != null && existing !== session && existing.adopt(address, session)) {
            // Same live view, new stop: move its binding so callbacks still
            // reach the tab. The session keeps the host it was created with.
            if (current.id != target.id) {
                rendererSessions.remove(current.id)
                rendererSessions.remove(target.id)?.let(::releaseSession)
                rendererSessions[target.id] = existing
            }
            existing.onActivate()
            replaceTab(if (appendStop) tab.appended(target) else tab.replacedCurrent(target))
            return
        }

        if (existing != null && existing !== session) {
            deactivateStop(current.id)
        }
        rendererSessions[target.id] = session
        bindHost(host, session)
        session.onActivate()
        if (session.retention == Retention.WARM) touchWarm(target.id)
        replaceTab(if (appendStop) tab.appended(target) else tab.replacedCurrent(target))
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
        val stop = activeTabState(id)?.currentStop ?: return false
        val session = rendererSessions[stop.id] ?: return false
        if (!session.needsLeaveGuard) return false
        // A hidden tab has no visible dialog host. Its navigation is not
        // user-driven, so release the renderer before switching that tab.
        if (id != currentTab().id) {
            rendererSessions.remove(stop.id)?.let(::releaseSession)
            action()
            return true
        }
        session.requestClose {
            rendererSessions.remove(stop.id)?.let(::releaseSession)
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
        if (!force && path == tab.path && tab.currentStop.kind == StopKind.LOCATION) return
        if (record && !isHomePath(path)) {
            recordHome(HomeEntryKind.FOLDER, path, Path.parse(path).name ?: path, path)
        }
        val next = tab.appended(
            HistoryStop(path = path, folder = view, kind = StopKind.LOCATION),
        )
        deactivateStop(tab.currentStop.id)
        if (tabId == currentTab().id) {
            highlightPath = null
            highlightDirectory = null
            pendingScrollPath = null
        }
        // Home is a landing placeholder, not a directory: entering or leaving
        // it has nothing to load, so adopt the location in this frame. A
        // pending load would leave the home page blocking taps until the next
        // composition commits it.
        if (isHomePath(path) || isHomePath(tab.path)) {
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
        if (folder is Folder) {
            recordHome(HomeEntryKind.FOLDER, folder.path.toString(), folder.name, folderLabel(folder))
        }
        val stop = HistoryStop(path = folder.name, folder = folder, kind = StopKind.LOCATION)
        if (tabId == currentTab().id) {
            highlightPath = null
            highlightDirectory = null
            pendingScrollPath = null
        }
        // A rejected page guess is one stop, not a stop plus an archive above
        // it: replace it in place and let the directory renderer take over.
        if (replaceCurrent && tab.currentStop.kind != StopKind.LOCATION) {
            deactivateStop(tab.currentStop.id)
            replaceTab(tab.replacedCurrent(stop))
            return
        }
        val next = tab.appended(stop)
        deactivateStop(tab.currentStop.id)
        if (isHomePath(tab.path)) replaceTab(next) else pendingNavigations[next.id] = next
    }

    /** Put an open outcome on screen for [tabId]. */
    fun applyOpen(
        tabId: String,
        file: VFile,
        address: String,
        name: String,
        content: ContentInfo?,
        host: RenderHost,
        pushHistory: Boolean,
        outcome: AppOpenOutcome,
    ) {
        when (outcome) {
            is AppOpenOutcome.Folder ->
                navigateToFolder(outcome.node, tabId, replaceCurrent = !pushHistory)
            is AppOpenOutcome.Renderer -> showRenderer(tabId, outcome.session, host, address, pushHistory)
            AppOpenOutcome.External -> openFile(
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
        val guessedStop = tabs.firstOrNull { it.id == tabId }?.currentStop?.id ?: return
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
            if (rendererSessions[guessedStop] === session) closeRenderer(tabId)
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
        host: RenderHost,
        /** A document the user opened: it becomes a home row. */
        recordHome: Boolean = true,
        /** A new location: the outgoing one becomes a Back stop. */
        pushHistory: Boolean = true,
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
        applyOpen(tabId, file, address, name, content, host, pushHistory, outcome)
        if (optimistic) {
            // The renderer that took the tab is the one whose address is a
            // page; the optimistic open made it the current stop already.
            val page = activeSession(tabId)
            if (page?.webAddress != null) confirmPage(tabId, file, page) { facts ->
                // The reopen carries the facts, so the table cannot guess a
                // second time. It pushes no history and records no home row:
                // the optimistic open already made this address the current
                // stop and its own Home row. A fresh host belongs to the
                // replacement session.
                routeVFile(
                    tabId = tabId,
                    file = file,
                    address = address,
                    name = name,
                    contentHint = facts,
                    host = hostFor(tabId),
                    recordHome = false,
                    pushHistory = false,
                )
            }
        }
    }

    fun renderHostFor(id: String): ShellRenderHost {
        val hostScope = scope
        val host = object : ShellRenderHost {
            override var boundSession: RendererSession? = null
            override val scope: CoroutineScope get() = hostScope

            override fun navigate(node: io.furryr.file.core.VNode, rendererId: String?) {
                if (node is VFolder) {
                    if (node is Folder) navigateTo(node.path.toString(), id)
                    else navigateToFolder(node, id)
                }
            }

            override fun updateTitle(title: String?) {
                // A late title from a renderer that no longer owns the tab must
                // not rename the new view. Session identity, not stop id, is
                // the ownership check: an adopted page keeps its host.
                val session = boundSession ?: return
                if (!isActiveSession(id, session)) return
                val label = title?.removePrefix("• ")?.trim()?.takeIf { it.isNotBlank() }
                updateTab(id) { tab ->
                    val updated = if (label != null) {
                        tab.replacedCurrent(tab.currentStop.copy(title = label))
                    } else {
                        tab
                    }
                    updated.copy(webTitle = title)
                }
                if (title != null && session.isLive) {
                    val address = session.address ?: return
                    HomeStore.updateTitle(address, title)
                    homeVersion++
                }
            }

            /**
             * A page moved itself. The tab holds the history, so a moved page
             * either lands on a stop the tab already made (the page's own
             * back/forward) or appends the step it just took.
             */
            override fun onPageVisited(address: String, replacePrevious: String?) {
                val session = boundSession ?: return
                if (!isActiveSession(id, session)) return
                // A page-driven step supersedes a folder load in this tab.
                cancelPending(id)
                val tab = tabs.firstOrNull { it.id == id } ?: return
                val current = tab.currentStop
                if (current.path == address) return
                // A redirect never rendered its source page, so its provisional
                // stop is replaced in place, not pushed.
                if (replacePrevious != null && current.path == replacePrevious) {
                    val redirected = current.copy(path = address, kind = StopKind.PAGE, title = null)
                    replaceTab(
                        tab.replacedCurrent(redirected).copy(webUrl = address, webTitle = null),
                    )
                    return
                }
                // The page being left keeps the title it last reported.
                val titled = tab.replacedCurrent(
                    current.copy(
                        title = tab.webTitle?.removePrefix("• ")?.takeIf { it.isNotBlank() }
                            ?: current.title,
                    ),
                )
                val known = titled.history.indexOfLast {
                    it.path == address && it.kind == StopKind.PAGE && it.id != current.id
                }
                if (known >= 0) {
                    val target = titled.history[known]
                    // The same WebView now shows the target stop. Move its
                    // binding instead of releasing it; the old stop is a cache
                    // hit, not an owner.
                    rendererSessions.remove(current.id)
                    rendererSessions.remove(target.id)?.let(::releaseSession)
                    rendererSessions[target.id] = session
                    session.onActivate()
                    replaceTab(
                        titled.movedTo(known).copy(webUrl = address, webTitle = target.title),
                    )
                    return
                }
                val next = HistoryStop(path = address, kind = StopKind.PAGE)
                rendererSessions.remove(current.id)
                rendererSessions[next.id] = session
                session.onActivate()
                replaceTab(titled.appended(next))
            }

            override fun updateCapabilities(capabilities: RenderCapabilities) = Unit

            override fun refresh() {
                (activeSession(id) as? ListingSession)
                    ?.refreshListing(animate = true)
            }

            override fun confirmLeave(
                message: String?,
                canSave: Boolean,
                onSave: (suspend () -> Result<Unit>)?,
                onLeave: () -> Unit,
                onCancel: () -> Unit,
            ) {
                // A background tab has no visible dialog host; its page must
                // not block navigation that never reaches the user.
                if (id != currentTab().id) {
                    onLeave()
                    return
                }
                requestLeave(
                    LeavePrompt(
                        ownerTabId = id,
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
                // user opened, so it does not become a home entry. A fresh host
                // belongs to the target session; this one stays bound to the
                // renderer that asked.
                val targetHost = renderHostFor(id)
                scope.launch {
                    routeVFile(
                        id,
                        file,
                        file.path.toString(),
                        file.name,
                        content,
                        targetHost,
                        recordHome = false,
                    )
                }
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
                        is Folder -> navigateTo(node.path.toString(), id)
                        is VFolder -> navigateToFolder(node, id)
                        else -> navigateTo(entry.path, id)
                    }
                } else {
                    when (node) {
                        is VFile -> openResolvedNodeFn(node, entry)
                        else -> openFile(context, entry)
                    }
                }
            }

            override fun requestShare(entries: List<FileEntry>) = shareEntries(context, entries)

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
                val session = boundSession ?: return
                if (!isActiveSession(id, session)) return
                // A pending location has no tab row yet; its selection is
                // seeded from the tab when the load commits.
                if (pendingNavigations[id] == null) {
                    val tabIndex = tabs.indexOfFirst { it.id == id }
                    if (tabIndex >= 0) {
                        tabs[tabIndex] = tabs[tabIndex].copy(
                            selectedPaths = paths,
                            lastSelectedIndex = lastIndex,
                        )
                    }
                }
                if (id == currentTab().id) ClipboardStore.select(entries)
            }

            override fun onFolderLoaded(path: String) {
                val session = boundSession ?: return
                if (!isActiveSession(id, session)) return
                val pending = pendingNavigations[id] ?: return
                if (pending.path != path && pending.currentStop.path != path) return
                replaceTab(pending)
                pendingNavigations.remove(id)
            }

            override fun onFolderCancelled() {
                val session = boundSession ?: return
                if (!isActiveSession(id, session)) return
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
                val session = boundSession ?: return null
                if (!isActiveSession(id, session)) return null
                val tab = activeTabState(id) ?: return null
                val committed = tabs.firstOrNull { it.id == id } ?: tab
                return FolderRendererState(
                    path = tab.path,
                    committedPath = committed.path,
                    folder = tab.folder,
                    scrollToPath = pendingScrollPath,
                    highlightPath = highlightPath,
                    highlightDirectory = highlightDirectory,
                    opening = nodeOpeningTab == id,
                )
            }

            override fun homeView(): HomeRendererState? {
                val session = boundSession ?: return null
                if (!isActiveSession(id, session)) return null
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
                closeRenderer(id)
            }
        }
        return host
    }
    hostFor = ::renderHostFor

    /** Open a node the caller already resolved. */
    fun openVFile(
        file: VFile,
        address: String,
        name: String,
        contentHint: ContentInfo? = null,
        recordHome: Boolean = true,
        pushHistory: Boolean = true,
    ) {
        val tabId = currentTab().id
        val host = renderHostFor(tabId)
        scope.launch {
            routeVFile(tabId, file, address, name, contentHint, host, recordHome, pushHistory)
        }
    }

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

    fun openNode(file: VFile, entry: FileEntry) {
        val tabId = currentTab().id
        val host = renderHostFor(tabId)
        trackNodeOpen(tabId) {
            routeVFile(tabId, file, entry.path, entry.name, null, host)
        }
    }
    openResolvedNodeFn = ::openNode

    fun toggleHomeBookmark(entry: HomeEntry) {
        HomeStore.toggleBookmark(entry)
        homeVersion++
    }
    toggleHomeBookmarkFn = ::toggleHomeBookmark

    fun currentBookmarkEntry(): HomeEntry? {
        val tab = currentTab()
        val pending = pendingOf(tab.id)
        val path = pending?.path ?: tab.path
        // A page or an opened file is bookmarkable even when it was opened
        // from home; only the directory branch needs a real location. The
        // session knows whether its address is live or a projected file.
        val session = activeSession(tab.id)
        if (session != null && session.address != null) {
            if (session.suppressBookmark) return null
            val address = session.address!!
            val kind = if (session.isLive) HomeEntryKind.WEB else HomeEntryKind.FILE
            return HomeEntry(kind, address, session.title ?: tab.webTitle ?: address, address)
        }
        if (isHomePath(path) || pending?.folder != null || tab.folder != null) return null
        return HomeEntry(
            HomeEntryKind.FOLDER,
            path,
            Path.parse(path).name ?: path,
            path,
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
     * single entry for a typed address, a home row and a history stop — nothing
     * here reads a scheme, and no caller needs a second "open a URL" path.
     */
    fun openAddress(address: String, tabId: String = currentTab().id) {
        if (guardRenderer(tabId) { openAddress(address, tabId) }) return
        if (tabs.none { it.id == tabId }) return
        val parsed = NodeAddress.parse(address)
        scope.launch {
            val node = resolveNode(parsed)
            when (node) {
                // A view level already resolved to a node: keep it, so an
                // archive root is not re-opened from its path.
                is VFolder -> if (parsed.isViewed) navigateToFolder(node, tabId)
                else navigateTo(address, tabId, view = node.takeIf { it !is Folder })
                is VFile -> openVFile(node, address, node.name)
                null -> navigateTo(address, tabId, record = false)
            }
        }
    }

    /** Open a home row through the same path a directory row uses. */
    fun openHomeEntry(entry: HomeEntry) {
        when (entry.kind) {
            HomeEntryKind.FOLDER -> navigateTo(entry.location)
            HomeEntryKind.WEB, HomeEntryKind.FILE -> openAddress(entry.location)
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
        if (tab.currentStop.kind != StopKind.LOCATION) {
            closeRenderer(tab.id)
            return
        }
        replaceCurrent(tab.copy(folder = null, webUrl = null, webTitle = null))
    }

    /** Reveal [address] selected inside [parent], the jump-dialog behaviour. */
    fun selectInParent(parent: VFolder, address: String) {
        val tabId = currentTab().id
        if (parent.path.toString() == currentTab().path) {
            closeRenderer(tabId)
            val listing = rendererOf(tabId) as? ListingSession
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
                    else navigateTo(input, tabId, view = node.takeIf { it !is Folder })
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
    fun restoreStopNode(tabId: String, stop: HistoryStop) {
        if (!restoringStops.add(stop.id)) return
        scope.launch {
            try {
                val node = resolveNode(NodeAddress.parse(stop.path))
                // The tab may have moved on while the address was resolving.
                val current = tabs.firstOrNull { it.id == tabId }
                if (current?.currentStop?.id != stop.id ||
                    pendingNavigations[tabId] != null ||
                    rendererSessions[stop.id] != null
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
                            host = renderHostFor(tabId),
                            recordHome = false,
                            pushHistory = false,
                        )
                        // The route may have handed the file to an external
                        // viewer. A file/page stop with no renderer would only
                        // draw a blank tab; reveal the location behind it.
                        val tab = tabs.firstOrNull { it.id == tabId }
                        if (tab?.currentStop?.id == stop.id &&
                            tab.currentStop.kind != StopKind.LOCATION &&
                            rendererSessions[stop.id] == null
                        ) {
                            closeRenderer(tabId)
                        }
                    }
                    is VFolder -> updateTab(tabId) { tab ->
                        // A stop recorded as a file/page can turn out to be a
                        // folder after the fact; the directory renderer takes it.
                        if (tab.currentStop.id == stop.id) {
                            tab.replacedCurrent(
                                stop.copy(kind = StopKind.LOCATION, folder = node, title = null),
                            )
                        } else {
                            tab
                        }
                    }
                    null -> Unit
                }
            } finally {
                restoringStops.remove(stop.id)
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
    fun moveToStop(tab: FileTab, index: Int) {
        if (tab.id != currentTab().id) return
        if (pendingNavigations[tab.id] != null) return
        if (index !in tab.history.indices || index == tab.historyIndex) return
        val target = tab.history[index]
        val current = tab.currentStop
        val session = rendererSessions[current.id]
        // A WebView is one view: moving to a page in its own cache transfers
        // the binding instead of releasing and rebuilding it.
        if (target.kind == StopKind.PAGE && session != null && session.retrace(target.path)) {
            rendererSessions.remove(current.id)
            rendererSessions.remove(target.id)?.let(::releaseSession)
            rendererSessions[target.id] = session
            session.onActivate()
            replaceTab(tab.movedTo(index))
            return
        }
        clearLeavePrompt(tab.id)
        deactivateStop(current.id)
        val moved = tab.movedTo(index)
        replaceTab(moved)
        when (target.kind) {
            StopKind.LOCATION -> rendererSessions[target.id]?.let { targetSession ->
                targetSession.onActivate()
                if (targetSession.retention == Retention.WARM) touchWarm(target.id)
            }
            StopKind.FILE, StopKind.PAGE -> {
                // A stale binding for the target would otherwise be replaced
                // without its release hook.
                rendererSessions.remove(target.id)?.let(::releaseSession)
                restoreStopNode(tab.id, target)
            }
        }
    }

    fun goBack() {
        val tab = currentTab()
        if (pendingNavigations[tab.id] != null) return
        if (!tab.canGoBack) {
            // A trimmed list can leave an overlay without a location behind it;
            // dismissing the overlay is still the right undo.
            if (tab.currentStop.kind != StopKind.LOCATION &&
                !guardRenderer(tab.id) { goBack() }
            ) {
                closeRenderer(tab.id)
            }
            return
        }
        if (guardRenderer(tab.id) { goBack() }) return
        moveToStop(tab, tab.historyIndex - 1)
    }

    fun goForward() {
        val tab = currentTab()
        if (pendingNavigations[tab.id] != null) return
        if (!tab.canGoForward) return
        if (guardRenderer(tab.id) { goForward() }) return
        moveToStop(tab, tab.historyIndex + 1)
    }

    /** Select a stop in the history panel; the same move as Back or Forward. */
    fun jumpToHistory(index: Int) {
        val tab = currentTab()
        if (pendingNavigations[tab.id] != null) return
        if (index !in tab.history.indices || index == tab.historyIndex) return
        if (guardRenderer(tab.id) { jumpToHistory(index) }) return
        moveToStop(tab, index)
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
            if (parentAddress == tab.path && tab.currentStop.kind != StopKind.LOCATION) {
                if (rendererParent is Folder) {
                    navigateTo(parentAddress, force = true)
                } else {
                    navigateToFolder(rendererParent)
                }
                return
            }
            // A provider folder is addressed by its path; only a view folder
            // needs its node kept as a hint.
            if (rendererParent is Folder) navigateTo(parentAddress)
            else navigateToFolder(rendererParent)
            return
        }
        val activePath = pendingOf(tab.id)?.path ?: tab.path
        if (isHomePath(activePath)) return
        val activeFolder = pendingOf(tab.id)?.folder ?: tab.folder
        if (activeFolder != null) {
            val parent = activeFolder.parent ?: return
            if (parent is Folder) navigateTo(parent.path.toString()) else navigateToFolder(parent)
            return
        }
        val parent = Path.parse(activePath).parent()?.toString() ?: return
        navigateTo(parent)
    }

    fun refreshFileList(
        animate: Boolean = true,
        highlightPath: String? = null,
        highlightDirectory: String? = null,
    ) {
        val listing = rendererOf(currentTab().id) as? ListingSession ?: return
        listing.refreshListing(animate, highlightPath, highlightDirectory)
    }

    /** Single refresh entry point for the toolbar/menu. */
    fun refreshActive() {
        val renderer = rendererOf(currentTab().id)
        if (renderer?.refresh() == true) return
        refreshFileList(animate = true)
    }

    /** Capabilities of whatever owns the current tab: renderer or directory. */
    fun currentCapabilities(): RenderCapabilities {
        val tab = currentTab()
        val state = activeTabState(tab.id) ?: tab
        val pending = pendingOf(tab.id)
        val activePath = pending?.path ?: tab.path
        val folder = pending?.folder ?: tab.folder
        val renderer = rendererOf(tab.id)
        if (renderer != null) {
            return renderer.capabilities.copy(
                // History belongs to the tab. A renderer that can dismiss
                // itself (a page or the editor) also makes Back available.
                canGoBack = state.canGoBack || renderer.canDismiss,
                canGoForward = state.canGoForward,
            )
        }
        val canParent = folder?.parent != null ||
            (folder == null && Path.parse(activePath).parent() != null)
        return RenderCapabilities(
            canGoBack = state.canGoBack,
            canGoForward = state.canGoForward,
            canGoParent = canParent,
            canRefresh = true,
            canCreate = false,
            canTerminal = false,
        )
    }

    val handleHereRequest by rememberUpdatedState(newValue = { request: HereNavigation.Request ->
        val tab = currentTab()
        clearLeavePrompt(tab.id)
        // A here request supersedes an in-flight navigation in the current tab.
        cancelPending(tab.id)
        deactivateStop(tab.currentStop.id)
        showTerminalSheet = false
        highlightPath = request.selectedPath
        highlightDirectory = request.directory
        pendingScrollPath = request.selectedPath
        // Re-entering the same plain directory is not a step; an overlay over
        // that directory still gets a new location stop behind it.
        if (tab.currentStop.kind == StopKind.LOCATION && tab.path == request.directory) {
            replaceCurrent(
                tab.copy(
                    folder = null,
                    webUrl = null,
                    webTitle = null,
                    selectedPaths = emptySet(),
                    lastSelectedIndex = -1,
                ),
            )
        } else {
            val destination = tab.appended(
                HistoryStop(path = request.directory, kind = StopKind.LOCATION),
            )
            pendingNavigations[destination.id] = destination
        }
        // Refresh even within the same directory so newly created entries can
        // be selected once the matching asynchronous listing is committed.
        refreshFileList(
            highlightPath = highlightPath,
            highlightDirectory = highlightDirectory,
        )
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

    // Renderer sessions are owned per history stop and survive tab switches.
    DisposableEffect(Unit) {
        onDispose {
            rendererSessions.values.forEach { releaseSession(it) }
            rendererSessions.clear()
            warmSessions.clear()
        }
    }

    val activeRenderer = rendererOf(currentTab().id)
    // An activity recreate drops the session map but keeps the tab history.
    // Rebuild a page/file stop when its renderer is gone; a directory mints
    // its session on demand from rendererOf().
    val composedStop = currentTab().currentStop
    LaunchedEffect(composedStop.id) {
        val tab = currentTab()
        if (composedStop.kind == StopKind.LOCATION) return@LaunchedEffect
        if (pendingNavigations[tab.id] != null) return@LaunchedEffect
        if (rendererSessions[composedStop.id] != null) return@LaunchedEffect
        restoreStopNode(tab.id, composedStop)
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
    // Home has no directory load callback to commit a pending navigation, so
    // adopt it here. Otherwise the pending tab would block the next navigate.
    // Every tab's pending home is adopted, not just the selected one: a back
    // action can queue one and then the user can switch tabs before the effect
    // runs.
    val pendingHomeTabs = pendingNavigations.values.filter { isHomePath(it.path) }
    LaunchedEffect(pendingHomeTabs) {
        pendingHomeTabs.forEach { next ->
            replaceTab(next)
            pendingNavigations.remove(next.id)
            if (next.id == currentTab().id) {
                highlightPath = null
                pendingScrollPath = null
            }
        }
    }

    fun closeTabNow(index: Int) {
        if (tabs.size <= 1) return
        val closing = tabs.removeAt(index)
        clearLeavePrompt(closing.id)
        releaseStops(closing, pendingNavigations.remove(closing.id))
        selectedTabIndex = selectedTabIndex.coerceAtMost(tabs.lastIndex)
    }

    fun closeTab(index: Int) {
        if (tabs.size <= 1) return
        // guardRenderer returns false when the tab has no editor; the close must
        // still run in that case (web pages, home, plain directories).
        if (!guardRenderer(tabs[index].id) { closeTabNow(index) }) closeTabNow(index)
    }

    fun moveTab(from: Int, to: Int) {
        if (from == to || from !in tabs.indices || to !in tabs.indices) return
        val selectedId = currentTab().id
        val moved = tabs.removeAt(from)
        tabs.add(to, moved)
        selectedTabIndex = tabs.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
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
            tabs.add(FileTab(path = HomePath))
            selectedTabIndex = 0
        } else {
            selectedTabIndex = selectedTabIndex.coerceAtMost(tabs.lastIndex)
        }
    }

    /** The tab's directory as a node: the one path→node step operations use. */
    suspend fun folderOf(tab: FileTab): VFolder? = folderFor(tab.path, tab.folder)

    /** A listed entry as a node of [folder]. */
    suspend fun nodeOf(folder: VFolder?, name: String): VNode? =
        if (folder == null) null else withContext(Dispatchers.IO) { folder.child(name).getOrNull() }

    fun currentListing(): ListingSession? =
        rendererOf(currentTab().id) as? ListingSession

    fun selectedEntries(): List<FileEntry> = currentListing()?.selectedEntries.orEmpty()

    fun clearSelection() {
        currentListing()?.clearSelection()
    }

    /** Clear the pending payload and any plain selection left behind. */
    fun clearClipboard() {
        ClipboardStore.clear()
        rendererSessions.values.forEach { session ->
            (session as? ListingSession)?.clearDisplayedSelection()
        }
    }

    /** Run the standard clipboard list into the current renderer's sink. */
    fun performPaste() {
        val state = ClipboardStore.state
        if (!state.inTransfer) return
        val sink = rendererOf(currentTab().id)?.clipboardSink ?: return
        if (!sink.accepts(state)) return
        val folder = sink.destinationFolder() ?: return
        val isCopy = state.action == ClipboardAction.COPY
        operationHidden = false
        operationJob?.cancel()
        operationJob = scope.launch {
            operationProgress = OperationProgress(
                0, 0, state.entries.firstOrNull()?.name.orEmpty(), isCopy,
            )
            var lastBytes = 0L
            var lastTime = System.currentTimeMillis()
            var failure: Throwable? = null
            try {
                ClipboardTransfer.run(state, folder) { progress ->
                    if (!progress.finished) {
                        val now = System.currentTimeMillis()
                        val elapsed = now - lastTime
                        var speed = operationProgress?.speedBytesPerSec ?: 0L
                        if (elapsed >= SPEED_SAMPLE_MS) {
                            val delta = progress.copiedBytes - lastBytes
                            if (delta >= 0) speed = delta * 1000 / elapsed
                            lastBytes = progress.copiedBytes
                            lastTime = now
                        }
                        operationProgress = OperationProgress(
                            progress.totalBytes,
                            progress.copiedBytes,
                            progress.currentName.ifBlank { state.entries.first().name },
                            isCopy,
                            speed,
                        )
                    }
                }.onFailure { failure = it }
            } catch (e: CancellationException) {
                OperationNotification.cancel(context)
                throw e
            } catch (e: Exception) {
                failure = e
            }
            OperationNotification.cancel(context)
            operationTaskIds = null
            Toast.makeText(
                context,
                if (failure == null) {
                    context.getString(
                        if (isCopy) R.string.toast_copied else R.string.toast_moved,
                        state.entries.firstOrNull()?.name.orEmpty(),
                    )
                } else {
                    failure?.message ?: context.getString(R.string.toast_failed)
                },
                Toast.LENGTH_SHORT
            ).show()
            if (failure == null) {
                clearClipboard()
            }
            operationProgress = null
            operationJob = null
            refreshFileList()
        }
    }

    LaunchedEffect(pendingTabIndex) {
        if (pendingTabIndex >= 0) {
            selectedTabIndex = pendingTabIndex
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
                    val stopId = previous.currentStop.id
                    val listing = (rendererSessions[stopId] as? ListingSession)
                    if (listing != null) {
                        listing.onDeactivate()
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
            activeSession(currentTab().id)?.onActivate()
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
            // out of a folder through history first. "Up" is the parent action,
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
            FileDrawer(
                darkTheme = darkTheme,
                onToggleTheme = onToggleTheme,
                onHomeClick = {
                    navigateTo(DefaultPath)
                    scope.launch { drawerState.close() }
                },
                onRootClick = {
                    navigateTo("/")
                    scope.launch { drawerState.close() }
                },
                onContainerClick = { name ->
                    navigateTo(ContainerUris.forContainer(name) + "/")
                    scope.launch { drawerState.close() }
                },
                onNewContainer = {
                    // The drawer owns the form; creation is handled by onCreateContainer below.
                },
                rootAvailable = DaemonManager.isRootAvailable(),
                onCreateContainer = { name, image, backend ->
                    scope.launch {
                        ContainerNotification.showProgress(context, name, 0L, 0L)
                        val result = withContext(Dispatchers.IO) {
                            ContainerManager.createContainer(
                                context = context,
                                name = name,
                                imageRef = image,
                                backend = backend,
                                progress = { current, total ->
                                    ContainerNotification.showProgress(context, name, current, total)
                                },
                            )
                        }
                        if (result.isSuccess) {
                            ContainerNotification.cancel(context)
                            Toast.makeText(context, "[$name]", Toast.LENGTH_SHORT).show()
                        } else {
                            ContainerNotification.showFailure(
                                context,
                                name,
                                result.exceptionOrNull()?.message ?: "Unknown error",
                            )
                        }
                        containerRefreshKey++
                    }
                },
                onSettingsClick = {
                    drawerSortMode = false
                    context.startActivity(Intent(context, SettingsActivity::class.java))
                },
                onContainerRename = { oldName, newName ->
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            ContainerManager.renameContainer(context, oldName, newName)
                        }
                        Toast.makeText(
                            context,
                            if (result.isSuccess) "Renamed to '$newName'"
                            else result.exceptionOrNull()?.message ?: "Rename failed",
                            Toast.LENGTH_SHORT
                        ).show()
                        containerRefreshKey++
                    }
                },
                onContainerDelete = { name ->
                    scope.launch {
                        if (!DaemonManager.isRootAvailable() &&
                            ContainerManager.getContainer(context, name)?.backend == ContainerBackend.CHROOT
                        ) return@launch
                        val result = withContext(Dispatchers.IO) {
                            ContainerManager.deleteContainer(context, name)
                        }
                        Toast.makeText(
                            context,
                            if (result.isSuccess) "Deleted '$name'"
                            else result.exceptionOrNull()?.message ?: "Delete failed",
                            Toast.LENGTH_SHORT
                        ).show()
                        containerRefreshKey++
                    }
                },
                onContainerKill = { name ->
                    scope.launch {
                        if (!DaemonManager.isRootAvailable() &&
                            ContainerManager.getContainer(context, name)?.backend == ContainerBackend.CHROOT
                        ) return@launch
                        // Close every terminal session of the container first,
                        // then tear down its dedicated daemon (kills the process tree).
                        SessionManager.destroyContainerSessions(name)
                        withContext(Dispatchers.IO) {
                            DaemonManager.closeNamedConnection(name)
                        }
                        // Any tab browsing into the killed container is now
                        // invalid; drop it (and reopen home if none remain).
                        closeTabsForContainer(name)
                        containerRefreshKey++
                        Toast.makeText(
                            context,
                            context.getString(R.string.toast_killed, name),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                },
                sortMode = drawerSortMode,
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
                containerOrder = containerNameOrder,
                onContainerReorder = { from, to ->
                    val mutable = containerNameOrder.toMutableList()
                    if (from in mutable.indices && to in mutable.indices) {
                        val item = mutable.removeAt(from)
                        mutable.add(to, item)
                        containerNameOrder = mutable
                    }
                },
                refreshKey = containerRefreshKey,
                safLocations = locations.safLocations,
                safRefreshKey = locations.safRefreshKey,
                onAddLocation = {
                    safFolderLauncher.launch(
                        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                            .setPackage("com.android.documentsui")
                    )
                },
                onUseExternalStorage = {
                    safFolderLauncher.launch(
                        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                            .setPackage("com.android.documentsui")
                    )
                },
                localLocations = locations.localLocations,
                localOrder = locations.localLocations.map { it.id },
                onAddLocalPath = { path ->
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching { LocationsRepository.insertLocal(path) }
                        }
                        if (result.isFailure) {
                            Toast.makeText(
                                context,
                                result.exceptionOrNull()?.message ?: "Failed to add local location",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                        locations.refreshLocal()
                    }
                },
                onLocalClick = { location ->
                    navigateTo(location.path)
                    scope.launch { drawerState.close() }
                },
                onLocalRename = { id, newName ->
                    scope.launch {
                        withContext(Dispatchers.IO) { LocationsRepository.renameLocal(id, newName) }
                        locations.refreshLocal()
                    }
                },
                onLocalDelete = { id ->
                    scope.launch {
                        withContext(Dispatchers.IO) { LocationsRepository.deleteLocal(id) }
                        locations.refreshLocal()
                    }
                },
                onLocalReorder = { from, to ->
                    val mutable = locations.localLocations.toMutableList()
                    if (from in mutable.indices && to in mutable.indices) {
                        val item = mutable.removeAt(from)
                        mutable.add(to, item)
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                LocationsRepository.reorderLocal(mutable.map { it.id })
                            }
                            locations.refreshLocal()
                        }
                    }
                },
                onSafClick = { hexId ->
                    navigateTo("saf://$hexId/")
                    scope.launch { drawerState.close() }
                },
                onSafRename = { hexId, newName ->
                    scope.launch {
                        withContext(Dispatchers.IO) { LocationsRepository.renameSaf(hexId, newName) }
                        locations.refreshSaf()
                    }
                },
                onSafDelete = { hexId ->
                    scope.launch {
                        withContext(Dispatchers.IO) { LocationsRepository.deleteSaf(hexId) }
                        locations.refreshSaf()
                    }
                },
                safOrder = locations.safLocations.map { it.hexId },
                onSafReorder = { from, to ->
                    val mutable = locations.safLocations.toMutableList()
                    if (from in mutable.indices && to in mutable.indices) {
                        val item = mutable.removeAt(from)
                        mutable.add(to, item)
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                LocationsRepository.reorderSaf(mutable.map { it.hexId })
                            }
                            locations.refreshSaf()
                        }
                    }
                },
                ftpLocations = locations.ftpLocations,
                ftpRefreshKey = locations.ftpRefreshKey,
                onAddFtp = { host, name, port, username, password ->
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching { LocationsRepository.insertFtp(host, name, port, username, password) }
                        }
                        Toast.makeText(
                            context,
                            if (result.isSuccess) "Added FTP location"
                            else result.exceptionOrNull()?.message ?: "Failed to add",
                            Toast.LENGTH_SHORT
                        ).show()
                        locations.refreshFtp()
                    }
                },
                onFtpSave = { oldHost, newHost, name, port, username, password ->
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching { LocationsRepository.updateFtp(oldHost, newHost, name, port, username, password) }
                        }
                        Toast.makeText(
                            context,
                            if (result.isSuccess) "Updated FTP location"
                            else result.exceptionOrNull()?.message ?: "Failed to update",
                            Toast.LENGTH_SHORT
                        ).show()
                        locations.refreshFtp()
                    }
                },
                onFtpClick = { host ->
                    navigateTo("ftp://$host/")
                    scope.launch { drawerState.close() }
                },
                onFtpDelete = { host ->
                    scope.launch {
                        withContext(Dispatchers.IO) { LocationsRepository.deleteFtp(host) }
                        locations.refreshFtp()
                    }
                },
                ftpOrder = locations.ftpLocations.map { it.host },
                onFtpReorder = { from, to ->
                    val mutable = locations.ftpLocations.toMutableList()
                    if (from in mutable.indices && to in mutable.indices) {
                        val item = mutable.removeAt(from)
                        mutable.add(to, item)
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                LocationsRepository.reorderFtp(mutable.map { it.host })
                            }
                            locations.refreshFtp()
                        }
                    }
                },
                sftpLocations = locations.sftpLocations,
                sftpRefreshKey = locations.sftpRefreshKey,
                onAddSftp = { host, name, port, username, password ->
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching { LocationsRepository.insertSftp(host, name, port, username, password) }
                        }
                        Toast.makeText(
                            context,
                            if (result.isSuccess) "Added SFTP location"
                            else result.exceptionOrNull()?.message ?: "Failed to add",
                            Toast.LENGTH_SHORT
                        ).show()
                        locations.refreshSftp()
                    }
                },
                onSftpSave = { oldHost, newHost, name, port, username, password ->
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching { LocationsRepository.updateSftp(oldHost, newHost, name, port, username, password) }
                        }
                        Toast.makeText(
                            context,
                            if (result.isSuccess) "Updated SFTP location"
                            else result.exceptionOrNull()?.message ?: "Failed to update",
                            Toast.LENGTH_SHORT
                        ).show()
                        locations.refreshSftp()
                    }
                },
                onSftpClick = { host ->
                    navigateTo("sftp://$host/")
                    scope.launch { drawerState.close() }
                },
                onSftpDelete = { host ->
                    scope.launch {
                        withContext(Dispatchers.IO) { LocationsRepository.deleteSftp(host) }
                        locations.refreshSftp()
                    }
                },
                sftpOrder = locations.sftpLocations.map { it.host },
                onSftpReorder = { from, to ->
                    val mutable = locations.sftpLocations.toMutableList()
                    if (from in mutable.indices && to in mutable.indices) {
                        val item = mutable.removeAt(from)
                        mutable.add(to, item)
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                LocationsRepository.reorderSftp(mutable.map { it.host })
                            }
                            locations.refreshSftp()
                        }
                    }
                },
                remoteLocations = locations.remoteLocations,
                onAddRemote = { location ->
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching { LocationsRepository.insertRemote(location) }
                        }
                        Toast.makeText(
                            context,
                            if (result.isSuccess) "Added remote location"
                            else result.exceptionOrNull()?.message ?: "Failed to add",
                            Toast.LENGTH_SHORT,
                        ).show()
                        locations.refreshRemote()
                    }
                },
                onRemoteSave = { location ->
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching { LocationsRepository.updateRemote(location) }
                        }
                        Toast.makeText(
                            context,
                            if (result.isSuccess) "Updated remote location"
                            else result.exceptionOrNull()?.message ?: "Failed to update",
                            Toast.LENGTH_SHORT,
                        ).show()
                        locations.refreshRemote()
                    }
                },
                onRemoteClick = { location ->
                    navigateTo(location.uri)
                    scope.launch { drawerState.close() }
                },
                onRemoteDelete = { id ->
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching { LocationsRepository.deleteRemote(id) }
                        }
                        if (result.isFailure) {
                            Toast.makeText(
                                context,
                                result.exceptionOrNull()?.message ?: "Failed to delete",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                        locations.refreshRemote()
                    }
                },
                remoteOrder = locations.remoteLocations.map { it.id },
                onRemoteReorder = { from, to ->
                    val mutable = locations.remoteLocations.toMutableList()
                    if (from in mutable.indices && to in mutable.indices) {
                        val item = mutable.removeAt(from)
                        mutable.add(to, item)
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                LocationsRepository.reorderRemote(mutable.map { it.id })
                            }
                            locations.refreshRemote()
                        }
                    }
                },
                webLocations = locations.webLocations,
                onAddWeb = { url, name ->
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching { LocationsRepository.insertWeb(url, name) }
                        }
                        if (result.isFailure) {
                            Toast.makeText(
                                context,
                                result.exceptionOrNull()?.message ?: "Failed to add web location",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                        locations.refreshWeb()
                    }
                },
                onWebClick = { url ->
                    openAddress(url)
                    scope.launch { drawerState.close() }
                },
                onWebRename = { id, newName ->
                    scope.launch {
                        withContext(Dispatchers.IO) { LocationsRepository.renameWeb(id, newName) }
                        locations.refreshWeb()
                    }
                },
                onWebDelete = { id ->
                    scope.launch {
                        withContext(Dispatchers.IO) { LocationsRepository.deleteWeb(id) }
                        locations.refreshWeb()
                    }
                },
                webOrder = locations.webLocations.map { it.id },
                onWebReorder = { from, to ->
                    val mutable = locations.webLocations.toMutableList()
                    if (from in mutable.indices && to in mutable.indices) {
                        val item = mutable.removeAt(from)
                        mutable.add(to, item)
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                LocationsRepository.reorderWeb(mutable.map { it.id })
                            }
                            locations.refreshWeb()
                        }
                    }
                },
            )
        }
    ) {
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
                val caps = currentCapabilities()
                val webLoading = activeRenderer?.loading == true
                val webProgress = (activeRenderer?.progress ?: 0) / 100f
                // Idle handles stay registered until they close, so track the
                // one that produced the newest block instead of summing all.
                val transfer = remoteTransfers.maxByOrNull { it.updatedAt }
                val transferReading = (transfer?.reading ?: 0) > 0
                var transferLinger by remember { mutableStateOf(false) }
                // The strip disappears right after the last block lands, but a
                // short hold keeps it from flickering between 128 KiB blocks.
                LaunchedEffect(remoteTransfers) {
                    if (transferReading) {
                        transferLinger = true
                    } else if (transferLinger) {
                        delay(700)
                        transferLinger = false
                    }
                }
                val transferProgress = if (transfer != null && transfer.totalBytes > 0L) {
                    (transfer.fetchedBytes.toFloat() / transfer.totalBytes).coerceIn(0f, 1f)
                } else {
                    0f
                }
                Column(
                    modifier = Modifier.onGloballyPositioned {
                        topBarBounds = it.boundsInWindow()
                    },
                ) {
                    if (operationProgress != null && !operationHidden) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.dp)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    } else if (transfer != null && (transferReading || transferLinger)) {
                        // Remote block reads share the same 2 dp strip as page
                        // and copy progress: one bar, no extra layout.
                        LinearProgressIndicator(
                            progress = { transferProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.dp),
                        )
                    } else if (webLoading) {
                        // Same top strip as copy/move progress, so page loading
                        // never adds a second bar or re-measures the content.
                        // A fresh navigation reports progress 0 until the first
                        // byte arrives; a determinate bar at 0 would draw
                        // nothing, so keep an indeterminate animation visible
                        // for that whole wait.
                        if (webProgress > 0f) {
                            LinearProgressIndicator(
                                progress = { webProgress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(2.dp),
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(2.dp),
                            )
                        }
                    }
                    TopAppBar(
                        title = {
                            val titleTab = currentTab()
                            val titlePending = pendingOf(titleTab.id)
                            val titleSlot = activeRenderer?.titleSlot()
                            if (titleSlot != null) {
                                titleSlot(Modifier)
                            } else {
                                val titleFolder = titlePending?.folder ?: titleTab.folder
                                val titleRenderer = rendererOf(titleTab.id)
                                val titlePage = titlePending?.webUrl ?: titleTab.webUrl
                                // Renderers own their path bar: Home, a page
                                // and the editor all report their own title,
                                // address and caption.
                                val rendererPath = titleRenderer?.pathBar(context)
                                val titleDisplay = rendererPath?.title
                                    ?: titlePage?.let { pageLabel(it) }
                                    ?: titleFolder?.let { folderLabel(it) }
                                    ?: titlePending?.path
                                    ?: titleTab.path
                                // Any location that round-trips through the
                                // opener route is editable; a view without an
                                // address stays a read-only breadcrumb.
                                val titleEditable = rendererPath?.editable ?: (titleFolder == null ||
                                    runCatching {
                                        NodeAddress.format(titleFolder!!.path)
                                    }.getOrNull() != null)
                                TitleBlock(
                                    path = titlePending?.path ?: titleTab.path,
                                    info = if (rendererPath == null && titlePage == null) {
                                        titleRenderer?.locationInfo ?: DirectoryStats()
                                    } else {
                                        DirectoryStats()
                                    },
                                    onNavigate = { jumpTo(it) },
                                    onBounds = { titleBounds = it },
                                    displayPath = titleDisplay,
                                    editText = rendererPath?.editText ?: titleDisplay,
                                    editable = titleEditable,
                                    detail = rendererPath?.caption,
                                    suggestionSource = pathSuggestionSource,
                                    suggestionAnchor = topBarBounds,
                                    onSuggestion = ::chooseTitleSuggestion,
                                    onEditingChange = { active -> titleEditing = active },
                                )
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.cd_menu))
                            }
                        },
                        actions = {
                            val menuItems = activeRenderer?.menuItems(context).orEmpty()
                            Box {
                                IconButton(onClick = {
                                    menuChildren = null
                                    showMoreMenu = true
                                }) {
                                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.cd_more))
                                }
                                DropdownMenu(
                                    expanded = showMoreMenu,
                                    onDismissRequest = {
                                        showMoreMenu = false
                                        menuChildren = null
                                    }
                                ) {
                                    val children = menuChildren
                                    if (children != null) {
                                        children.forEach { child ->
                                            RendererMenuItemRow(
                                                item = child,
                                                onAction = { chosen ->
                                                    chosen.onClick()
                                                    showMoreMenu = false
                                                    menuChildren = null
                                                },
                                                onSubmenu = { parent ->
                                                    menuChildren = parent.children
                                                },
                                            )
                                        }
                                    } else {
                                        menuItems.forEach { item ->
                                            RendererMenuItemRow(
                                                item = item,
                                                onAction = { chosen ->
                                                    chosen.onClick()
                                                    showMoreMenu = false
                                                    menuChildren = null
                                                },
                                                onSubmenu = { parent ->
                                                    menuChildren = parent.children
                                                },
                                            )
                                        }
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.settings)) },
                                            leadingIcon = { Icon(Icons.Default.Settings, null) },
                                            onClick = {
                                                context.startActivity(Intent(context, SettingsActivity::class.java))
                                                showMoreMenu = false
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.exit)) },
                                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.ExitToApp, null) },
                                            onClick = {
                                                val activity = context as? Activity
                                                val finish: () -> Unit = { activity?.finish() }
                                                if (!guardRenderer(currentTab().id, finish)) finish()
                                                showMoreMenu = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    )
                }
            },
            bottomBar = {
                val toolTab = currentTab()
                val caps = currentCapabilities()
                val renderer = rendererOf(toolTab.id)
                val clipboard = ClipboardStore.state
                val selection = (renderer as? ListingSession)
                val selectionMode = !clipboard.inTransfer &&
                    selection != null && selection.selectedPaths.isNotEmpty()
                val canShare = selection != null && currentTab().folder == null &&
                    selection.selectedEntries.all { !it.isDirectory }
                val copyActive = clipboard.inTransfer
                val canConfirmCopyMove = copyActive &&
                    renderer?.clipboardSink?.accepts(clipboard) == true
                BottomToolbar(
                    modifier = Modifier.onGloballyPositioned {
                        bottomBarHeightPx = it.size.height
                    },
                    canGoBack = caps.canGoBack,
                    canGoForward = caps.canGoForward,
                    canGoParent = caps.canGoParent,
                    onBack = ::goBack,
                    onForward = ::goForward,
                    onCreate = { renderer?.requestCreate() },
                    canCreate = caps.canCreate,
                    onTerminal = { showTerminalSheet = true },
                    onParent = ::goParent,
                    onHistoryDrag = { if (!showHistory) historyDragPx = it },
                    onHistoryDragEnd = { revealed ->
                        historyDragPx = 0f
                        if (!showHistory && revealed >= historyRevealThresholdPx) {
                            showHistory = true
                        }
                    },
                    canOpenTerminal = caps.canTerminal || hasTerminalSessions,
                    selectionMode = selectionMode,
                    onSelectAll = { selection?.selectAll() },
                    onInvertSelection = { selection?.invertSelection() },
                    onCancelSelection = { selection?.clearSelection() },
                    onSelectSameType = { selection?.selectSameType() },
                    onShare = { shareEntries(context, selection?.selectedEntries.orEmpty()) },
                    canShare = canShare,
                    copyActive = copyActive,
                    canConfirmCopyMove = canConfirmCopyMove,
                    onCopyMoveConfirm = ::performPaste,
                    onCopyMoveCancel = ::clearClipboard,
                )
            }
        ) { padding ->
            Box(modifier = Modifier.padding(padding)) {
                Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    BoxWithConstraints(modifier = Modifier.weight(1f)) {
                        if (maxWidth >= 90.dp * tabs.size.toFloat()) {
                            TabRow(
                                selectedTabIndex = selectedTabIndex,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                tabs.forEachIndexed { index, tab ->
                                    DraggableFileTab(
                                        index = index,
                                        tab = tab,
                                        selected = index == selectedTabIndex,
                                        showClose = tabs.size > 1,
                                        onClick = { selectedTabIndex = index },
                                        onClose = { closeTab(index) },
                                        onMove = ::moveTabBy,
                                    )
                                }
                            }
                        } else {
                            ScrollableTabRow(
                                selectedTabIndex = selectedTabIndex,
                                modifier = Modifier.fillMaxWidth(),
                                edgePadding = 0.dp
                            ) {
                                tabs.forEachIndexed { index, tab ->
                                    DraggableFileTab(
                                        index = index,
                                        tab = tab,
                                        selected = index == selectedTabIndex,
                                        showClose = tabs.size > 1,
                                        onClick = { selectedTabIndex = index },
                                        onClose = { closeTab(index) },
                                        onMove = ::moveTabBy,
                                    )
                                }
                            }
                        }
                    }
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .combinedClickable(
                                onClick = {
                                    tabs.add(FileTab(path = HomePath))
                                    pendingTabIndex = tabs.lastIndex
                                },
                                onLongClick = { showSessionManager = true },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.cd_new_tab))
                    }
                }

                val tab = currentTab()
                when {
                    // The live renderer owns the tab view. It decides how its
                    // document/page is drawn; the shell only supplies the slot.
                    activeRenderer != null -> activeRenderer.Content(
                        darkTheme = darkTheme,
                        modifier = Modifier.fillMaxSize(),
                    )
                    // A page owns the location even before its renderer is
                    // rebuilt; draw nothing rather than falling back to Home.
                    tab.webUrl != null -> Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background),
                    )
                    // Every directory tab has a FolderRendererSession. This
                    // branch only covers the frame between a location being
                    // superseded and its next renderer being built.
                    else -> Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background),
                    )
                }
                if (titleEditing) {
                    // A black scrim over an AMOLED-black list is invisible, so
                    // dark mode veils the content with light instead; the
                    // suggestion popup then reads as the darker panel on top.
                    val scrim = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
                        // Black alpha over AMOLED black changes nothing; a
                        // low-alpha white veil is the visible equivalent.
                        Color.White.copy(alpha = 0.12f)
                    } else {
                        Color.Black.copy(alpha = 0.32f)
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(scrim)
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                            ) { focusManager.clearFocus() },
                    )
                }
            }
            }
        }
    }

    showDeleteConfirm?.let { entries ->
        DeleteConfirmDialog(
            entries = entries,
            onDismiss = { showDeleteConfirm = null },
            onConfirm = {
                showDeleteConfirm = null
                val targets = entries
                val tab = currentTab()
                scope.launch {
                    var failure: Throwable? = null
                    withContext(Dispatchers.IO) {
                        val folder = folderOf(tab)
                        for (entry in targets) {
                            val node = nodeOf(folder, entry.name)
                            val result = runCatching {
                                (node ?: error("missing ${entry.name}")).delete().getOrThrow()
                            }
                            if (result.isFailure) {
                                failure = result.exceptionOrNull()
                                break
                            }
                        }
                    }
                    val error = failure
                    val message = when {
                        error != null -> error.message ?: context.getString(R.string.toast_failed)
                        targets.size == 1 -> context.getString(R.string.toast_deleted, targets.first().name)
                        else -> context.getString(R.string.toast_deleted_count, targets.size)
                    }
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    clearSelection()
                    refreshFileList()
                }
            }
        )
    }

    showRenameDialog?.let { entry ->
        RenameDialog(
            entry = entry,
            onDismiss = { showRenameDialog = null },
            onConfirm = { newName ->
                val tab = currentTab()
                val oldName = entry.name
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            val folder = folderOf(tab) ?: error("cannot rename here")
                            folder.child(oldName).getOrThrow().rename(newName).getOrThrow()
                        }
                    }
                    Toast.makeText(
                        context,
                        if (result.isSuccess) context.getString(R.string.toast_renamed, newName)
                        else result.exceptionOrNull()?.message ?: context.getString(R.string.toast_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                    showRenameDialog = null
                    refreshFileList()
                }
            }
        )
    }

    if (showBatchRenameDialog) {
        val entries = selectedEntries()
        BatchRenameDialog(
            count = entries.size,
            onDismiss = { showBatchRenameDialog = false },
            onConfirm = { pattern, replacement ->
                val regex = runCatching { Regex(pattern) }.getOrNull()
                val targets = entries
                val tab = currentTab()
                scope.launch {
                    val failures = if (regex == null) {
                        listOf(IllegalArgumentException(context.getString(R.string.toast_failed)))
                    } else {
                        withContext(Dispatchers.IO) {
                            val folder = folderOf(tab)
                            targets.mapNotNull { entry ->
                                val newName = regex.replace(entry.name, replacement)
                                if (newName.isBlank() || newName == entry.name) {
                                    null
                                } else {
                                    runCatching {
                                        folder?.child(entry.name)?.getOrThrow()
                                            ?.rename(newName)?.getOrThrow()
                                    }.exceptionOrNull()
                                }
                            }
                        }
                    }
                    Toast.makeText(
                        context,
                        if (failures.isEmpty()) context.getString(R.string.toast_renamed, targets.size.toString())
                        else failures.first().message ?: context.getString(R.string.toast_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                    showBatchRenameDialog = false
                    if (failures.isEmpty()) clearSelection()
                    refreshFileList()
                }
            }
        )
    }

    showEntryInfo?.let { entry ->
        EntryInfoDialog(
            entry = entry,
            onDismiss = { showEntryInfo = null }
        )
    }

    if (operationProgress != null && !operationHidden) {
        val clipboard = ClipboardStore.state
        OperationProgressDialog(
            progress = operationProgress!!,
            sourceParent = clipboard.sourceParent,
            targetPath = currentTab().path,
            onHide = {
                operationHidden = true
                OperationNotification.show(
                    context,
                    operationProgress!!.isCopy,
                    clipboard.sourceParent.ifBlank { operationProgress!!.currentName },
                )
            },
            onCancel = {
                OperationNotification.cancel(context)
                operationJob?.cancel()
                operationJob = null
                operationProgress = null
                refreshFileList()
            }
        )
    }

    containerError?.let { reason ->
        AlertDialog(
            onDismissRequest = { containerError = null },
            title = { Text(stringResource(R.string.container_create_failed)) },
            text = { Text(reason) },
            confirmButton = {
                TextButton(onClick = { containerError = null }) {
                    Text(stringResource(R.string.confirm))
                }
            },
        )
    }

    if (showSessionManager) {
        SessionManagerDialog(
            tabs = tabs.toList(),
            selectedIndex = selectedTabIndex,
            onSelect = {
                selectedTabIndex = it
                showSessionManager = false
            },
            onClose = { closeTab(it) },
            onMove = { from, to -> moveTab(from, to) },
            onDismiss = { showSessionManager = false },
        )
    }

    val allHistory = historyRows()
    // Home is a landing marker, not a navigation stop: hide it from the
    // sheet, but keep the full list for index-based history navigation.
    val visibleHistory = allHistory.filterNot { isHomePath(it.stop.path) }
    HistorySheet(
        visible = showHistory,
        dragPx = historyDragPx,
        stops = visibleHistory,
        bottomInset = with(LocalDensity.current) { bottomBarHeightPx.toDp() },
        onSelect = { row ->
            val index = currentTab().history.indexOfFirst { it.id == row.stop.id }
            if (index >= 0) jumpToHistory(index)
            showHistory = false
        },
        onDismiss = { showHistory = false },
    )

    val terminalCanCreate = currentCapabilities().canTerminal
    TerminalSheet(
        visible = showTerminalSheet,
        onDismiss = { showTerminalSheet = false },
        onShow = { showTerminalSheet = true },
        onDropAccepted = { terminalDropAccepted = true },
        canCreateTerminal = terminalCanCreate,
        // A renderer that needs a different shell cwd publishes it through the
        // session contract; the directory behind it is only the fallback.
        currentPath = rendererOf(currentTab().id)
            ?.terminalPath
            ?: currentTab().path
    )

    val promptModalActive = leavePrompt?.let { rendererOf(it.ownerTabId)?.modalActive == true } == true
    if (leavePrompt != null && !promptModalActive) {
        val prompt = leavePrompt!!
        val save = prompt.onSave
        val canSave = prompt.canSave && save != null
        AlertDialog(
            onDismissRequest = {
                if (!leaveSaving) {
                    leavePrompt = null
                    prompt.onCancel()
                }
            },
            title = { Text(stringResource(R.string.editor_unsaved)) },
            text = {
                Text(
                    prompt.message?.takeIf { it.isNotBlank() }
                        ?: stringResource(
                            if (canSave) R.string.editor_unsaved_message
                            else R.string.editor_leave_message
                        )
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !leaveSaving,
                    onClick = {
                        if (save == null || !canSave) {
                            leavePrompt = null
                            prompt.onLeave()
                        } else {
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
                                    if (leavePrompt === prompt) leavePrompt = null
                                    prompt.onLeave()
                                } else {
                                    if (leavePrompt === prompt) leavePrompt = null
                                    prompt.onCancel()
                                }
                            }
                        }
                    },
                ) {
                    Text(stringResource(if (canSave) R.string.editor_save else R.string.editor_leave))
                }
            },
            dismissButton = {
                if (canSave) {
                    Row {
                        TextButton(
                            enabled = !leaveSaving,
                            onClick = {
                                leavePrompt = null
                                prompt.onCancel()
                            },
                        ) { Text(stringResource(R.string.cancel)) }
                        TextButton(
                            enabled = !leaveSaving,
                            onClick = {
                                leavePrompt = null
                                prompt.onLeave()
                            },
                        ) { Text(stringResource(R.string.editor_discard)) }
                    }
                } else {
                    TextButton(
                        onClick = {
                            leavePrompt = null
                            prompt.onCancel()
                        },
                    ) { Text(stringResource(R.string.cancel)) }
                }
            },
        )
    }
}

@Composable
private fun RendererMenuItemRow(
    item: RendererMenuItem,
    onAction: (RendererMenuItem) -> Unit,
    onSubmenu: (RendererMenuItem) -> Unit,
) {
    val children = item.children.orEmpty()
    DropdownMenuItem(
        text = { Text(item.title) },
        leadingIcon = item.icon?.let { icon -> { Icon(icon, null) } },
        trailingIcon = when {
            children.isNotEmpty() -> {
                { Icon(Icons.Default.ChevronRight, contentDescription = null) }
            }
            item.checked != null -> {
                {
                    Checkbox(
                        checked = item.checked == true,
                        onCheckedChange = null,
                        enabled = item.enabled,
                    )
                }
            }
            else -> null
        },
        enabled = item.enabled,
        onClick = {
            if (children.isEmpty()) onAction(item) else onSubmenu(item)
        },
    )
}

/** Minimum window between two transfer-rate samples shown in the copy dialog. */
private const val SPEED_SAMPLE_MS = 250L

/**
 * targetSdk 28 keeps the legacy soft-input contract: the terminal sheet needs
 * adjustResize to stay above the IME, every other screen keeps adjustNothing.
 * API 30 deprecates the constant in favour of edge-to-edge WindowInsets, which
 * would change layout app-wide, so keep the legacy mode here.
 */
@Suppress("DEPRECATION")
private fun terminalSoftInputMode(showTerminalSheet: Boolean): Int =
    if (showTerminalSheet) {
        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
    } else {
        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
    }

private fun storagePermissions(): List<String> = when {
    Build.VERSION.SDK_INT >= 29 -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    else -> listOf(
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    )
}

private fun openAllFilesAccessSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
        Uri.parse("package:${context.packageName}")
    )
    runCatching { context.startActivity(intent) }.recoverCatching {
        context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
    }
}
