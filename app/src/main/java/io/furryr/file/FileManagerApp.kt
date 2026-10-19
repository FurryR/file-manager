package io.furryr.file
import io.furryr.file.model.*
import io.furryr.file.ui.components.*
import io.furryr.file.ui.screens.*
import io.furryr.file.ui.util.*
import io.furryr.file.util.Path
import io.furryr.file.archive.ArchiveProvider
import io.furryr.file.archive.ArchiveToolsActivity
import io.furryr.file.provider.remote.RemoteLocationsActivity
import io.furryr.file.tools.ToolboxActivity

import io.furryr.file.core.DaemonManager
import io.furryr.file.core.DaemonStatus
import io.furryr.file.agent.ContainerManager
import io.furryr.file.agent.ContainerBackend
import io.furryr.file.provider.DirectoryCapabilities
import io.furryr.file.agent.SessionManager
import io.furryr.file.model.SafLocation
import io.furryr.file.model.FtpServer
import io.furryr.file.model.SftpServer
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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import io.furryr.file.model.DirectoryInfo
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
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
    var currentInfo by remember { mutableStateOf(DirectoryInfo()) }
    var showJumpDialog by remember { mutableStateOf(false) }
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
    var showDeleteConfirm by remember { mutableStateOf<FileEntry?>(null) }
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
    var externalDirectory by remember { mutableStateOf<String?>(null) }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) externalDirectory =
            result.data?.getStringExtra(RemoteLocationsActivity.EXTRA_URI)
                ?: result.data?.getStringExtra(ArchiveToolsActivity.EXTRA_URI)
    }
    LaunchedEffect(terminalOpenRequest) {
        if (terminalOpenRequest > 0) showTerminalSheet = true
    }
    var containerRefreshKey by remember { mutableIntStateOf(0) }
    var drawerSortMode by remember { mutableStateOf(false) }
    var containerNameOrder by remember { mutableStateOf<List<String>>(emptyList()) }

    // Tracks the last daemon connection generation we've reacted to.
    // When DaemonLauncher establishes a fresh connection (after start or
    // recovery) this counter increments, triggering file list + drawer refresh.
    var lastRefreshDaemonGen by remember { mutableIntStateOf(DaemonManager.generation) }

    var safLocations by remember { mutableStateOf<List<SafLocation>>(emptyList()) }
    var safRefreshKey by remember { mutableIntStateOf(0) }

    val safFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            LocationsRepository.insertSaf(uri.toString())
            safRefreshKey++
        }
    }

    LaunchedEffect(safRefreshKey) {
        safLocations = withContext(Dispatchers.IO) { LocationsRepository.safLocations() }
    }

    var ftpLocations by remember { mutableStateOf<List<FtpServer>>(emptyList()) }
    var ftpRefreshKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(ftpRefreshKey) {
        ftpLocations = withContext(Dispatchers.IO) { LocationsRepository.ftpServers() }
    }

    var sftpLocations by remember { mutableStateOf<List<SftpServer>>(emptyList()) }
    var sftpRefreshKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(sftpRefreshKey) {
        sftpLocations = withContext(Dispatchers.IO) { LocationsRepository.sftpServers() }
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
    LaunchedEffect(drawerState.isClosed) {
        if (drawerState.isClosed) drawerSortMode = false
    }
    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    fun currentTab(): FileTab = tabs[selectedTabIndex]

    var pendingNavigation by remember(selectedTabIndex, currentTab().path) { mutableStateOf<FileTab?>(null) }

    var directoryCapabilities by remember {
        mutableStateOf(DirectoryCapabilities())
    }

    fun replaceCurrent(tab: FileTab) {
        tabs[selectedTabIndex] = tab
    }

    fun navigateTo(path: String) {
        val tab = currentTab()
        if (path == tab.path || pendingNavigation != null) return
        highlightPath = null
        highlightDirectory = null
        highlightRefreshToken = null
        pendingScrollPath = null
        pendingNavigation = tab.copy(path = path, backStack = tab.backStack + tab.path, forwardStack = emptyList())
    }

    fun goBack() {
        if (pendingNavigation != null) return
        val tab = currentTab()
        val target = tab.backStack.lastOrNull() ?: return
        pendingNavigation =
            tab.copy(
                path = target,
                backStack = tab.backStack.dropLast(1),
                forwardStack = listOf(tab.path) + tab.forwardStack
            )
    }

    fun goForward() {
        if (pendingNavigation != null) return
        val tab = currentTab()
        val target = tab.forwardStack.firstOrNull() ?: return
        pendingNavigation =
            tab.copy(
                path = target,
                backStack = tab.backStack + tab.path,
                forwardStack = tab.forwardStack.drop(1)
            )
    }

    fun goParent() {
        val path = pendingNavigation?.path ?: currentTab().path
        val parent = FileRepository.navigate(path, "..") ?: return
        pendingNavigation = null
        highlightPath = null
        pendingScrollPath = null
        navigateTo(parent)
    }

    fun refreshFileList(animate: Boolean = true) {
        refreshTrigger++
        if (animate) animationTrigger++
    }

    LaunchedEffect(externalDirectory) {
        externalDirectory?.let { destination ->
            pendingNavigation = null
            if (currentTab().path != destination) navigateTo(destination)
            refreshFileList()
            externalDirectory = null
        }
    }
    fun archiveAction(mode: String, paths: List<String>) {
        locationLauncher.launch(Intent(context, ArchiveToolsActivity::class.java)
            .putExtra(ArchiveToolsActivity.EXTRA_MODE, mode)
            .putStringArrayListExtra(ArchiveToolsActivity.EXTRA_PATHS, ArrayList(paths))
            .putExtra(ArchiveToolsActivity.EXTRA_DESTINATION, currentTab().path))
    }

    val handleHereRequest by rememberUpdatedState(newValue = { request: HereNavigation.Request ->
        val tab = currentTab()
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
            selectedPaths = emptySet(),
            lastSelectedIndex = -1,
            backStack = if (tab.path == request.directory) tab.backStack else tab.backStack + tab.path,
            forwardStack = if (tab.path == request.directory) tab.forwardStack else emptyList(),
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

    fun closeTab(index: Int) {
        if (tabs.size <= 1) return
        tabs.removeAt(index)
        selectedTabIndex = selectedTabIndex.coerceAtMost(tabs.lastIndex)
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
        matches.sortedDescending().forEach { tabs.removeAt(it) }
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
            "delete" -> showDeleteConfirm = entry
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
                    operationProgress = OperationProgress(0, 0, source.name, state.isCopy)
                    val flow = if (state.isCopy) {
                        FileRepository.copy(source.path, targetDir)
                    } else {
                        FileRepository.move(source.path, targetDir)
                    }
                    flow.collect { progress ->
                        ensureActive()
                        if (progress.finished) {
                            if (progress.totalBytes > 0 && progress.copiedBytes < progress.totalBytes) {
                                failure = Exception("operation failed"); return@collect
                            }
                            return@collect
                        }
                        val now = System.currentTimeMillis()
                        val elapsed = now - lastTime
                        val speed = if (elapsed > 100) ((progress.copiedBytes - lastBytes) * 1000 / elapsed) else 0L
                        lastBytes = progress.copiedBytes
                        lastTime = now
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

    // Refresh container running status when terminal sessions change.
    DisposableEffect(Unit) {
        val listener: () -> Unit = { containerRefreshKey++ }
        SessionManager.addSessionChangeListener(listener)
        onDispose { SessionManager.removeSessionChangeListener(listener) }
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
        } else if (pendingNavigation != null) {
            pendingNavigation = null
            highlightPath = null
            pendingScrollPath = null
        } else {
            val currentPath = currentTab().path
            if (FileRepository.navigate(currentPath, "..") != null) {
                goParent()
            } else if (tabs.size > 1) {
                closeTab(selectedTabIndex)
            } else {
                (context as? Activity)?.finish()
            }
        }
    }

    @Composable
    fun TabContent(index: Int, tab: FileTab) {
        Tab(
            selected = index == selectedTabIndex,
            onClick = { selectedTabIndex = index },
            text = {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = tab.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(
                            end = if (tabs.size > 1) 18.dp else 0.dp
                        )
                    )
                    if (tabs.size > 1) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Close tab",
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .size(16.dp)
                                .clickable { closeTab(index) },
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
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
                onToolsClick = {
                    context.startActivity(Intent(context, ToolboxActivity::class.java).putExtra("path", currentTab().path))
                },
                onRemoteConnectionsClick = {
                    locationLauncher.launch(Intent(context, RemoteLocationsActivity::class.java))
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
                        withContext(Dispatchers.IO) {
                            io.furryr.file.agent.ContainerServiceForegroundService.stopContainer(context, name)
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
                safLocations = safLocations,
                safRefreshKey = safRefreshKey,
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
                onLocalPath = { path ->
                    navigateTo(path)
                    scope.launch { drawerState.close() }
                },
                onSafClick = { hexId ->
                    navigateTo("saf://$hexId/")
                    scope.launch { drawerState.close() }
                },
                onSafRename = { hexId, newName ->
                    scope.launch {
                        withContext(Dispatchers.IO) { LocationsRepository.renameSaf(hexId, newName) }
                        safRefreshKey++
                    }
                },
                onSafDelete = { hexId ->
                    scope.launch {
                        withContext(Dispatchers.IO) { LocationsRepository.deleteSaf(hexId) }
                        safRefreshKey++
                    }
                },
                safOrder = safLocations.map { it.hexId },
                onSafReorder = { from, to ->
                    val mutable = safLocations.toMutableList()
                    if (from in mutable.indices && to in mutable.indices) {
                        val item = mutable.removeAt(from)
                        mutable.add(to, item)
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                LocationsRepository.reorderSaf(mutable.map { it.hexId })
                            }
                            safRefreshKey++
                        }
                    }
                },
                ftpLocations = ftpLocations,
                ftpRefreshKey = ftpRefreshKey,
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
                        ftpRefreshKey++
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
                        ftpRefreshKey++
                    }
                },
                onFtpClick = { host ->
                    navigateTo("ftp://$host/")
                    scope.launch { drawerState.close() }
                },
                onFtpDelete = { host ->
                    scope.launch {
                        withContext(Dispatchers.IO) { LocationsRepository.deleteFtp(host) }
                        ftpRefreshKey++
                    }
                },
                ftpOrder = ftpLocations.map { it.host },
                onFtpReorder = { from, to ->
                    val mutable = ftpLocations.toMutableList()
                    if (from in mutable.indices && to in mutable.indices) {
                        val item = mutable.removeAt(from)
                        mutable.add(to, item)
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                LocationsRepository.reorderFtp(mutable.map { it.host })
                            }
                            ftpRefreshKey++
                        }
                    }
                },
                sftpLocations = sftpLocations,
                sftpRefreshKey = sftpRefreshKey,
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
                        sftpRefreshKey++
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
                        sftpRefreshKey++
                    }
                },
                onSftpClick = { host ->
                    navigateTo("sftp://$host/")
                    scope.launch { drawerState.close() }
                },
                onSftpDelete = { host ->
                    scope.launch {
                        withContext(Dispatchers.IO) { LocationsRepository.deleteSftp(host) }
                        sftpRefreshKey++
                    }
                },
                sftpOrder = sftpLocations.map { it.host },
                onSftpReorder = { from, to ->
                    val mutable = sftpLocations.toMutableList()
                    if (from in mutable.indices && to in mutable.indices) {
                        val item = mutable.removeAt(from)
                        mutable.add(to, item)
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                LocationsRepository.reorderSftp(mutable.map { it.host })
                            }
                            sftpRefreshKey++
                        }
                    }
                },
            )
        }
    ) {
        Scaffold(
            topBar = {
                Column {
                    if (copyMoveState != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.dp)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                    CenterAlignedTopAppBar(
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
                                            Icon(Icons.Default.Close, contentDescription = "Close search")
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
                                TitleBlock(
                                    path = currentTab().path,
                                    info = currentInfo,
                                    onClick = { showJumpDialog = true }
                                )
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, contentDescription = "Menu")
                            }
                        },
                        actions = {
                            Box {
                                IconButton(onClick = { showMoreMenu = true }) {
                                    Icon(Icons.Default.MoreVert, contentDescription = "More")
                                }
                                DropdownMenu(
                                    expanded = showMoreMenu,
                                    onDismissRequest = { showMoreMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.refresh)) },
                                        leadingIcon = { Icon(Icons.Default.Refresh, null) },
                                        onClick = {
                                            refreshFileList(animate = true)
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
                BottomToolbar(
                    canGoBack = toolTab.backStack.isNotEmpty(),
                    canGoForward = toolTab.forwardStack.isNotEmpty(),
                    canGoParent = FileRepository.navigate(pendingNavigation?.path ?: toolTab.path, "..") != null,
                    onBack = ::goBack,
                    onForward = ::goForward,
                    onCreateFile = { if (directoryCapabilities.canCreate) showCreateDialog = true },
                    canCreate = directoryCapabilities.canCreate,
                    onTerminal = { showTerminalSheet = true },
                    canOpenTerminal = directoryCapabilities.canOpenTerminal,
                    onParent = ::goParent,
                    copyMoveActive = copyMoveState != null,
                    onCopyMoveConfirm = { showCopyMovePlaceDialog = true },
                    onCopyMoveCancel = ::cancelCopyMove,
                    selectionMode = toolTab.selectedPaths.isNotEmpty(),
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
                    canShare = toolTab.selectedPaths.isNotEmpty() && selectedEntries().all { !it.isDirectory }
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
                                tabs.forEachIndexed { index, tab -> TabContent(index, tab) }
                            }
                        } else {
                            ScrollableTabRow(
                                selectedTabIndex = selectedTabIndex,
                                modifier = Modifier.fillMaxWidth(),
                                edgePadding = 0.dp
                            ) {
                                tabs.forEachIndexed { index, tab -> TabContent(index, tab) }
                            }
                        }
                    }
                    IconButton(
                        onClick = {
                            tabs.add(FileTab(path = DefaultPath))
                            pendingTabIndex = tabs.lastIndex
                        }
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "New tab")
                    }
                }

                val tab = currentTab()
                FileListScreen(
                    path = pendingNavigation?.path ?: tab.path,
                    committedPath = tab.path,
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
                    onFileClick = { if (ArchiveProvider.supports(it.name)) archiveAction("open", listOf(it.path)) else openFile(context, it) },
                    onLongPress = { contextMenuEntry = it },
                    selectionMode = copyMoveState == null && currentTab().selectedPaths.isNotEmpty(),
                    selectedPaths = currentTab().selectedPaths,
                    onToggleSelection = if (copyMoveState == null) ::toggleSelection else {{}},
                    onRangeSelect = if (copyMoveState == null) ::rangeSelectTo else {{}},
                    onFileDragStart = { showTerminalSheet = true },
                )
            }
        }
    }

    if (showJumpDialog) {
        JumpDialog(
            initialPath = currentTab().path,
            onDismiss = { showJumpDialog = false },
            onConfirm = { path ->
                showJumpDialog = false
                scope.launch {
                    val stat = withContext(Dispatchers.IO) { FileRepository.stat(path).getOrNull() }
                    if (stat != null && stat.exists && !stat.isDirectory) {
                            val parent = FileRepository.navigate(path, "..")
                        if (parent != null) {
                            if (parent == currentTab().path) {
                                replaceCurrent(currentTab().copy(
                                    selectedPaths = setOf(path),
                                    lastSelectedIndex = currentEntries.indexOfFirst { it.path == path }
                                ))
                                pendingScrollPath = path
                            } else {
                                navigateTo(parent)
                                highlightPath = path
                                highlightDirectory = parent
                                highlightRefreshToken = null
                                pendingScrollPath = path
                            }
                        }
                    } else {
                        navigateTo(path)
                    }
                }
            }
        )
    }

    if (showInfoDialog) {
        InfoDialog(
            path = currentTab().path,
            info = currentInfo,
            onDismiss = { showInfoDialog = false }
        )
    }

    if (showCreateDialog && directoryCapabilities.canCreate) {
        CreateDialog(
            initialPath = currentTab().path,
            onDismiss = { showCreateDialog = false },
            onCreateFile = { name ->
                val result = FileRepository.createFile(currentTab().path, name)
                Toast.makeText(
                    context,
                    result.exceptionOrNull()?.message ?: context.getString(R.string.toast_created, name),
                    Toast.LENGTH_SHORT
                ).show()
                showCreateDialog = false
                refreshFileList()
            },
            onCreateFolder = { name ->
                val result = FileRepository.createDirectory(currentTab().path, name)
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
            canShare = sharesForMenu.all { !it.isDirectory },
            onOpenWith = { contextMenuEntry = null; openFileWithSystem(context, entry) },
            onArchive = { contextMenuEntry = null; archiveAction("create", sharesForMenu.map { it.path }) },
            onExtract = if (!entry.isDirectory && ArchiveProvider.supports(entry.name)) {
                { contextMenuEntry = null; archiveAction("extract", listOf(entry.path)) }
            } else null,
        )
    }

    showDeleteConfirm?.let { entry ->
        DeleteConfirmDialog(
            entry = entry,
            onDismiss = { showDeleteConfirm = null },
            onConfirm = {
                val result = FileRepository.delete(entry.path)
                Toast.makeText(
                    context,
                    if (result.isSuccess) context.getString(R.string.toast_deleted, entry.name)
                    else result.exceptionOrNull()?.message ?: context.getString(R.string.toast_failed),
                    Toast.LENGTH_SHORT
                ).show()
                showDeleteConfirm = null
                refreshFileList()
            }
        )
    }

    showRenameDialog?.let { entry ->
        RenameDialog(
            entry = entry,
            onDismiss = { showRenameDialog = null },
            onConfirm = { newName ->
                val result = FileRepository.rename(entry.path, newName)
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
                        else FileRepository.rename(entry.path, newName).exceptionOrNull()
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

    TerminalSheet(
        visible = showTerminalSheet,
        onDismiss = { showTerminalSheet = false },
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
