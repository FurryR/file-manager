package io.furryr.file
import io.furryr.file.daemon.*
import io.furryr.file.model.*
import io.furryr.file.ui.components.*
import io.furryr.file.ui.screens.*
import io.furryr.file.ui.util.*
import io.furryr.file.util.Path

import io.furryr.file.agent.ContainerWizardActivity
import io.furryr.file.agent.ContainerManager
import io.furryr.file.agent.SessionManager
import io.furryr.file.provider.SafDatabase
import io.furryr.file.provider.SafLocation
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

import io.furryr.file.daemon.DaemonLauncher
import io.furryr.file.daemon.StatResult
import io.furryr.file.model.CopyMoveSource
import io.furryr.file.model.CopyMoveState
import io.furryr.file.model.DirectoryInfo
import io.furryr.file.model.FileEntry
import io.furryr.file.model.FileTab
import io.furryr.file.model.OperationProgress
import io.furryr.file.ui.components.BottomToolbar
import io.furryr.file.ui.components.FileDrawer
import io.furryr.file.ui.screens.FileListScreen
import io.furryr.file.ui.util.OperationNotification
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
    onToggleTheme: () -> Unit = {}
) {
    val context = LocalContext.current
    val tabs = remember { mutableStateListOf(FileTab(path = DefaultPath)) }
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var currentInfo by remember { mutableStateOf(DirectoryInfo()) }
    var showJumpDialog by remember { mutableStateOf(false) }
    var showInfoDialog by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
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
    var pendingScrollPath by remember { mutableStateOf<String?>(null) }
    var showAgentSheet by remember { mutableStateOf(false) }
    var containerRefreshKey by remember { mutableIntStateOf(0) }
    var drawerSortMode by remember { mutableStateOf(false) }
    var containerNameOrder by remember { mutableStateOf<List<String>>(emptyList()) }

    // Tracks the last daemon connection generation we've reacted to.
    // When DaemonLauncher establishes a fresh connection (after start or
    // recovery) this counter increments, triggering file list + drawer refresh.
    var lastRefreshDaemonGen by remember { mutableIntStateOf(DaemonLauncher.readyGeneration) }

    val containerWizardLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ -> containerRefreshKey++ }

    val safDb = remember { SafDatabase(context) }
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
            safDb.insert(uri.toString())
            safRefreshKey++
        }
    }

    LaunchedEffect(safRefreshKey) {
        safLocations = withContext(Dispatchers.IO) { safDb.getAll() }
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

    fun replaceCurrent(tab: FileTab) {
        tabs[selectedTabIndex] = tab
    }

    fun navigateTo(path: String) {
        val tab = currentTab()
        if (path == tab.path) return
        replaceCurrent(tab.copy(path = path, backStack = tab.backStack + tab.path, forwardStack = emptyList()))
    }

    fun navigateDown(name: String) {
        val tab = currentTab()
        val child = FileRepository.navigate(tab.path, name) ?: return
        if (child == tab.path) return
        replaceCurrent(tab.copy(path = child, backStack = tab.backStack + tab.path, forwardStack = emptyList()))
    }

    fun goBack() {
        val tab = currentTab()
        val target = tab.backStack.lastOrNull() ?: return
        replaceCurrent(
            tab.copy(
                path = target,
                backStack = tab.backStack.dropLast(1),
                forwardStack = listOf(tab.path) + tab.forwardStack
            )
        )
    }

    fun goForward() {
        val tab = currentTab()
        val target = tab.forwardStack.firstOrNull() ?: return
        replaceCurrent(
            tab.copy(
                path = target,
                backStack = tab.backStack + tab.path,
                forwardStack = tab.forwardStack.drop(1)
            )
        )
    }

    fun goParent() {
        FileRepository.navigate(currentTab().path, "..")?.let(::navigateTo)
    }

    fun refreshFileList(animate: Boolean = true) {
        refreshTrigger++
        if (animate) animationTrigger++
    }

    fun closeTab(index: Int) {
        if (tabs.size <= 1) return
        tabs.removeAt(index)
        selectedTabIndex = selectedTabIndex.coerceAtMost(tabs.lastIndex)
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

    // Daemon readiness observer — triggers a file list + drawer stats
    // refresh whenever the daemon establishes a fresh connection after
    // start, restart, or transient failure recovery.
    LaunchedEffect(Unit) {
        DaemonLauncher.daemonState.collect { state ->
            if (state is DaemonLauncher.DaemonState.Connected) {
                val currentGen = DaemonLauncher.readyGeneration
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
        } else if (currentTab().path != "/") {
            goParent()
        } else if (tabs.size > 1) {
            closeTab(selectedTabIndex)
        } else {
            (context as? Activity)?.finish()
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
                    containerWizardLauncher.launch(
                        Intent(context, ContainerWizardActivity::class.java)
                    )
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
                onSafClick = { hexId ->
                    navigateTo("saf://$hexId/")
                    scope.launch { drawerState.close() }
                },
                onSafRename = { hexId, newName ->
                    scope.launch {
                        withContext(Dispatchers.IO) { safDb.rename(hexId, newName) }
                        safRefreshKey++
                    }
                },
                onSafDelete = { hexId ->
                    scope.launch {
                        withContext(Dispatchers.IO) { safDb.delete(hexId) }
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
                                safDb.reorder(mutable.map { it.hexId })
                            }
                            safRefreshKey++
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
                    canGoParent = FileRepository.navigate(toolTab.path, "..") != null,
                    onBack = ::goBack,
                    onForward = ::goForward,
                    onCreateFile = { showCreateDialog = true },
                    onAgent = { showAgentSheet = true },
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
                    path = tab.path,
                    refreshToken = refreshTrigger,
                    animationToken = animationTrigger,
                    sortMode = sortMode,
                    sortReverse = sortReverse,
                    filterText = filterText,
                    scrollToPath = pendingScrollPath,
                    onScrollConsumed = { pendingScrollPath = null },
                    onInfoChange = { currentInfo = it },
                    onEntriesChange = { entries ->
                        currentEntries = entries
                        val curTab = currentTab()
                        var newPaths = curTab.selectedPaths intersect entries.map { it.path }.toSet()
                        if (highlightPath != null) {
                            val found = entries.any { it.path == highlightPath }
                            if (found) {
                                newPaths = newPaths + highlightPath!!
                            }
                            highlightPath = null
                        }
                        replaceCurrent(curTab.copy(selectedPaths = newPaths))
                    },
                    onDirectoryClick = { path -> Path.parse(path).name?.let(::navigateDown) },
                    onFileClick = { openFile(context, it) },
                    onLongPress = { contextMenuEntry = it },
                    selectionMode = copyMoveState == null && currentTab().selectedPaths.isNotEmpty(),
                    selectedPaths = currentTab().selectedPaths,
                    onToggleSelection = if (copyMoveState == null) ::toggleSelection else {{}},
                    onRangeSelect = if (copyMoveState == null) ::rangeSelectTo else {{}},
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
                    val stat = withContext(Dispatchers.IO) { DaemonLauncher.getConnection().stat(path).getOrNull() }
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

    if (showCreateDialog) {
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
            canShare = sharesForMenu.all { !it.isDirectory }
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

    AgentSheet(
        visible = showAgentSheet,
        onDismiss = { showAgentSheet = false },
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
