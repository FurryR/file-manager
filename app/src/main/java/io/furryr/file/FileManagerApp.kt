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
import io.furryr.file.core.RemoteFile
import io.furryr.file.core.ContentTypes
import io.furryr.file.core.Path
import io.furryr.file.editor.EditorRendererSession

import io.furryr.file.daemon.DaemonManager
import io.furryr.file.daemon.DaemonStatus
import io.furryr.file.container.ContainerManager
import io.furryr.file.container.ContainerBackend
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
import io.furryr.file.core.Vfs
import io.furryr.file.render.DirectoryRenderer
import io.furryr.file.render.HomeRenderer
import io.furryr.file.render.LocationAddress
import io.furryr.file.render.RenderCapabilities
import io.furryr.file.render.RenderHost
import io.furryr.file.render.RendererMenuItem
import io.furryr.file.render.RendererSession
import io.furryr.file.render.WebRendererSession
import io.furryr.file.provider.web.WebTransfers
import io.furryr.file.provider.web.peek
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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

import io.furryr.file.model.CopyMoveSource
import io.furryr.file.model.CopyMoveState
import io.furryr.file.core.DirectoryStats
import io.furryr.file.core.model.FileEntry
import io.furryr.file.model.FileTab
import io.furryr.file.model.OperationProgress
import io.furryr.file.ui.components.BottomToolbar
import io.furryr.file.ui.components.FileDrawer
import io.furryr.file.ui.screens.FileListScreen
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FileManagerApp(
    darkTheme: Boolean = true,
    onToggleTheme: () -> Unit = {},
    terminalOpenRequest: Int = 0,
    containerErrorRequest: String? = null,
) {
    val context = LocalContext.current
    val tabs = remember { mutableStateListOf(FileTab(path = HomePath)) }
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var currentInfo by remember { mutableStateOf(DirectoryStats()) }
    var showInfoDialog by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var containerError by remember(containerErrorRequest) { mutableStateOf(containerErrorRequest) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var menuChildren by remember { mutableStateOf<List<RendererMenuItem>?>(null) }
    var pendingTabIndex by remember { mutableIntStateOf(-1) }
    var refreshTrigger by remember { mutableIntStateOf(0) }
    var animationTrigger by remember { mutableIntStateOf(0) }
    var sortMode by remember { mutableStateOf(SortMode.NAME) }
    var sortReverse by remember { mutableStateOf(false) }
    var showSortDialog by remember { mutableStateOf(false) }
    var searchMode by remember { mutableStateOf(false) }
    var filterText by remember { mutableStateOf("") }

    // Context menu & file operations state
    var contextMenuEntry by remember { mutableStateOf<FileEntry?>(null) }
    var showDeleteConfirm by remember { mutableStateOf<List<FileEntry>?>(null) }
    var showRenameDialog by remember { mutableStateOf<FileEntry?>(null) }
    var showBatchRenameDialog by remember { mutableStateOf(false) }
    var showEntryInfo by remember { mutableStateOf<FileEntry?>(null) }

    var currentEntries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var operationProgress by remember { mutableStateOf<OperationProgress?>(null) }
    var operationJob by remember { mutableStateOf<Job?>(null) }
    var operationTaskIds: List<Long>? = null  // kept only for notification UI reference
    var operationHidden by remember { mutableStateOf(false) }
    var highlightPath by remember { mutableStateOf<String?>(null) }
    var highlightDirectory by remember { mutableStateOf<String?>(null) }
    var highlightRefreshToken by remember { mutableStateOf<Int?>(null) }
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
    // One live renderer session per tab, keyed by FileTab.id. Switching tabs
    // keeps the WebView (and its own back/forward history) alive.
    val webSessions = remember { mutableStateMapOf<String, WebRendererSession>() }
    // Titles the pages reported, keyed by page address. History lives in the
    // tab stacks, which carry addresses only; this keeps the label of a page
    // stop after its WebView is gone.
    val pageTitles = remember { mutableStateMapOf<String, String>() }
    // Text editors are tab-owned too: switching tabs keeps the Sora buffer,
    // undo history and LSP session alive until the tab closes.
    val editorSessions = remember { mutableStateMapOf<String, EditorRendererSession>() }
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

    // Copy/Move state
    var copyMoveState by remember { mutableStateOf<CopyMoveState?>(null) }
    var showCopyMovePlaceDialog by remember { mutableStateOf(false) }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    // Typed locators resolve through the same node service the agent uses:
    // opener registry, no format knowledge here.
    val nodeService = remember { DefaultNodeService() }

    // Exit sort mode when drawer closes
    // Any tap outside the inline path editor dismisses it, like an address
    // bar. Compose touch targets do not move focus on their own, so the root
    // sees every press before its children and clears focus for out-of-bounds
    // presses only.
    val focusManager = LocalFocusManager.current
    var titleBounds by remember { mutableStateOf<Rect?>(null) }
    var rootCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    LaunchedEffect(drawerState.isClosed) {
        if (drawerState.isClosed) drawerSortMode = false
    }
    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    fun currentTab(): FileTab = tabs[selectedTabIndex]

    /** The live tab view, whichever renderer owns it. */
    fun rendererOf(id: String): RendererSession? = webSessions[id] ?: editorSessions[id]

    fun folderLabel(folder: VFolder): String {
        // A view folder carries its own address (provider path plus view
        // levels); no format-specific formatting belongs here.
        runCatching { LocationAddress.format(folder.path) }.getOrNull()?.let { return it }
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

    fun pendingForCurrent(): FileTab? = pendingNavigations[currentTab().id]

    fun recordHome(kind: HomeEntryKind, location: String, title: String, detail: String = "") {
        if (HomeStore.record(kind, location, title, detail)) homeVersion++
    }

    var directoryCapabilities by remember {
        mutableStateOf(DirectoryCapabilities())
    }

    fun replaceTab(tab: FileTab) {
        val index = tabs.indexOfFirst { it.id == tab.id }
        if (index >= 0) tabs[index] = tab
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
     * Address of the location this tab is showing.
     *
     * A renderer owns the tab when one is live: the page it is on, or the file
     * it is editing. Otherwise the address is the folder/home behind it. This is
     * what a history push records, so the edited file is a stop of its own and
     * Back can reopen it.
     */
    fun currentLocationAddress(tab: FileTab): String? =
        editorSessions[tab.id]?.address
            ?: tab.webUrl

    /** Push the tab's current location onto its history before navigation. */
    fun recordCurrentHistory(tab: FileTab): FileTab {
        val location = currentLocationAddress(tab)
        return tab.copy(
            backStack = tab.backStack + (location ?: tab.path),
            folderBackStack = tab.folderBackStack + (if (location != null) null else tab.folder),
            forwardStack = emptyList(),
            folderForwardStack = emptyList(),
        )
    }

    /** Display label of a page stop: the title it reported, else its host. */
    fun pageLabel(address: String): String =
        pageTitles[address]?.takeIf { it.isNotBlank() }
            ?: runCatching { java.net.URI(address).host }.getOrNull()
                ?.takeIf { it.isNotBlank() }
            ?: address

    /** Primary line for a history stop: the folder name, or the last path segment. */
    fun historyLabel(path: String, folder: VFolder?): String = when {
        isHomePath(path) -> "Home"
        folder != null -> folder.name
        BrowseAddress.isWebUrl(path) -> pageLabel(path)
        else -> Path.parse(path).name ?: path
    }

    /**
     * Secondary line for a history stop: the full address. Folder views keep
     * their provider path plus nested segments, web pages keep the whole URL.
     */
    fun historyDetail(path: String, folder: VFolder?): String =
        if (isHomePath(path)) "" else if (folder != null) folderLabel(folder) else path

    /**
     * Every stop of [tab], oldest first, with the tab's location marked.
     *
     * One list, one source of truth: a page visit, a folder, Home and an edited
     * file are the same kind of stop, so Back, Forward and the panel all read
     * this and nothing else. A live WebView has no history of its own here; it
     * is only a cache of pages whose bytes it already holds.
     */
    fun historyStops(tab: FileTab = currentTab()): List<HistoryStop> {
        val stops = mutableListOf<HistoryStop>()
        tab.backStack.forEachIndexed { index, path ->
            val folder = tab.folderBackStack.getOrNull(index)
            stops += HistoryStop(
                path = path,
                label = historyLabel(path, folder),
                detail = historyDetail(path, folder),
                isWeb = BrowseAddress.isWebUrl(path),
                folder = folder,
            )
        }
        val editor = editorSessions[tab.id]
        val page = tab.webUrl
        val location = editor?.address ?: page
        if (location != null) {
            // A renderer owns the tab: the page it shows, or the file it edits
            // (drawn over its folder) is where the tab is.
            stops += HistoryStop(
                path = location,
                label = historyLabel(location, null),
                detail = location,
                isWeb = editor == null && BrowseAddress.isWebUrl(location),
                current = true,
            )
        } else {
            stops += HistoryStop(
                path = tab.path,
                label = historyLabel(tab.path, tab.folder),
                detail = historyDetail(tab.path, tab.folder),
                isWeb = false,
                current = true,
                folder = tab.folder,
            )
        }
        tab.forwardStack.forEachIndexed { index, path ->
            val folder = tab.folderForwardStack.getOrNull(index)
            stops += HistoryStop(
                path = path,
                label = historyLabel(path, folder),
                detail = historyDetail(path, folder),
                isWeb = BrowseAddress.isWebUrl(path),
                folder = folder,
            )
        }
        return stops
    }

    /**
     * [tab] with its stacks rebuilt around stop [index] of [stops].
     *
     * The stacks are a window on the same list, never a second history: what
     * lies behind the stop is the back stack, what lies ahead the forward one.
     */
    fun stacksAt(tab: FileTab, stops: List<HistoryStop>, index: Int): FileTab {
        val back = stops.take(index)
        val forward = stops.drop(index + 1)
        return tab.copy(
            backStack = back.map { it.path },
            folderBackStack = back.map { it.folder },
            forwardStack = forward.map { it.path },
            folderForwardStack = forward.map { it.folder },
        )
    }

    fun closeWeb(id: String) {
        clearLeavePrompt(id)
        val session = webSessions.remove(id)
        // A renderer without its own close hook still closes through the
        // default implementation.
        session?.requestClose {
            updateTab(id) { it.copy(webUrl = null, webTitle = null) }
        } ?: run {
            updateTab(id) { it.copy(webUrl = null, webTitle = null) }
        }
    }

    /**
     * Give [id] a page.
     *
     * When the tab already shows a live page, the existing WebView is kept and
     * only its address changes: the route table just proved the new node is a
     * page, so there is nothing to rebuild (and the WebView's own history
     * survives the navigation).
     */
    fun showWeb(id: String, session: WebRendererSession, url: String, pushHistory: Boolean = true) {
        clearLeavePrompt(id)
        val existing = webSessions[id]
        if (existing != null && existing !== session && existing.isRemote && session.isRemote) {
            // A link inside a live page: keep its WebView and let it load the
            // new address. The step is a history stop like any other, because
            // the tab, not the WebView, owns the back/forward list.
            session.close()
            updateTab(id) {
                val recorded = if (pushHistory) recordCurrentHistory(it) else it
                recorded.copy(webUrl = url, webTitle = null)
            }
            existing.load(url)
            return
        }
        // A renderer is taking the tab over: the location it is replacing — a
        // page, an edited file, a folder or Home — stays reachable through Back.
        if (pushHistory) updateTab(id) { recordCurrentHistory(it) }
        webSessions.remove(id)?.close()
        webSessions[id] = session
        updateTab(id) { it.copy(webUrl = url, webTitle = null) }
    }

    fun closeEditor(id: String) {
        clearLeavePrompt(id)
        editorSessions.remove(id)?.close()
    }

    fun showEditor(id: String, session: EditorRendererSession, pushHistory: Boolean = true) {
        // The editor replaces the tab's content, so whatever was on screen (a
        // page, another file, a folder or Home) becomes the history stop Back
        // returns to. The edited file itself is the tab's current location
        // ([currentLocationAddress]), so leaving it pushes it as its own stop.
        // Closing the page below is lossless: every page it visited is already a
        // stop of the tab, not of its WebView.
        if (pushHistory) updateTab(id) { recordCurrentHistory(it) }
        closeEditor(id)
        webSessions.remove(id)?.close()
        editorSessions[id] = session
        updateTab(id) { it.copy(webUrl = null, webTitle = session.title) }
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
     * Run [action] after the tab's editor lets go. The session owns whether a
     * leave prompt is needed; the tab only supplies the dialog host and the
     * post-close cleanup.
     */
    fun guardEditor(id: String, action: () -> Unit): Boolean {
        val session = editorSessions[id] ?: return false
        // A hidden tab has no visible dialog host. Its navigation is not
        // user-driven, so release the buffer before switching that tab.
        if (id != currentTab().id) {
            editorSessions.remove(id)?.close()
            updateTab(id) { it.copy(webTitle = null) }
            action()
            return true
        }
        session.requestClose {
            editorSessions.remove(id)?.close()
            updateTab(id) { it.copy(webTitle = null) }
            action()
        }
        return true
    }

    fun navigateTo(path: String, tabId: String = currentTab().id, record: Boolean = true) {
        if (guardEditor(tabId) { navigateTo(path, tabId, record) }) return
        val tab = tabs.firstOrNull { it.id == tabId } ?: return
        if (path == tab.path || pendingNavigations[tab.id] != null) return
        if (record && !isHomePath(path)) {
            recordHome(HomeEntryKind.FOLDER, path, Path.parse(path).name ?: path, path)
        }
        val next = recordCurrentHistory(tab).copy(
            path = path,
            folder = null,
            webUrl = null,
            webTitle = null,
        )
        closeWeb(tab.id)
        if (tabId == currentTab().id) {
            highlightPath = null
            highlightDirectory = null
            highlightRefreshToken = null
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

    fun navigateToFolder(folder: VFolder, tabId: String = currentTab().id) {
        if (guardEditor(tabId) { navigateToFolder(folder, tabId) }) return
        val tab = tabs.firstOrNull { it.id == tabId } ?: return
        if (pendingNavigations[tab.id] != null) return
        if (folder is Folder) {
            recordHome(HomeEntryKind.FOLDER, folder.path.toString(), folder.name, folderLabel(folder))
        }
        val next = recordCurrentHistory(tab).copy(
            path = folder.name,
            folder = folder,
            webUrl = null,
            webTitle = null,
            selectedPaths = emptySet(),
            lastSelectedIndex = -1,
        )
        closeWeb(tab.id)
        if (tabId == currentTab().id) {
            highlightPath = null
            highlightDirectory = null
            highlightRefreshToken = null
            pendingScrollPath = null
        }
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
            is AppOpenOutcome.Folder -> navigateToFolder(outcome.node, tabId)
            is AppOpenOutcome.Web -> showWeb(tabId, outcome.session, address, pushHistory)
            is AppOpenOutcome.Editor -> showEditor(tabId, outcome.session, pushHistory)
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
        live: RemoteFile,
        session: WebRendererSession,
        reopen: suspend (ContentInfo) -> Unit,
    ) {
        val expected = session.address
        // Keep the page's body behind the loading cover until the server has
        // said what it is, so a hand-back never flashes a frame of it.
        session.beginProbe()
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
            if (webSessions[tabId] !== session || session.address != expected) return@launch
            if (confirmed == null ||
                WebPagePolicy.verdict(confirmed, settled = true) != WebPagePolicy.Verdict.NOT_PAGE
            ) {
                // A page, or an address whose type nobody named: the browser
                // keeps it, exactly where the eager path would have put it.
                session.settlePage()
                return@launch
            }
            // Not a page: drop the guess before reopening, so the node lands in
            // whichever opener or viewer the table picks for its real type.
            closeWeb(tabId)
            reopen(confirmed)
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
        val live = file as? RemoteFile
        val optimistic = contentHint == null && live != null &&
            WebPagePolicy.likelyPage(address)
        // An optimistic address deliberately keeps its facts unknown: that is
        // what makes the route table hand it to the browser without a request.
        val content = contentHint ?: if (optimistic) {
            null
        } else {
            withContext(Dispatchers.IO) { file.describe().getOrNull() }
        }
        val outcome = withContext(Dispatchers.IO) {
            OpenRoutes.route(file, content, OpenRouteContext(host))
        }
        // Only a plain provider path becomes a home row; a view address is
        // rebuilt from its own text when it is reopened. A live resource is a
        // page row, every other node a file row — the node itself decides.
        if (recordHome && !file.path.isViewed) {
            val kind = if (file is RemoteFile) HomeEntryKind.WEB else HomeEntryKind.FILE
            recordHome(kind, address, name, address)
        }
        applyOpen(tabId, file, address, name, content, host, pushHistory, outcome)
        if (optimistic && outcome is AppOpenOutcome.Web) {
            // showWeb keeps the WebView a live page already owned, so the tab —
            // not the fresh session the outcome carries — knows which session
            // now holds this address.
            val page = webSessions[tabId]
            if (page != null) confirmPage(tabId, live, page) { facts ->
                // The reopen carries the facts, so the table cannot guess a
                // second time. It pushes no history and records no home row:
                // the optimistic open already made this address the current
                // stop and its own Home row.
                routeVFile(
                    tabId = tabId,
                    file = file,
                    address = address,
                    name = name,
                    contentHint = facts,
                    host = host,
                    recordHome = false,
                    pushHistory = false,
                )
            }
        }
    }

    fun renderHostFor(id: String): RenderHost {
        val hostScope = scope
        lateinit var host: RenderHost
        host = object : RenderHost {
            override val scope: CoroutineScope get() = hostScope

            override fun navigate(node: io.furryr.file.core.VNode, rendererId: String?) {
                if (node is VFolder) {
                    clearLeavePrompt(id)
                    webSessions.remove(id)?.close()
                    if (node is Folder) navigateTo(node.path.toString(), id)
                    else navigateToFolder(node, id)
                }
            }

            override fun updateTitle(title: String?) {
                // The editor owns the tab title while it is open; a dirty
                // buffer prefixes the file name with a bullet.
                if (editorSessions.containsKey(id)) {
                    updateTab(id) { it.copy(webTitle = title) }
                    return
                }
                // A late title from a page whose tab already left the web view
                // must not rename the directory tab.
                val session = webSessions[id] ?: return
                val url = session.address
                if (title != null) pageTitles[url] = title
                updateTab(id) { it.copy(webTitle = title) }
                if (title != null && session.isRemote) {
                    HomeStore.updateTitle(url, title)
                    homeVersion++
                }
            }

            /**
             * A page moved itself. The tab holds the history, so a moved page
             * either lands on a stop the tab already made (the page's own
             * back/forward) or appends the step it just took.
             */
            override fun onPageVisited(address: String) {
                updateTab(id) { tab ->
                    val previous = currentLocationAddress(tab)
                    if (previous == address) return@updateTab tab
                    // The page being left keeps its title even though its
                    // WebView is gone.
                    if (previous != null) {
                        tab.webTitle?.takeIf { it.isNotBlank() }?.let { pageTitles[previous] = it }
                    }
                    val stops = historyStops(tab)
                    val known = stops.indexOfLast { it.path == address && !it.current }
                    if (known >= 0) {
                        stacksAt(tab, stops, known).copy(
                            webUrl = address,
                            webTitle = pageTitles[address],
                        )
                    } else {
                        recordCurrentHistory(tab).copy(webUrl = address, webTitle = null)
                    }
                }
            }

            override fun updateCapabilities(capabilities: RenderCapabilities) = Unit

            override fun refresh() {
                refreshTrigger++
                animationTrigger++
            }

            override fun toggleFilter() {
                searchMode = !searchMode
                if (!searchMode) filterText = ""
            }

            override fun openSort() {
                showSortDialog = true
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
                // user opened, so it does not become a home entry.
                scope.launch {
                    routeVFile(
                        id,
                        file,
                        file.path.toString(),
                        file.name,
                        content,
                        host,
                        recordHome = false,
                    )
                }
            }

            override fun copyFile(file: VFile, content: ContentInfo?) {
                // Enter the tab's normal copy flow with a node source: the user
                // picks the destination by navigating, then pastes there.
                copyMoveState = CopyMoveState(
                    sources = listOf(
                        CopyMoveSource(
                            node = file,
                            name = content?.name?.takeIf { it.isNotBlank() } ?: file.name,
                        )
                    ),
                    isCopy = true,
                    targetPath = currentTab().path,
                )
            }

            override fun shareFile(file: VFile, content: ContentInfo?) {
                io.furryr.file.ui.util.shareFile(context, file, content)
            }

            override fun reportError(error: Throwable) {
                Toast.makeText(context, error.message ?: "Renderer failed", Toast.LENGTH_SHORT).show()
            }

            override fun requestClose() {
                closeWeb(id)
            }
        }
        return host
    }

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

    fun toggleHomeBookmark(entry: HomeEntry) {
        HomeStore.toggleBookmark(entry)
        homeVersion++
    }

    fun currentBookmarkEntry(): HomeEntry? {
        val tab = currentTab()
        if (editorSessions.containsKey(tab.id)) return null
        val pending = pendingOf(tab.id)
        val path = pending?.path ?: tab.path
        // A page is bookmarkable even when it was opened from home; only the
        // directory branch needs a real location. The session knows whether its
        // document is a live page or a projected file.
        val session = webSessions[tab.id]
        if (session != null) {
            val address = session.address
            val kind = if (session.isRemote) HomeEntryKind.WEB else HomeEntryKind.FILE
            return HomeEntry(kind, address, tab.webTitle ?: address, address)
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

    /**
     * Go to [address] in [tabId]: the provider decides what it is.
     *
     * A folder becomes the tab's location (the loader lists it), a file goes
     * through the same opener/renderer table a directory row uses. This is the
     * single entry for a typed address, a home row and a history stop — nothing
     * here reads a scheme, and no caller needs a second "open a URL" path.
     */
    fun openAddress(address: String, tabId: String = currentTab().id) {
        if (guardEditor(tabId) { openAddress(address, tabId) }) return
        if (tabs.none { it.id == tabId }) return
        val parsed = LocationAddress.parse(address)
        scope.launch {
            val node = withContext(Dispatchers.IO) { nodeService.resolve(parsed).getOrNull() }
            when (node) {
                // A view level already resolved to a node: keep it, so an
                // archive root is not re-opened from its path.
                is VFolder -> if (parsed.isViewed) navigateToFolder(node, tabId) else navigateTo(address, tabId)
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

    /** Show the home page, even when a page opened from home is still active. */
    fun goHome() {
        if (guardEditor(currentTab().id) { goHome() }) return
        val tab = currentTab()
        if (!isHomePath(tab.path)) {
            // Empty-address navigation supersedes a load still in flight.
            pendingNavigations.remove(tab.id)
            // navigateTo records the page URL (if one is active) in history and
            // closes its renderer before adopting the home location.
            navigateTo(HomePath)
            return
        }
        // A page opened from home sits on top of the home location.
        if (webSessions.containsKey(tab.id)) closeWeb(tab.id)
        replaceCurrent(tab.copy(folder = null, webUrl = null, webTitle = null))
    }

    /** Reveal [address] selected inside [parent], the jump-dialog behaviour. */
    fun selectInParent(parent: VFolder, address: String) {
        val tabId = currentTab().id
        if (parent.path.toString() == currentTab().path) {
            closeWeb(tabId)
            replaceCurrent(
                currentTab().copy(
                    selectedPaths = setOf(address),
                    lastSelectedIndex = currentEntries.indexOfFirst { it.path == address },
                ),
            )
            pendingScrollPath = address
            return
        }
        navigateTo(parent.path.toString())
        highlightPath = address
        highlightDirectory = parent.path.toString()
        highlightRefreshToken = null
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
        if (guardEditor(currentTab().id) { jumpTo(raw) }) return
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
        val address = LocationAddress.parse(input)
        scope.launch {
            val node = withContext(Dispatchers.IO) { nodeService.resolve(address).getOrNull() }
            when (node) {
                is VFolder -> {
                    if (address.isViewed) navigateToFolder(node, tabId) else navigateTo(input, tabId)
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
                    LocationAddress.isAddress(input) -> navigateTo(input, record = false)
                    else -> BrowseAddress.normalize(input)?.let { url -> openAddress(url) }
                        ?: openAddress(SearchSettings.searchUrl(context, input))
                }
            }
        }
    }

    /**
     * Restore [tab] at [address] after a history move.
     *
     * The address decides: a folder is handed to the tab's loader, a file — a
     * page, an archive, anything else — goes through the same route table a
     * directory row uses. [folderHint] keeps a container view's node, so
     * restoring an archive stop does not re-open the archive.
     */
    fun restoreStop(tab: FileTab, address: String, folderHint: VFolder?) {
        if (isHomePath(address)) {
            replaceCurrent(tab.copy(path = address, folder = null))
            return
        }
        val parsed = LocationAddress.parse(address)
        scope.launch {
            val node = withContext(Dispatchers.IO) { nodeService.resolve(parsed).getOrNull() }
            when (node) {
                is VFolder -> pendingNavigations[tab.id] = tab.copy(
                    path = address,
                    // A plain provider directory needs no node hint. Keeping a
                    // stale one would make the tab look like a read-only view
                    // and disable copy/share after a history move.
                    folder = if (parsed.isViewed) node
                    else folderHint?.takeIf { it !is Folder },
                )
                is VFile -> {
                    replaceCurrent(tab)
                    openVFile(node, address, node.name, recordHome = false, pushHistory = false)
                }
                // A miss keeps the address: the loader shows the error state.
                null -> pendingNavigations[tab.id] = tab.copy(path = address, folder = folderHint)
            }
        }
    }

    /**
     * Make stop [index] of [stops] the tab's location.
     *
     * The stacks are rebuilt around it, so a jump of any distance is the same
     * operation as one step. A live page that already holds the target
     * retraces it inside the WebView — its bytes are cached there — and
     * anything else is restored through the route table a row uses.
     */
    fun moveToStop(tab: FileTab, stops: List<HistoryStop>, index: Int) {
        val target = stops.getOrNull(index) ?: return
        if (target.current) return
        val session = webSessions[tab.id]
        val stacks = stacksAt(tab, stops, index)
        // Only a page address can be in the WebView's list, so a folder or an
        // edited file misses on its own.
        if (session != null && session.retrace(target.path)) {
            replaceCurrent(
                stacks.copy(webUrl = target.path, webTitle = pageTitles[target.path])
            )
            return
        }
        clearLeavePrompt(tab.id)
        // The WebView goes away with the location it was showing; the target
        // decides what draws the tab from here on.
        webSessions.remove(tab.id)?.close()
        restoreStop(stacks.copy(webUrl = null, webTitle = null), target.path, target.folder)
    }

    fun goBack() {
        val tab = currentTab()
        // The editor is an overlay: Back dismisses it, and the same Back then
        // restores the location it covered (the page it was opened from, its
        // folder, or Home) — that location was recorded when the editor opened.
        if (editorSessions.containsKey(tab.id)) {
            guardEditor(tab.id) { goBack() }
            return
        }
        if (pendingNavigations[tab.id] != null) return
        val stops = historyStops(tab)
        val index = stops.indexOfFirst { it.current }
        if (index > 0) {
            moveToStop(tab, stops, index - 1)
            return
        }
        // Nothing behind this stop. A page still has the location it was opened
        // from underneath it: dropping the page is that undo, and the page stays
        // reachable forward, exactly as a browser does.
        val page = tab.webUrl ?: return
        webSessions.remove(tab.id)?.close()
        replaceCurrent(
            tab.copy(
                forwardStack = listOf(page) + tab.forwardStack,
                folderForwardStack = listOf<VFolder?>(null) + tab.folderForwardStack,
                webUrl = null,
                webTitle = null,
            )
        )
    }

    fun goForward() {
        if (guardEditor(currentTab().id) { goForward() }) return
        val tab = currentTab()
        if (pendingNavigations[tab.id] != null) return
        val stops = historyStops(tab)
        val index = stops.indexOfFirst { it.current }
        if (index < 0) return
        moveToStop(tab, stops, index + 1)
    }

    /** Select a stop in the history panel; the same move as Back or Forward. */
    fun jumpToHistory(index: Int) {
        if (guardEditor(currentTab().id) { jumpToHistory(index) }) return
        val tab = currentTab()
        moveToStop(tab, historyStops(tab), index)
    }

    fun goParent() {
        val editingTab = currentTab()
        val editor = editorSessions[editingTab.id]
        if (editor != null) {
            // Up means the folder that owns the edited file, not the folder
            // the tab happened to show before the file was opened.
            val parent = editor.parentFolder ?: return
            if (guardEditor(editingTab.id) {
                    // A provider folder is addressed by its path; only a view
                    // folder needs its node kept as a hint.
                    if (parent is Folder) navigateTo(parent.path.toString(), editingTab.id)
                    else navigateToFolder(parent, editingTab.id)
                }) return
        } else if (guardEditor(editingTab.id) { goParent() }) {
            return
        }
        val tab = currentTab()
        val pending = pendingOf(tab.id)
        val activePath = pending?.path ?: tab.path
        if (isHomePath(activePath)) return
        val activeFolder = pending?.folder ?: tab.folder
        if (activeFolder != null) {
            val parent = activeFolder.parent ?: return
            pendingNavigations.remove(tab.id)
            highlightPath = null
            pendingScrollPath = null
            if (parent is Folder) navigateTo(parent.path.toString()) else navigateToFolder(parent)
            return
        }
        val parent = Path.parse(activePath).parent()?.toString() ?: return
        pendingNavigations.remove(tab.id)
        highlightPath = null
        pendingScrollPath = null
        navigateTo(parent)
    }

    fun refreshFileList(animate: Boolean = true) {
        refreshTrigger++
        if (animate) animationTrigger++
    }

    /**
     * Single refresh entry point for the toolbar/menu. The active renderer gets
     * first refusal; the transitional directory view falls back to its token.
     */
    fun refreshActive() {
        val renderer = rendererOf(currentTab().id)
        if (renderer?.refresh() == true) return
        refreshFileList(animate = true)
    }

    /** Capabilities of whatever owns the current tab: renderer or directory. */
    fun currentCapabilities(): RenderCapabilities {
        val tab = currentTab()
        val pending = pendingOf(tab.id)
        val activePath = pending?.path ?: tab.path
        val folder = pending?.folder ?: tab.folder
        val renderer = rendererOf(tab.id)
        if (renderer != null) {
            return renderer.capabilities.copy(
                // History belongs to the tab. A renderer that can dismiss
                // itself (a page or the editor) also makes Back available.
                canGoBack = tab.backStack.isNotEmpty() || renderer.canDismiss,
                canGoForward = tab.forwardStack.isNotEmpty(),
            )
        }
        if (isHomePath(activePath)) {
            return RenderCapabilities(
                canGoBack = tab.backStack.isNotEmpty(),
                canGoForward = tab.forwardStack.isNotEmpty(),
                canGoParent = false,
                canRefresh = false,
            )
        }
        val canParent = folder?.parent != null ||
            (folder == null && Path.parse(activePath).parent() != null)
        return RenderCapabilities(
            canGoBack = tab.backStack.isNotEmpty(),
            canGoForward = tab.forwardStack.isNotEmpty(),
            canGoParent = canParent,
            canRefresh = true,
            canCreate = directoryCapabilities.canCreate,
            canTerminal = directoryCapabilities.canOpenTerminal,
        )
    }

    val handleHereRequest by rememberUpdatedState(newValue = { request: HereNavigation.Request ->
        val tab = currentTab()
        clearLeavePrompt(tab.id)
        webSessions.remove(tab.id)?.close()
        // A here request supersedes an in-flight navigation in the current tab.
        pendingNavigations.remove(tab.id)
        filterText = ""
        searchMode = false
        contextMenuEntry = null
        showTerminalSheet = false
        highlightPath = request.selectedPath
        highlightDirectory = request.directory
        pendingScrollPath = request.selectedPath
        // Re-entering the same directory is not a step; anything else is.
        val from = if (tab.path == request.directory) tab else recordCurrentHistory(tab)
        val destination = from.copy(
            path = request.directory,
            folder = null,
            webUrl = null,
            webTitle = null,
            selectedPaths = emptySet(),
            lastSelectedIndex = -1,
        )
        if (tab.path == request.directory) replaceCurrent(destination)
        else pendingNavigations[destination.id] = destination
        // Refresh even within the same directory so newly created entries can
        // be selected once the matching asynchronous listing is committed.
        refreshFileList()
        highlightRefreshToken = refreshTrigger
    })
    fun dispatchHere(request: HereNavigation.Request) {
        val id = currentTab().id
        if (guardEditor(id) { pendingHereRequest = request }) return
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

    // Renderer sessions are owned per tab and survive tab switches.
    DisposableEffect(Unit) {
        onDispose {
            webSessions.values.forEach { it.close() }
            webSessions.clear()
            editorSessions.values.forEach { it.close() }
            editorSessions.clear()
        }
    }

    val activeRenderer = rendererOf(currentTab().id)
    val activeEditor = editorSessions[currentTab().id]
    // Block reads of remote HTTP(S) handles (archive index, proxy descriptor)
    // publish their progress here; the top strip below mirrors page loading.
    val remoteTransfers by WebTransfers.progress.collectAsState()
    val homeRecent = remember(homeVersion) { HomeStore.recent() }
    val homeBookmarks = remember(homeVersion) { HomeStore.bookmarks() }
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
        webSessions.remove(closing.id)?.close()
        editorSessions.remove(closing.id)?.close()
        pendingNavigations.remove(closing.id)
        selectedTabIndex = selectedTabIndex.coerceAtMost(tabs.lastIndex)
    }

    fun closeTab(index: Int) {
        if (tabs.size <= 1) return
        // guardEditor returns false when the tab has no editor; the close must
        // still run in that case (web pages, home, plain directories).
        if (!guardEditor(tabs[index].id) { closeTabNow(index) }) closeTabNow(index)
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
            parsed.scheme == "container" && parsed.host == name
        }
        if (matches.isEmpty()) return
        matches.sortedDescending().forEach {
            val removed = tabs.removeAt(it)
            clearLeavePrompt(removed.id)
            webSessions.remove(removed.id)?.close()
            editorSessions.remove(removed.id)?.close()
            pendingNavigations.remove(removed.id)
        }
        if (tabs.isEmpty()) {
            tabs.add(FileTab(path = HomePath))
            selectedTabIndex = 0
        } else {
            selectedTabIndex = selectedTabIndex.coerceAtMost(tabs.lastIndex)
        }
    }

    /** The tab's directory as a node: the one path→node step operations use. */
    suspend fun folderOf(tab: FileTab): VFolder? =
        tab.folder ?: withContext(Dispatchers.IO) { Vfs.folder(tab.path).getOrNull() }

    /** A listed entry as a node of [folder]. */
    suspend fun nodeOf(folder: VFolder?, name: String): VNode? =
        if (folder == null) null else withContext(Dispatchers.IO) { folder.child(name).getOrNull() }

    fun selectedEntries(): List<FileEntry> = currentEntries.filter { it.path in currentTab().selectedPaths }

    fun clearSelection() {
        replaceCurrent(currentTab().copy(selectedPaths = emptySet(), lastSelectedIndex = -1))
    }

    fun toggleSelection(entry: FileEntry) {
        val tab = currentTab()
        val newPaths = if (entry.path in tab.selectedPaths) tab.selectedPaths - entry.path else tab.selectedPaths + entry.path
        replaceCurrent(tab.copy(selectedPaths = newPaths, lastSelectedIndex = currentEntries.indexOfFirst { it.path == entry.path }))
    }

    fun rangeSelectTo(entry: FileEntry) {
        val tab = currentTab()
        val index = currentEntries.indexOfFirst { it.path == entry.path }
        if (index < 0) return
        val start = if (tab.lastSelectedIndex >= 0) minOf(tab.lastSelectedIndex, index) else index
        val end = if (tab.lastSelectedIndex >= 0) maxOf(tab.lastSelectedIndex, index) else index
        replaceCurrent(tab.copy(selectedPaths = tab.selectedPaths + currentEntries.subList(start, end + 1).map { it.path }, lastSelectedIndex = index))
    }

    /** Turn the listed entries into nodes, then open the paste flow with them. */
    fun beginCopyMove(entries: List<FileEntry>, isCopy: Boolean) {
        if (entries.isEmpty()) return
        val tab = currentTab()
        val path = tab.path
        val hint = tab.folder
        scope.launch {
            val folder = hint ?: withContext(Dispatchers.IO) { Vfs.folder(path).getOrNull() }
            val nodes = entries.mapNotNull { nodeOf(folder, it.name) }
            if (nodes.isEmpty()) {
                Toast.makeText(context, context.getString(R.string.toast_failed), Toast.LENGTH_SHORT).show()
                return@launch
            }
            copyMoveState = CopyMoveState(
                sources = nodes.map { CopyMoveSource(it, it.name) },
                isCopy = isCopy,
                targetPath = path,
            )
        }
    }

    /** Create a child node in the tab's own folder node. */
    fun createEntry(name: String, directory: Boolean) {
        val tab = currentTab()
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val folder = folderOf(tab) ?: error("cannot create here")
                    if (directory) folder.mkdir(name).getOrThrow() else folder.create(name).getOrThrow()
                }
            }
            Toast.makeText(
                context,
                result.exceptionOrNull()?.message ?: context.getString(R.string.toast_created, name),
                Toast.LENGTH_SHORT
            ).show()
            showCreateDialog = false
            refreshFileList()
        }
    }

    fun executeEntryAction(entry: FileEntry, action: String) {
        contextMenuEntry = null
        val batchEntries = selectedEntries().ifEmpty { listOf(entry) }
        val currentPath = currentTab().path
        when (action) {
            "copy" -> beginCopyMove(batchEntries, isCopy = true)
            "move" -> beginCopyMove(batchEntries, isCopy = false)
            "rename" -> if (currentTab().selectedPaths.isNotEmpty()) showBatchRenameDialog = true else showRenameDialog = entry
            "delete" -> showDeleteConfirm = batchEntries
            "properties" -> showEntryInfo = entry
            "share" -> shareEntries(context, batchEntries)
        }
    }

    fun performCopyMove() {
        val state = copyMoveState ?: return
        val tab = currentTab()
        val targetDir = tab.path
        val folderHint = tab.folder
        showCopyMovePlaceDialog = false
        operationHidden = false
        operationJob?.cancel()
        operationJob = scope.launch {
            // The destination is a folder node; every source is a node too.
            val targetFolder = folderHint
                ?: withContext(Dispatchers.IO) { Vfs.folder(targetDir).getOrNull() }
            var failure: Throwable? = null
            operationProgress = OperationProgress(0, 0, state.sources.firstOrNull()?.name.orEmpty(), state.isCopy)
            var lastBytes = 0L
            var lastTime = System.currentTimeMillis()
            try {
                for (source in state.sources) {
                    ensureActive()
                    lastBytes = 0L
                    lastTime = System.currentTimeMillis()
                    operationProgress = OperationProgress(0, 0, source.name, state.isCopy)
                    // The target node comes first: created in the destination
                    // folder when missing, then handed to the copy.
                    val targetResult = withContext(Dispatchers.IO) {
                        runCatching {
                            val folder = targetFolder ?: error("cannot write to $targetDir")
                            if (source.node is VFolder) {
                                folder.destinationFolder(source.name).getOrThrow()
                            } else {
                                folder.destination(source.name).getOrThrow()
                            }
                        }
                    }
                    val target = targetResult.getOrElse {
                        failure = it
                        null
                    } ?: break
                    val flow = if (state.isCopy) {
                        Vfs.copyTo(source.node, target)
                    } else {
                        Vfs.moveTo(source.node, target)
                    }
                    flow.collect { progress ->
                        ensureActive()
                        if (progress.finished) {
                            if (progress.totalBytes > 0 && progress.copiedBytes < progress.totalBytes) {
                                failure = Exception("operation failed"); return@collect
                            }
                            return@collect
                        }
                        // Progress can arrive faster than any useful sample
                        // window; sample on a fixed period and keep the last
                        // measured rate in between, instead of reporting 0.
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
                            progress.totalBytes, progress.copiedBytes,
                            progress.currentName.ifBlank { source.name },
                            state.isCopy, speed
                        )
                    }
                    if (failure != null) break
                }
            } catch (e: CancellationException) {
                OperationNotification.cancel(context)
                throw e
            } catch (e: Exception) {
                // A read-only destination or a failing link must report through
                // the progress dialog, not escape into the app scope.
                failure = e
            }
            if (failure != null) {
                // Flow was cancelled/dropped by failure handler above
            }
            OperationNotification.cancel(context)
            operationTaskIds = null
            Toast.makeText(
                context,
                if (failure == null)
                    context.getString(if (state.isCopy) R.string.toast_copied else R.string.toast_moved, state.sourceName)
                else failure?.message ?: context.getString(R.string.toast_failed),
                Toast.LENGTH_SHORT
            ).show()
            copyMoveState = null
            operationProgress = null
            operationJob = null
            if (!state.isCopy && failure == null) clearSelection()
            refreshFileList()
        }
    }

    fun cancelCopyMove() {
        operationJob?.cancel()
        operationJob = null
        operationProgress = null
        copyMoveState = null
        showCopyMovePlaceDialog = false
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
            lastRefreshedTabIndex = selectedTabIndex
            highlightPath = null
            highlightDirectory = null
            highlightRefreshToken = null
            pendingScrollPath = null
            refreshFileList(animate = false)
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
        if (searchMode) {
            searchMode = false
            filterText = ""
        } else if (currentTab().selectedPaths.isNotEmpty()) {
            clearSelection()
        } else if (drawerState.isOpen) {
            scope.launch { drawerState.close() }
        } else if (pendingForCurrent() != null) {
            pendingNavigations.remove(currentTab().id)
            highlightPath = null
            pendingScrollPath = null
        } else if (currentTab().backStack.isNotEmpty() || activeRenderer?.canDismiss == true) {
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
                    navigateTo("container://$name/")
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
                val menuHost = renderHostFor(currentTab().id)
                val menuPath = pendingOf(currentTab().id)?.path ?: currentTab().path
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
                Column {
                    if (copyMoveState != null) {
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
                            if (searchMode) {
                                TextField(
                                    value = filterText,
                                    onValueChange = { filterText = it },
                                    singleLine = true,
                                    placeholder = {
                                        if (filterText.isEmpty()) {
                                            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                                        }
                                    },
                                    trailingIcon = {
                                        IconButton(onClick = { searchMode = false; filterText = "" }) {
                                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_close_search))
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    textStyle = MaterialTheme.typography.bodyLarge,
                                    colors = TextFieldDefaults.colors(
                                        focusedContainerColor = Color.Transparent,
                                        unfocusedContainerColor = Color.Transparent,
                                        focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                                        unfocusedIndicatorColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                    )
                                )
                            } else {
                                val titleTab = currentTab()
                                val titlePending = pendingOf(titleTab.id)
                                val titleFolder = titlePending?.folder ?: titleTab.folder
                                val titleRenderer = rendererOf(titleTab.id)
                                val titleHome = titleRenderer == null &&
                                    isHomePath(titlePending?.path ?: titleTab.path)
                                // Renderers own their path bar: a page shows
                                // its title over the URL, Home shows its name
                                // but edits an empty address, and the editor
                                // shows its file plus encoding/cursor facts.
                                val rendererPath = titleRenderer?.pathBar(context)
                                    ?: if (titleHome) HomeRenderer.pathBar(context) else null
                                val titleDisplay = rendererPath?.title
                                    ?: titleFolder?.let { folderLabel(it) }
                                    ?: titleTab.path
                                // Any location that round-trips through the
                                // opener route is editable; a view without an
                                // address stays a read-only breadcrumb.
                                val titleEditable = rendererPath?.editable ?: (titleFolder == null ||
                                    runCatching {
                                        LocationAddress.format(titleFolder!!.path)
                                    }.getOrNull() != null)
                                TitleBlock(
                                    path = titlePending?.path ?: titleTab.path,
                                    info = if (titleRenderer == null && !titleHome) currentInfo else DirectoryStats(),
                                    onNavigate = { jumpTo(it) },
                                    onBounds = { titleBounds = it },
                                    displayPath = titleDisplay,
                                    editText = rendererPath?.editText ?: titleDisplay,
                                    editable = titleEditable,
                                    detail = rendererPath?.caption,
                                )
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.cd_menu))
                            }
                        },
                        actions = {
                            val menuItems = when {
                                activeRenderer != null -> activeRenderer.menuItems(context)
                                isHomePath(menuPath) -> HomeRenderer.menuItems(context, menuHost)
                                else -> DirectoryRenderer.menuItems(context, menuHost, caps.canRefresh)
                            }
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
                                        val bookmarkEntry = currentBookmarkEntry()
                                        if (bookmarkEntry != null) {
                                            val bookmarked = HomeStore.isBookmarked(
                                                bookmarkEntry.kind,
                                                bookmarkEntry.location,
                                            )
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        stringResource(
                                                            if (bookmarked) R.string.bookmark_remove
                                                            else R.string.bookmark_add,
                                                        )
                                                    )
                                                },
                                                leadingIcon = {
                                                    Icon(
                                                        if (bookmarked) Icons.Default.Star
                                                        else Icons.Default.StarBorder,
                                                        null,
                                                    )
                                                },
                                                onClick = {
                                                    toggleCurrentBookmark()
                                                    showMoreMenu = false
                                                }
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
                                                if (!guardEditor(currentTab().id, finish)) finish()
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
                val toolRenderer = rendererOf(toolTab.id)
                val toolPending = pendingOf(toolTab.id)
                val caps = currentCapabilities()
                // Every renderer reports its own write capability: web, editor,
                // Home and archive views all return canCreate = false, a writable
                // directory returns true. The toolbar stays type-agnostic.
                val canConfirmCopyMove = copyMoveState != null && toolPending == null && caps.canCreate
                BottomToolbar(
                    modifier = Modifier.onGloballyPositioned {
                        bottomBarHeightPx = it.size.height
                    },
                    canGoBack = caps.canGoBack,
                    canGoForward = caps.canGoForward,
                    canGoParent = caps.canGoParent,
                    onBack = ::goBack,
                    onForward = ::goForward,
                    onCreateFile = { if (caps.canCreate) showCreateDialog = true },
                    canCreate = caps.canCreate,
                    onTerminal = {
                        if (caps.canTerminal || hasTerminalSessions) {
                            showTerminalSheet = true
                        }
                    },
                    canOpenTerminal = caps.canTerminal || hasTerminalSessions,
                    onParent = ::goParent,
                    copyMoveActive = copyMoveState != null,
                    canConfirmCopyMove = canConfirmCopyMove,
                    onCopyMoveConfirm = {
                        if (canConfirmCopyMove) showCopyMovePlaceDialog = true
                    },
                    onCopyMoveCancel = ::cancelCopyMove,
                    selectionMode = toolRenderer == null &&
                        toolTab.selectedPaths.isNotEmpty(),
                    onSelectAll = {
                        replaceCurrent(currentTab().copy(selectedPaths = currentEntries.map { it.path }.toSet()))
                    },
                    onInvertSelection = {
                        val tab = currentTab()
                        replaceCurrent(tab.copy(selectedPaths = currentEntries.map { it.path }.filterNot { it in tab.selectedPaths }.toSet()))
                    },
                    onSelectSameType = {
                        val selected = selectedEntries()
                        if (selected.isNotEmpty()) {
                            val hasDir = selected.any { it.isDirectory }
                            val exts = selected.filter { !it.isDirectory }.map { it.name.substringAfterLast('.', "") }.distinct()
                            replaceCurrent(currentTab().copy(selectedPaths = currentEntries.filter { entry ->
                                if (entry.isDirectory) hasDir
                                else entry.name.substringAfterLast('.', "") in exts
                            }.map { it.path }.toSet()))
                        }
                    },
                    onCancelSelection = ::clearSelection,
                    onShare = { shareEntries(context, selectedEntries()) },
                    onHistoryDrag = { if (!showHistory) historyDragPx = it },
                    onHistoryDragEnd = { revealed ->
                        historyDragPx = 0f
                        if (!showHistory && revealed >= historyRevealThresholdPx) {
                            showHistory = true
                        }
                    },
                    canShare = toolTab.folder == null && toolTab.selectedPaths.isNotEmpty() &&
                        selectedEntries().all { !it.isDirectory }
                )
            }
        ) { padding ->
            Column(modifier = Modifier.padding(padding)) {
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
                val listPending = pendingOf(tab.id)
                val activePath = listPending?.path ?: tab.path
                val activeFolder = listPending?.folder ?: tab.folder
                when {
                    // The live renderer owns the tab view. It decides how its
                    // document/page is drawn; the shell only supplies the slot.
                    activeRenderer != null -> activeRenderer.Content(
                        darkTheme = darkTheme,
                        modifier = Modifier.fillMaxSize(),
                    )
                    isHomePath(activePath) -> HomeScreen(
                        recent = homeRecent,
                        bookmarks = homeBookmarks,
                        onOpen = ::openHomeEntry,
                        onToggleBookmark = ::toggleHomeBookmark,
                        onRemoveRecent = {
                            HomeStore.removeRecent(it.key)
                            homeVersion++
                        },
                        onRemoveBookmark = {
                            HomeStore.removeBookmark(it.key)
                            homeVersion++
                        },
                        onClearRecent = {
                            HomeStore.clearRecent()
                            homeVersion++
                        },
                        onClearBookmarks = {
                            HomeStore.clearBookmarks()
                            homeVersion++
                        },
                        filter = if (searchMode) filterText else "",
                        opening = nodeOpeningTab == tab.id,
                        onCancelOpening = ::cancelNodeOpening,
                        modifier = Modifier.fillMaxSize(),
                    )
                    else -> FileListScreen(
                    path = activePath,
                    committedPath = tab.path,
                    folderOverride = activeFolder,
                    onNavigationLoaded = { loadedPath ->
                        pendingNavigations[tab.id]?.takeIf { it.path == loadedPath }?.let {
                            replaceTab(it)
                            pendingNavigations.remove(tab.id)
                        }
                    },
                    onNavigationCancelled = {
                        pendingNavigations.remove(tab.id)
                        highlightPath = null
                        pendingScrollPath = null
                    },
                    refreshToken = refreshTrigger,
                    animationToken = animationTrigger,
                    sortMode = sortMode,
                    sortReverse = sortReverse,
                    filterText = filterText,
                    scrollToPath = pendingScrollPath,
                    onScrollConsumed = { pendingScrollPath = null },
                    onInfoChange = { currentInfo = it },
                    onCapabilitiesChange = {
                        directoryCapabilities = it
                        if (!it.canCreate) showCreateDialog = false
                    },
                    onEntriesChange = entriesChanged@{ loadedPath, loadedRefreshToken, entries ->
                        val curTab = currentTab()
                        if (loadedPath != curTab.path) return@entriesChanged
                        if (highlightDirectory == loadedPath &&
                            (highlightRefreshToken ?: Int.MIN_VALUE) > loadedRefreshToken) return@entriesChanged
                        currentEntries = entries
                        var newPaths = curTab.selectedPaths intersect entries.map { it.path }.toSet()
                        var selectionIndex = curTab.lastSelectedIndex
                        if ((highlightPath != null || highlightDirectory != null) &&
                            (highlightDirectory == null || highlightDirectory == loadedPath)) {
                            val found = entries.any { it.path == highlightPath }
                            if (found) {
                                newPaths = newPaths + highlightPath!!
                                selectionIndex = entries.indexOfFirst { it.path == highlightPath }
                            }
                            highlightPath = null
                            highlightDirectory = null
                            highlightRefreshToken = null
                        }
                        replaceCurrent(curTab.copy(
                            selectedPaths = newPaths,
                            lastSelectedIndex = selectionIndex,
                        ))
                    },
                    onDirectoryClick = ::navigateTo,
                    onFileClick = { openFile(context, it) },
                    onDirectoryNodeClick = if (activeFolder != null) {
                        { folder, _ -> navigateToFolder(folder) }
                    } else {
                        null
                    },
                    onFileNodeClick = { file, entry ->
                        openNode(file, entry)
                    },
                    opening = nodeOpeningTab == currentTab().id,
                    onCancelOpening = ::cancelNodeOpening,
                    onLongPress = { contextMenuEntry = it },
                    selectionMode = copyMoveState == null && currentTab().selectedPaths.isNotEmpty(),
                    selectedPaths = currentTab().selectedPaths,
                    onToggleSelection = if (copyMoveState == null) ::toggleSelection else {{}},
                    onRangeSelect = if (copyMoveState == null) ::rangeSelectTo else {{}},
                    onFileDragStart = {
                        terminalDragGeneration++
                        terminalDropAccepted = false
                        showTerminalSheet = true
                    },
                    onFileDragEnd = {
                        val generation = terminalDragGeneration
                        scope.launch {
                            // Give the platform drop (ACTION_DROP) a chance to arrive
                            // before deciding whether this drag was cancelled.
                            delay(150)
                            if (generation == terminalDragGeneration && !terminalDropAccepted) {
                                showTerminalSheet = false
                            }
                        }
                    },
                    )
                }
            }
        }
    }

    if (showInfoDialog) {
        InfoDialog(
            path = currentTab().path,
            info = currentInfo,
            onDismiss = { showInfoDialog = false }
        )
    }

    if (showCreateDialog && currentCapabilities().canCreate) {
        CreateDialog(
            initialPath = currentTab().path,
            onDismiss = { showCreateDialog = false },
            onCreateFile = { name -> createEntry(name, directory = false) },
            onCreateFolder = { name -> createEntry(name, directory = true) }
        )
    }

    contextMenuEntry?.let { entry ->
        val sharesForMenu = selectedEntries().ifEmpty { listOf(entry) }
        ContextMenu(
            entry = entry,
            onDismiss = { contextMenuEntry = null },
            onCopy = { executeEntryAction(entry, "copy") },
            onMove = { executeEntryAction(entry, "move") },
            onRename = { executeEntryAction(entry, "rename") },
            onDelete = { executeEntryAction(entry, "delete") },
            onProperties = { executeEntryAction(entry, "properties") },
            onShare = { executeEntryAction(entry, "share") },
            canShare = currentTab().folder == null && sharesForMenu.all { !it.isDirectory },
            canModify = currentTab().folder == null
        )
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

    if (showCopyMovePlaceDialog && copyMoveState != null) {
        CopyMovePlaceDialog(
            isCopy = copyMoveState!!.isCopy,
            sourceName = copyMoveState!!.sourceName,
            // The destination is the folder open now, which is where
            // performCopyMove() writes; the state's targetPath is only the
            // folder the copy started from.
            targetPath = currentTab().path,
            onDismiss = { showCopyMovePlaceDialog = false },
            onConfirm = ::performCopyMove
        )
    }

    if (operationProgress != null && !operationHidden) {
        val opState = copyMoveState
        OperationProgressDialog(
            progress = operationProgress!!,
            sourceParent = opState?.sourceParent ?: "",
            targetPath = opState?.targetPath ?: "",
            onHide = {
                operationHidden = true
                OperationNotification.show(context, operationProgress!!.isCopy,
                    (opState?.sourceParent ?: "").ifBlank { operationProgress!!.currentName })
            },
            onCancel = {
                OperationNotification.cancel(context)
                operationJob?.cancel()
                operationJob = null
                operationProgress = null
                copyMoveState = null
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

    val allHistory = historyStops()
    // Home is a landing marker, not a navigation stop: hide it from the
    // sheet, but keep the full list for index-based history navigation.
    val visibleHistory = allHistory.filterNot { isHomePath(it.path) }
    HistorySheet(
        visible = showHistory,
        dragPx = historyDragPx,
        stops = visibleHistory,
        bottomInset = with(LocalDensity.current) { bottomBarHeightPx.toDp() },
        onSelect = { stop ->
            val index = allHistory.indexOfFirst { it === stop }
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
        // FIXME: editor-specific terminal cwd belongs to the renderer's generic
        // session contract, not an activeEditor lookup in the shell.
        // The editor opened in this tab asks for a shell in the folder that
        // owns the file; the directory behind it is only the fallback.
        currentPath = activeEditor
            ?.takeIf { it.capabilities.canTerminal }
            ?.parentAddress
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

    if (showSortDialog) {
        SortDialog(
            currentMode = sortMode,
            currentReverse = sortReverse,
            onDismiss = { showSortDialog = false },
            onConfirm = { mode, reverse ->
                sortMode = mode
                sortReverse = reverse
                showSortDialog = false
            }
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
