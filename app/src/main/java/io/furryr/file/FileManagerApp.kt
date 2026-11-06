package io.furryr.file
import io.furryr.file.model.*
import io.furryr.file.ui.components.*
import io.furryr.file.ui.screens.*
import io.furryr.file.ui.util.*
import io.furryr.file.ui.state.rememberLocationCollections
import io.furryr.file.core.Path

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
import io.furryr.file.provider.LocationsRepository
import io.furryr.file.core.Folder
import io.furryr.file.core.VFolder
import io.furryr.file.core.VFile
import io.furryr.file.core.Vfs
import io.furryr.file.render.Openers
import io.furryr.file.render.RenderCapabilities
import io.furryr.file.render.RenderHost
import io.furryr.file.render.RenderTarget
import io.furryr.file.render.Renderers
import io.furryr.file.render.WebContent
import io.furryr.file.render.WebHistoryEntry
import io.furryr.file.render.WebRendererSession
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
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.AlertDialog
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FileManagerApp(
    darkTheme: Boolean = true,
    onToggleTheme: () -> Unit = {},
    terminalOpenRequest: Int = 0,
    containerErrorRequest: String? = null,
) {
    val context = LocalContext.current
    val tabs = remember { mutableStateListOf(FileTab(path = DefaultPath)) }
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var currentInfo by remember { mutableStateOf(DirectoryStats()) }
    var showInfoDialog by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var containerError by remember(containerErrorRequest) { mutableStateOf(containerErrorRequest) }
    var showMoreMenu by remember { mutableStateOf(false) }
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
    var showTerminalSheet by remember { mutableStateOf(false) }
    var terminalDropAccepted by remember { mutableStateOf(false) }
    var terminalDragGeneration by remember { mutableIntStateOf(0) }
    var showSessionManager by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
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

    fun folderLabel(folder: VFolder): String {
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

    var pendingNavigation by remember(selectedTabIndex, currentTab().path, currentTab().folder) { mutableStateOf<FileTab?>(null) }

    var directoryCapabilities by remember {
        mutableStateOf(DirectoryCapabilities())
    }

    fun replaceCurrent(tab: FileTab) {
        tabs[selectedTabIndex] = tab
    }

    fun updateTab(id: String, transform: (FileTab) -> FileTab) {
        val index = tabs.indexOfFirst { it.id == id }
        if (index >= 0) tabs[index] = transform(tabs[index])
    }

    fun closeWeb(id: String) {
        webSessions.remove(id)?.close()
        updateTab(id) { it.copy(webUrl = null, webTitle = null) }
    }

    fun showWeb(id: String, session: WebRendererSession, url: String) {
        webSessions.remove(id)?.close()
        webSessions[id] = session
        updateTab(id) { it.copy(webUrl = url, webTitle = null) }
    }

    fun currentWebUrl(tab: FileTab): String? =
        webSessions[tab.id]?.address?.takeIf { BrowseAddress.isWebUrl(it) }
            ?: tab.webUrl?.takeIf { BrowseAddress.isWebUrl(it) }

    /** Push the current location onto the tab history before navigation. */
    fun recordCurrentHistory(tab: FileTab): FileTab {
        val url = currentWebUrl(tab)
        return tab.copy(
            backStack = tab.backStack + (url ?: tab.path),
            folderBackStack = tab.folderBackStack + (if (url != null) null else tab.folder),
            forwardStack = emptyList(),
            folderForwardStack = emptyList(),
        )
    }

    fun navigateTo(path: String) {
        val tab = currentTab()
        if (path == tab.path || pendingNavigation != null) return
        val next = recordCurrentHistory(tab).copy(
            path = path,
            folder = null,
            webUrl = null,
            webTitle = null,
        )
        closeWeb(tab.id)
        highlightPath = null
        highlightDirectory = null
        highlightRefreshToken = null
        pendingScrollPath = null
        pendingNavigation = next
    }

    fun navigateToFolder(folder: VFolder) {
        val tab = currentTab()
        if (pendingNavigation != null) return
        val next = recordCurrentHistory(tab).copy(
            path = folder.name,
            folder = folder,
            webUrl = null,
            webTitle = null,
            selectedPaths = emptySet(),
            lastSelectedIndex = -1,
        )
        closeWeb(tab.id)
        highlightPath = null
        highlightDirectory = null
        highlightRefreshToken = null
        pendingScrollPath = null
        pendingNavigation = next
    }

    fun renderHostFor(id: String): RenderHost {
        val hostScope = scope
        return object : RenderHost {
            override val scope: CoroutineScope get() = hostScope

            override fun navigate(node: io.furryr.file.core.VNode, rendererId: String?) {
                if (node is VFolder) {
                    webSessions.remove(id)?.close()
                    navigateToFolder(node)
                }
            }

            override fun updateTitle(title: String?) {
                // A late title from a page whose tab already left the web view
                // must not rename the directory tab.
                if (webSessions[id] == null) return
                updateTab(id) { it.copy(webTitle = title) }
            }

            override fun updateCapabilities(capabilities: RenderCapabilities) = Unit

            override fun reportError(error: Throwable) {
                Toast.makeText(context, error.message ?: "Renderer failed", Toast.LENGTH_SHORT).show()
            }

            override fun requestClose() {
                closeWeb(id)
            }
        }
    }

    fun openNode(file: VFile, entry: FileEntry, insideContainer: Boolean) {
        val tabId = currentTab().id
        scope.launch {
            val content = withContext(Dispatchers.IO) { file.describe().getOrNull() }
            val opener = content?.let { Openers.resolve(file, it) }
            if (opener == null) {
                val target = content?.let { RenderTarget.Node(file, it) }
                val renderer = target?.let { Renderers.resolve(it) }
                if (target != null && renderer != null) {
                    val session = withContext(Dispatchers.IO) {
                        renderer.create(target, renderHostFor(tabId))
                    }.getOrNull()
                    if (session is WebRendererSession) {
                        showWeb(tabId, session, entry.path)
                        return@launch
                    }
                    session?.close()
                }
                if (insideContainer) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.toast_cannot_open, entry.name),
                        Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    openFile(context, entry)
                }
                return@launch
            }
            val result = withContext(Dispatchers.IO) { opener.open(file) }
            val node = result.getOrNull()
            if (node is VFolder) {
                navigateToFolder(node)
            } else {
                Toast.makeText(
                    context,
                    result.exceptionOrNull()?.message
                        ?: context.getString(R.string.toast_cannot_open, entry.name),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    // Inline path submit: directories navigate, files open their parent with the
    // entry selected, matching the old jump dialog behaviour.
    fun openUrl(url: String, recordHistory: Boolean = true) {
        val tabId = currentTab().id
        // Reuse the tab's URL WebView when it already exists: only the page
        // changes, so the renderer does not pay WebView start-up again.
        val existing = webSessions[tabId]
        if (existing != null && existing.document == null) {
            existing.load(url)
            updateTab(tabId) { it.copy(webUrl = url, webTitle = null) }
            return
        }
        if (recordHistory) updateTab(tabId) { recordCurrentHistory(it) }
        val target = RenderTarget.Url(url)
        val factory = Renderers.resolve(target) ?: return
        scope.launch {
            val session = withContext(Dispatchers.IO) {
                factory.create(target, renderHostFor(tabId))
            }.getOrNull()
            if (session is WebRendererSession) {
                showWeb(tabId, session, url)
            } else {
                session?.close()
            }
        }
    }

    fun jumpTo(raw: String) {
        val input = raw.trim()
        if (input.isEmpty()) return
        if (BrowseAddress.isWebUrl(input)) {
            openUrl(input)
            return
        }
        scope.launch {
            val stat = withContext(Dispatchers.IO) { Vfs.stat(input).getOrNull() }
            when {
                stat != null && stat.exists && !stat.isDirectory -> {
                    val parent = Vfs.navigate(input, "..")
                    if (parent != null) {
                        if (parent == currentTab().path) {
                            closeWeb(currentTab().id)
                            replaceCurrent(currentTab().copy(
                                selectedPaths = setOf(input),
                                lastSelectedIndex = currentEntries.indexOfFirst { it.path == input }
                            ))
                            pendingScrollPath = input
                        } else {
                            navigateTo(parent)
                            highlightPath = input
                            highlightDirectory = parent
                            highlightRefreshToken = null
                            pendingScrollPath = input
                        }
                    }
                }
                stat != null && stat.exists -> navigateTo(input)
                // Bare host: only after the path check, so a real file named
                // like a domain never turns into a web request.
                else -> BrowseAddress.normalize(input)?.let { url ->
                    openUrl(url)
                } ?: openUrl(SearchSettings.searchUrl(context, input))
            }
        }
    }

    fun goBack() {
        val tab = currentTab()
        val session = webSessions[tab.id]
        if (session != null) {
            if (session.onBack()) return
            // WebView history exhausted: leave the page and consume the tab's
            // previous history entry (the folder the page was opened from).
            val url = currentWebUrl(tab)
            val target = tab.backStack.lastOrNull()
            val targetFolder = tab.folderBackStack.lastOrNull()
            val forwarded = if (url != null) listOf(url) + tab.forwardStack else tab.forwardStack
            val forwardedFolders =
                if (url != null) listOf<VFolder?>(null) + tab.folderForwardStack else tab.folderForwardStack
            closeWeb(tab.id)
            if (target == null) {
                updateTab(tab.id) {
                    it.copy(
                        forwardStack = forwarded,
                        folderForwardStack = forwardedFolders,
                    )
                }
                return
            }
            val next = tab.copy(
                path = if (BrowseAddress.isWebUrl(target)) tab.path else target,
                folder = if (BrowseAddress.isWebUrl(target)) null else targetFolder,
                backStack = tab.backStack.dropLast(1),
                folderBackStack = tab.folderBackStack.dropLast(1),
                forwardStack = forwarded,
                folderForwardStack = forwardedFolders,
                webUrl = null,
                webTitle = null,
            )
            if (BrowseAddress.isWebUrl(target)) {
                replaceCurrent(next)
                openUrl(target, recordHistory = false)
            } else {
                pendingNavigation = next
            }
            return
        }
        if (pendingNavigation != null) return
        val target = tab.backStack.lastOrNull() ?: return
        val next = tab.copy(
            backStack = tab.backStack.dropLast(1),
            folderBackStack = tab.folderBackStack.dropLast(1),
            forwardStack = listOf(tab.path) + tab.forwardStack,
            folderForwardStack = listOf(tab.folder) + tab.folderForwardStack,
            webUrl = null,
            webTitle = null,
        )
        if (BrowseAddress.isWebUrl(target)) {
            replaceCurrent(next)
            openUrl(target, recordHistory = false)
        } else {
            pendingNavigation = next.copy(
                path = target,
                folder = tab.folderBackStack.lastOrNull(),
            )
        }
    }

    fun goForward() {
        val tab = currentTab()
        val session = webSessions[tab.id]
        if (session != null) {
            if (session.goForward()) return
            return
        }
        if (pendingNavigation != null) return
        val target = tab.forwardStack.firstOrNull() ?: return
        val targetFolder = tab.folderForwardStack.firstOrNull()
        if (BrowseAddress.isWebUrl(target)) {
            replaceCurrent(
                tab.copy(
                    backStack = tab.backStack + tab.path,
                    folderBackStack = tab.folderBackStack + tab.folder,
                    forwardStack = tab.forwardStack.drop(1),
                    folderForwardStack = tab.folderForwardStack.drop(1),
                    webUrl = null,
                    webTitle = null,
                )
            )
            openUrl(target, recordHistory = false)
            return
        }
        pendingNavigation =
            tab.copy(
                path = target,
                folder = targetFolder,
                backStack = tab.backStack + tab.path,
                folderBackStack = tab.folderBackStack + tab.folder,
                forwardStack = tab.forwardStack.drop(1),
                folderForwardStack = tab.folderForwardStack.drop(1),
                webUrl = null,
                webTitle = null,
            )
    }

    /** Primary line for a history stop: the folder name, or the last path segment. */
    fun historyLabel(path: String, folder: VFolder?): String =
        folder?.name ?: Path.parse(path).name ?: path

    /**
     * Secondary line for a history stop: the full address. Folder views keep
     * their provider path plus nested segments, web pages keep the whole URL.
     */
    fun historyDetail(path: String, folder: VFolder?): String =
        if (folder != null) folderLabel(folder) else path

    /** Primary line for a page entry: its title, else the host, else the URL. */
    fun webHistoryLabel(entry: WebHistoryEntry): String =
        entry.title?.takeIf { it.isNotBlank() }
            ?: runCatching { java.net.URI(entry.address).host }.getOrNull()
                ?.takeIf { it.isNotBlank() }
            ?: entry.address

    fun historyStops(): List<HistoryStop> {
        val tab = currentTab()
        val session = webSessions[tab.id]
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
        if (session != null) {
            // The live page owns its own back/forward list; every URL the user
            // visited lives there, including in-page link navigations.
            val snapshot = session.historySnapshot()
            snapshot.entries.forEachIndexed { index, entry ->
                stops += HistoryStop(
                    path = entry.address,
                    label = webHistoryLabel(entry),
                    detail = entry.address,
                    isWeb = true,
                    current = index == snapshot.currentIndex,
                )
            }
        } else {
            val url = currentWebUrl(tab)
            if (url != null) {
                stops += HistoryStop(
                    path = url,
                    label = url,
                    detail = url,
                    isWeb = true,
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

    fun jumpToHistory(index: Int) {
        val stops = historyStops()
        if (index !in stops.indices) return
        val tab = currentTab()
        val session = webSessions[tab.id]
        val sessionStart = tab.backStack.size
        val sessionCount = session?.historySnapshot()?.entries?.size ?: 0
        if (session != null && index in sessionStart until sessionStart + sessionCount) {
            // Already inside the live page history: traverse it without
            // tearing the session down.
            session.jumpToHistory(index - sessionStart)
            return
        }
        val target = stops[index]
        webSessions.remove(tab.id)?.close()
        // Page entries only exist while their session does, so the tab stacks
        // are rebuilt from the locations that surround them.
        val appStops = stops.filterIndexed { i, _ ->
            i < sessionStart || i >= sessionStart + sessionCount
        }
        val appIndex = if (index < sessionStart) index else index - sessionCount
        val back = appStops.take(appIndex)
        val forward = appStops.drop(appIndex + 1)
        val stacks = tab.copy(
            backStack = back.map { it.path },
            folderBackStack = back.map { it.folder },
            forwardStack = forward.map { it.path },
            folderForwardStack = forward.map { it.folder },
            webUrl = null,
            webTitle = null,
        )
        if (target.isWeb) {
            replaceCurrent(stacks.copy(path = tab.path))
            openUrl(target.path, recordHistory = false)
        } else {
            pendingNavigation = stacks.copy(path = target.path, folder = target.folder)
        }
    }

    fun goParent() {
        val tab = currentTab()
        val activeFolder = pendingNavigation?.folder ?: tab.folder
        if (activeFolder != null) {
            val parent = activeFolder.parent ?: return
            pendingNavigation = null
            highlightPath = null
            pendingScrollPath = null
            if (parent is Folder) navigateTo(parent.path.toString()) else navigateToFolder(parent)
            return
        }
        val path = pendingNavigation?.path ?: tab.path
        val parent = Vfs.navigate(path, "..") ?: return
        pendingNavigation = null
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
        val session = webSessions[currentTab().id]
        if (session?.refresh() == true) return
        refreshFileList(animate = true)
    }

    /** Capabilities of whatever owns the current tab: renderer or directory. */
    fun currentCapabilities(): RenderCapabilities {
        val tab = currentTab()
        val folder = pendingNavigation?.folder ?: tab.folder
        val canParent = folder?.parent != null ||
            (folder == null && Vfs.navigate(pendingNavigation?.path ?: tab.path, "..") != null)
        val session = webSessions[tab.id]
        if (session != null) {
            return session.capabilities.copy(
                // Back also leaves the page for the tab's previous location.
                canGoBack = session.canGoBack || tab.backStack.isNotEmpty(),
                canGoParent = canParent,
            )
        }
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
        webSessions.remove(tab.id)?.close()
        // A here request supersedes an in-flight navigation in the current tab.
        pendingNavigation = null
        filterText = ""
        searchMode = false
        contextMenuEntry = null
        showTerminalSheet = false
        highlightPath = request.selectedPath
        highlightDirectory = request.directory
        pendingScrollPath = request.selectedPath
        val destination = tab.copy(
            path = request.directory,
            folder = null,
            webUrl = null,
            webTitle = null,
            selectedPaths = emptySet(),
            lastSelectedIndex = -1,
            backStack = if (tab.path == request.directory) tab.backStack else tab.backStack + tab.path,
            folderBackStack = if (tab.path == request.directory) tab.folderBackStack else tab.folderBackStack + tab.folder,
            forwardStack = if (tab.path == request.directory) tab.forwardStack else emptyList(),
            folderForwardStack = if (tab.path == request.directory) tab.folderForwardStack else emptyList(),
        )
        if (tab.path == request.directory) replaceCurrent(destination)
        else pendingNavigation = destination
        // Refresh even within the same directory so newly created entries can
        // be selected once the matching asynchronous listing is committed.
        refreshFileList()
        highlightRefreshToken = refreshTrigger
    })
    val activityLifecycle = (context as? LifecycleOwner)?.lifecycle
    LaunchedEffect(activityLifecycle) {
        if (activityLifecycle != null) {
            activityLifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                HereNavigation.requests.collect { handleHereRequest(it) }
            }
        } else {
            HereNavigation.requests.collect { handleHereRequest(it) }
        }
    }

    // Renderer sessions are owned per tab and survive tab switches.
    DisposableEffect(Unit) {
        onDispose {
            webSessions.values.forEach { it.close() }
            webSessions.clear()
        }
    }

    val activeWeb = webSessions[currentTab().id]

    fun closeTab(index: Int) {
        if (tabs.size <= 1) return
        val closing = tabs.removeAt(index)
        webSessions.remove(closing.id)?.close()
        selectedTabIndex = selectedTabIndex.coerceAtMost(tabs.lastIndex)
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
            val parsed = Path.parse(tabs[index].path)
            parsed.scheme == "container" && parsed.host == name
        }
        if (matches.isEmpty()) return
        matches.sortedDescending().forEach {
            val removed = tabs.removeAt(it)
            webSessions.remove(removed.id)?.close()
        }
        if (tabs.isEmpty()) {
            tabs.add(FileTab(path = DefaultPath))
            selectedTabIndex = 0
        } else {
            selectedTabIndex = selectedTabIndex.coerceAtMost(tabs.lastIndex)
        }
    }

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

    fun executeEntryAction(entry: FileEntry, action: String) {
        contextMenuEntry = null
        val batchEntries = selectedEntries().ifEmpty { listOf(entry) }
        val currentPath = currentTab().path
        when (action) {
            "copy" -> copyMoveState = CopyMoveState(
                sources = batchEntries.map { CopyMoveSource(it.path, it.name) },
                isCopy = true,
                targetPath = currentPath
            )
            "move" -> copyMoveState = CopyMoveState(
                sources = batchEntries.map { CopyMoveSource(it.path, it.name) },
                isCopy = false,
                targetPath = currentPath
            )
            "rename" -> if (currentTab().selectedPaths.isNotEmpty()) showBatchRenameDialog = true else showRenameDialog = entry
            "delete" -> showDeleteConfirm = batchEntries
            "properties" -> showEntryInfo = entry
            "share" -> shareEntries(context, batchEntries)
        }
    }

    fun performCopyMove() {
        val state = copyMoveState ?: return
        val targetDir = currentTab().path
        showCopyMovePlaceDialog = false
        operationHidden = false
        operationJob?.cancel()
        operationJob = scope.launch {
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
                    val flow = if (state.isCopy) {
                        Vfs.copy(source.path, targetDir)
                    } else {
                        Vfs.move(source.path, targetDir)
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
        } else if (activeWeb != null) {
            // A page's own back list wins over directory navigation: the
            // gesture must retrace the pages the user visited, not climb out of
            // the folder the page was opened from.
            goBack()
        } else if (pendingNavigation != null) {
            pendingNavigation = null
            highlightPath = null
            pendingScrollPath = null
        } else {
            val currentPath = currentTab().path
            if (Vfs.navigate(currentPath, "..") != null) {
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
        // A WebView consumes horizontal scrolling itself, so the open-from-edge
        // gesture stays off there. An already open drawer keeps the gesture so
        // it can still be swiped shut.
        gesturesEnabled = activeWeb == null || drawerState.isOpen,
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
                    openUrl(url)
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
                val webLoading = activeWeb?.loading == true
                val webProgress = (activeWeb?.progress ?: 0) / 100f
                Column {
                    if (copyMoveState != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.dp)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    } else if (webLoading) {
                        // Same top strip as copy/move progress, so page loading
                        // never adds a second bar or re-measures the content.
                        LinearProgressIndicator(
                            progress = { webProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.dp),
                        )
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
                                val titleFolder = pendingNavigation?.folder ?: titleTab.folder
                                val titleWeb = webSessions[titleTab.id]
                                TitleBlock(
                                    path = titleTab.path,
                                    info = if (titleWeb == null) currentInfo else DirectoryStats(),
                                    onNavigate = { jumpTo(it) },
                                    onBounds = { titleBounds = it },
                                    displayPath = titleWeb?.address
                                        ?: titleFolder?.let { folderLabel(it) }
                                        ?: titleTab.path,
                                    editable = titleWeb != null || titleFolder == null,
                                )
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.cd_menu))
                            }
                        },
                        actions = {
                            Box {
                                IconButton(onClick = { showMoreMenu = true }) {
                                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.cd_more))
                                }
                                DropdownMenu(
                                    expanded = showMoreMenu,
                                    onDismissRequest = { showMoreMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.refresh)) },
                                        leadingIcon = { Icon(Icons.Default.Refresh, null) },
                                        enabled = caps.canRefresh,
                                        onClick = {
                                            refreshActive()
                                            showMoreMenu = false
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.filter_title)) },
                                        leadingIcon = { Icon(Icons.Default.FilterAlt, null) },
                                        onClick = {
                                            searchMode = !searchMode
                                            if (!searchMode) filterText = ""
                                            showMoreMenu = false
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.sort_title)) },
                                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.Sort, null) },
                                        onClick = {
                                            showSortDialog = true
                                            showMoreMenu = false
                                        }
                                    )
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
                                            (context as? Activity)?.finish()
                                            showMoreMenu = false
                                        }
                                    )
                                }
                            }
                        }
                    )
                }
            },
            bottomBar = {
                val toolTab = currentTab()
                val toolWeb = webSessions[toolTab.id]
                val caps = currentCapabilities()
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
                        if (caps.canTerminal) {
                            showTerminalSheet = true
                        }
                    },
                    canOpenTerminal = caps.canTerminal,
                    onParent = ::goParent,
                    copyMoveActive = copyMoveState != null,
                    onCopyMoveConfirm = { showCopyMovePlaceDialog = true },
                    onCopyMoveCancel = ::cancelCopyMove,
                    selectionMode = toolWeb == null && toolTab.selectedPaths.isNotEmpty(),
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
                                    tabs.add(FileTab(path = DefaultPath))
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
                val activeFolder = pendingNavigation?.folder ?: tab.folder
                if (activeWeb == null) {
                    FileListScreen(
                    path = pendingNavigation?.path ?: tab.path,
                    committedPath = tab.path,
                    folderOverride = activeFolder,
                    onNavigationLoaded = { loadedPath ->
                        pendingNavigation?.takeIf { it.path == loadedPath }?.let {
                            replaceCurrent(it)
                            pendingNavigation = null
                        }
                    },
                    onNavigationCancelled = {
                        pendingNavigation = null
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
                        openNode(file, entry, insideContainer = activeFolder != null)
                    },
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
                } else {
                    WebContent(activeWeb, Modifier.fillMaxSize())
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
            onCreateFile = { name ->
                val result = Vfs.createFile(currentTab().path, name)
                Toast.makeText(
                    context,
                    result.exceptionOrNull()?.message ?: context.getString(R.string.toast_created, name),
                    Toast.LENGTH_SHORT
                ).show()
                showCreateDialog = false
                refreshFileList()
            },
            onCreateFolder = { name ->
                val result = Vfs.createDirectory(currentTab().path, name)
                Toast.makeText(
                    context,
                    result.exceptionOrNull()?.message ?: context.getString(R.string.toast_created, name),
                    Toast.LENGTH_SHORT
                ).show()
                showCreateDialog = false
                refreshFileList()
            }
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
                scope.launch {
                    var failure: Throwable? = null
                    withContext(Dispatchers.IO) {
                        for (entry in targets) {
                            val result = runCatching { Vfs.delete(entry.path).getOrThrow() }
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
                val result = Vfs.rename(entry.path, newName)
                Toast.makeText(
                    context,
                    if (result.isSuccess) context.getString(R.string.toast_renamed, newName)
                    else result.exceptionOrNull()?.message ?: context.getString(R.string.toast_failed),
                    Toast.LENGTH_SHORT
                ).show()
                showRenameDialog = null
                refreshFileList()
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
                val failures = if (regex == null) {
                    listOf(IllegalArgumentException(context.getString(R.string.toast_failed)))
                } else {
                    entries.mapNotNull { entry ->
                        val newName = regex.replace(entry.name, replacement)
                        if (newName.isBlank() || newName == entry.name) null
                        else Vfs.rename(entry.path, newName).exceptionOrNull()
                    }
                }
                Toast.makeText(
                    context,
                    if (failures.isEmpty()) context.getString(R.string.toast_renamed, entries.size.toString())
                    else failures.first().message ?: context.getString(R.string.toast_failed),
                    Toast.LENGTH_SHORT
                ).show()
                showBatchRenameDialog = false
                if (failures.isEmpty()) clearSelection()
                refreshFileList()
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
            targetPath = copyMoveState!!.targetPath,
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

    HistorySheet(
        visible = showHistory,
        dragPx = historyDragPx,
        stops = historyStops(),
        bottomInset = with(LocalDensity.current) { bottomBarHeightPx.toDp() },
        onSelect = {
            jumpToHistory(it)
            showHistory = false
        },
        onDismiss = { showHistory = false },
    )

    TerminalSheet(
        visible = showTerminalSheet,
        onDismiss = { showTerminalSheet = false },
        onShow = { showTerminalSheet = true },
        onDropAccepted = { terminalDropAccepted = true },
        currentPath = currentTab().path
    )

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
