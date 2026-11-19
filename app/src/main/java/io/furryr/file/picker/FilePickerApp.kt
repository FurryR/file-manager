package io.furryr.file.picker

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import io.furryr.file.ui.icons.Icons
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.furryr.file.AppWorld
import io.furryr.file.DefaultPath
import io.furryr.file.R
import io.furryr.file.container.ContainerUris
import io.furryr.file.core.DirectoryStats
import io.furryr.file.core.Path
import io.furryr.file.core.VFile
import io.furryr.file.core.VFolder
import io.furryr.file.core.VNode
import io.furryr.file.core.model.FileEntry
import io.furryr.file.home.HomeStore
import io.furryr.file.render.DefaultRendererErrorView
import io.furryr.file.render.FolderContent
import io.furryr.file.render.FolderListing
import io.furryr.file.ui.components.BottomToolbar
import io.furryr.file.ui.components.BottomToolbarAction
import io.furryr.file.ui.components.FileDrawer
import io.furryr.file.ui.components.TitleBlock
import io.furryr.file.ui.shell.PathEditScrim
import io.furryr.file.ui.state.rememberLocationCollections
import io.furryr.file.ui.util.PathSuggestion
import io.furryr.file.ui.util.PathSuggestionSource
import io.furryr.file.ui.util.PathSuggestions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The picker shell.
 *
 * It deliberately mirrors the file manager's one-folder view: the same drawer,
 * path bar, folder rows, selection backgrounds and bottom toolbar. It has no
 * tab strip, no terminal and no editor/browser opener; the bottom action is
 * the only destination for a routed file.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilePickerApp(
    world: AppWorld,
    request: PickerRequest,
    darkTheme: Boolean,
    onToggleTheme: () -> Unit,
    onCancel: () -> Unit,
    onPickFiles: (List<VFile>) -> Unit,
    onPickFolder: (VFolder) -> Unit,
    onCreated: (VFile) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listing = remember(world.vfs) {
        FolderListing(
            vfs = world.vfs,
            scope = scope,
            onSelectionChanged = { _, _, _ -> },
            clipboardAware = false,
        )
    }
    DisposableEffect(listing) {
        onDispose { listing.close() }
    }

    var path by rememberSaveable { mutableStateOf(request.initialPath) }
    var triedFallback by rememberSaveable { mutableStateOf(false) }
    var createName by rememberSaveable { mutableStateOf(request.suggestedName) }
    var createBusy by remember { mutableStateOf(false) }
    var operationError by remember { mutableStateOf<String?>(null) }
    // A saved web link is a file, not a folder; selecting it does not go
    // through the folder listing.
    var directFile by remember { mutableStateOf<VFile?>(null) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val locations = rememberLocationCollections()

    // Back/Forward are the picker's folder history. It is deliberately kept
    // local to the shell: a picker is one folder view, not a tab.
    var history by remember { mutableStateOf(listOf(path)) }
    var historyIndex by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        // An activity recreate restores the path but not this small stack.
        if (history.getOrNull(historyIndex) != path) {
            history = listOf(path)
            historyIndex = 0
        }
    }

    var titleEditing by remember { mutableStateOf(false) }
    var topBarBounds by remember { mutableStateOf<Rect?>(null) }

    val entryFilter: (FileEntry) -> Boolean = remember(request.mode, request.mimeTypes) {
        { entry ->
            when {
                entry.isDirectory -> true
                request.mode != PickerMode.OPEN -> false
                else -> request.accepts(entry.name)
            }
        }
    }

    val loadedFolder = listing.loadedFolder.takeIf { listing.loadedPath == path }
    val parentPath = (loadedFolder?.parent?.path
        ?: runCatching { Path.parse(path).parent() }.getOrNull())
        ?.toString()
    val selectedFiles = directFile?.let { listOf(it) } ?: listing.selectedEntries.mapNotNull { entry ->
        listing.nodesByName[entry.name] as? VFile
    }

    fun clearTransient() {
        listing.clearSelection()
        directFile = null
        operationError = null
    }

    fun replacePath(next: String) {
        clearTransient()
        path = next
        history = listOf(next)
        historyIndex = 0
    }

    fun navigate(next: String) {
        if (next == path) return
        clearTransient()
        path = next
        history = history.take(historyIndex + 1) + next
        historyIndex = history.lastIndex
    }

    fun goBack() {
        if (historyIndex <= 0) return
        clearTransient()
        historyIndex--
        path = history[historyIndex]
    }

    fun goForward() {
        if (historyIndex >= history.lastIndex) return
        clearTransient()
        historyIndex++
        path = history[historyIndex]
    }

    fun goUp() {
        val parent = parentPath
        if (parent != null) navigate(parent) else onCancel()
    }

    fun closeDrawer() {
        scope.launch { drawerState.close() }
    }

    // The picker's opener only selects; this is the one callback it reaches.
    val selection = world.pickerSelection
    DisposableEffect(selection, listing) {
        selection?.onSelect = { file ->
            scope.launch {
                directFile = null
                val entry = listing.entries.firstOrNull {
                    !it.isDirectory && it.name == file.name
                } ?: return@launch
                if (request.allowMultiple) {
                    listing.toggleSelection(entry)
                } else {
                    listing.setSelection(
                        setOf(entry.path),
                        listing.entries.indexOf(entry),
                    )
                }
            }
        }
        onDispose { selection?.onSelect = null }
    }

    LaunchedEffect(listing.error, listing.loadedPath, path) {
        if (listing.error == null && listing.loadedPath == path) {
            triedFallback = true
            return@LaunchedEffect
        }
        if (listing.error != null && !triedFallback && path == request.initialPath && path != "/") {
            triedFallback = true
            replacePath("/")
        }
    }

    fun open(entry: FileEntry, node: VNode?) {
        directFile = null
        when (node) {
            is VFolder -> navigate(node.path.toString())
            is VFile -> scope.launch {
                val renderer = withContext(Dispatchers.IO) {
                    runCatching { world.opener.route(node) }.getOrNull()
                }
                renderer?.close()
            }
            null -> {
                if (entry.isDirectory) {
                    navigate(entry.path)
                } else {
                    scope.launch {
                        val resolved = withContext(Dispatchers.IO) {
                            runCatching { world.vfs.file(entry.path).getOrThrow() }.getOrNull()
                        }
                        if (resolved != null) {
                            val renderer = withContext(Dispatchers.IO) {
                                runCatching { world.opener.route(resolved) }.getOrNull()
                            }
                            renderer?.close()
                        }
                    }
                }
            }
            else -> scope.launch {
                val resolved = withContext(Dispatchers.IO) {
                    runCatching { world.vfs.file(entry.path).getOrThrow() }.getOrNull()
                }
                if (resolved != null) {
                    val renderer = withContext(Dispatchers.IO) {
                        runCatching { world.opener.route(resolved) }.getOrNull()
                    }
                    renderer?.close()
                }
            }
        }
    }

    fun save() {
        val name = createName.trim()
        if (name.isEmpty() || name == "." || name == ".." || '/' in name || '\\' in name) {
            operationError = context.getString(R.string.picker_invalid_name)
            return
        }
        val folder = loadedFolder ?: return
        scope.launch {
            createBusy = true
            operationError = null
            if (withContext(Dispatchers.IO) { folder.child(name).getOrNull() != null }) {
                operationError = context.getString(R.string.picker_name_exists)
                createBusy = false
                return@launch
            }
            val result = withContext(Dispatchers.IO) { folder.destination(name) }
            result.fold(
                onSuccess = { onCreated(it) },
                onFailure = {
                    operationError = it.message ?: context.getString(R.string.picker_error)
                },
            )
            createBusy = false
        }
    }

    /**
     * A pinned web link is one online file: resolve it through the world and
     * offer it as the selection instead of trying to list a URL as a folder.
     */
    fun selectWebLocation(url: String) {
        scope.launch {
            drawerState.close()
            val file = withContext(Dispatchers.IO) {
                runCatching { world.vfs.file(url).getOrThrow() }.getOrNull()
            }
            if (file != null) {
                listing.clearSelection()
                directFile = file
                operationError = null
            } else {
                operationError = context.getString(R.string.picker_error)
            }
        }
    }

    BackHandler {
        when {
            historyIndex > 0 -> goBack()
            parentPath != null -> goUp()
            else -> onCancel()
        }
    }

    // The same address-bar rows as the manager; a picker never leaves the
    // device, so no internet completions.
    val homeRevision = HomeStore.revision()
    val homeRecent = remember(homeRevision) { HomeStore.recent() }
    val homeBookmarks = remember(homeRevision) { HomeStore.bookmarks() }
    val pathSuggestionSource = remember(homeBookmarks, homeRecent, world) {
        object : PathSuggestionSource {
            override fun local(query: String): List<PathSuggestion> =
                PathSuggestions.local(query, homeBookmarks, homeRecent)

            override suspend fun remote(query: String): List<PathSuggestion> = emptyList()

            // A picker lands on folders; a typed file is not a browsable stop.
            override suspend fun listing(query: String): List<PathSuggestion> =
                PathSuggestions.listing(world.files, query, includeFiles = false)
        }
    }

    fun chooseTitleSuggestion(suggestion: PathSuggestion) {
        navigate(suggestion.target)
    }

    val selectLabel = if (request.allowMultiple && selectedFiles.isNotEmpty()) {
        context.getString(R.string.picker_select_count, selectedFiles.size)
    } else {
        context.getString(R.string.picker_select)
    }
    val primaryEnabled = when (request.mode) {
        PickerMode.OPEN -> selectedFiles.isNotEmpty()
        PickerMode.CREATE -> loadedFolder != null && createName.isNotBlank() && !createBusy
        PickerMode.TREE -> loadedFolder != null
    }
    val bottomActions = listOf(
        BottomToolbarAction(
            icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
            contentDescription = stringResource(R.string.cd_back),
            enabled = historyIndex > 0,
            onClick = ::goBack,
        ),
        BottomToolbarAction(
            icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = stringResource(R.string.cd_forward),
            enabled = historyIndex < history.lastIndex,
            onClick = ::goForward,
        ),
        BottomToolbarAction(
            icon = Icons.Default.Done,
            contentDescription = when (request.mode) {
                PickerMode.OPEN -> selectLabel
                PickerMode.CREATE -> stringResource(R.string.picker_save)
                PickerMode.TREE -> stringResource(R.string.picker_use_folder)
            },
            enabled = primaryEnabled,
            onClick = {
                when (request.mode) {
                    PickerMode.OPEN -> onPickFiles(selectedFiles)
                    PickerMode.CREATE -> save()
                    PickerMode.TREE -> loadedFolder?.let(onPickFolder)
                }
            },
        ),
        BottomToolbarAction(
            icon = Icons.Default.Close,
            contentDescription = stringResource(R.string.picker_cancel),
            onClick = onCancel,
        ),
        BottomToolbarAction(
            icon = Icons.Default.KeyboardArrowUp,
            contentDescription = stringResource(R.string.picker_up),
            enabled = parentPath != null,
            onClick = ::goUp,
        ),
    )

    ModalNavigationDrawer(
        drawerState = drawerState,
        // The path edit scrim dims everything below the top bar; letting the
        // drawer's edge gesture open over it leaves two overlays active.
        gesturesEnabled = !titleEditing,
        drawerContent = {
            FileDrawer(
                vfs = world.vfs,
                darkTheme = darkTheme,
                onToggleTheme = onToggleTheme,
                onHomeClick = {
                    navigate(DefaultPath)
                    closeDrawer()
                },
                onRootClick = {
                    navigate("/")
                    closeDrawer()
                },
                onContainerClick = { name ->
                    navigate(ContainerUris.forContainer(name) + "/")
                    closeDrawer()
                },
                onNewContainer = {},
                onSettingsClick = {},
                allowEdit = false,
                showTools = false,
                localOnly = request.localOnly,
                localOrder = locations.localLocations.map { it.id },
                localLocations = locations.localLocations,
                onAddLocalPath = {},
                onLocalClick = { location ->
                    navigate(location.path)
                    closeDrawer()
                },
                onLocalRename = { _, _ -> },
                onLocalDelete = {},
                onLocalReorder = { _, _ -> },
                safRefreshKey = locations.safRefreshKey,
                safLocations = locations.safLocations,
                safOrder = locations.safLocations.map { it.hexId },
                onAddLocation = {},
                onUseExternalStorage = {},
                onSafClick = { hexId ->
                    navigate("saf:///$hexId")
                    closeDrawer()
                },
                onSafRename = { _, _ -> },
                onSafDelete = {},
                onSafReorder = { _, _ -> },
                ftpRefreshKey = locations.ftpRefreshKey,
                ftpLocations = locations.ftpLocations,
                ftpOrder = locations.ftpLocations.map { it.host },
                onAddFtp = { _, _, _, _, _ -> },
                onFtpSave = { _, _, _, _, _, _ -> },
                onFtpClick = { host ->
                    navigate("ftp://$host/")
                    closeDrawer()
                },
                onFtpDelete = {},
                onFtpReorder = { _, _ -> },
                sftpRefreshKey = locations.sftpRefreshKey,
                sftpLocations = locations.sftpLocations,
                sftpOrder = locations.sftpLocations.map { it.host },
                onAddSftp = { _, _, _, _, _ -> },
                onSftpSave = { _, _, _, _, _, _ -> },
                onSftpClick = { host ->
                    navigate("sftp://$host/")
                    closeDrawer()
                },
                onSftpDelete = {},
                onSftpReorder = { _, _ -> },
                remoteLocations = locations.remoteLocations,
                remoteOrder = locations.remoteLocations.map { it.id },
                onAddRemote = {},
                onRemoteSave = {},
                onRemoteClick = { location ->
                    navigate(location.uri)
                    closeDrawer()
                },
                onRemoteDelete = {},
                onRemoteReorder = { _, _ -> },
                // A picker has no browser opener; a live URL is one online file,
                // so it is selected directly instead of being listed.
                webLocations = if (request.mode == PickerMode.OPEN && !request.localOnly) {
                    locations.webLocations
                } else {
                    emptyList()
                },
                onAddWeb = { _, _ -> },
                onWebClick = ::selectWebLocation,
                onWebRename = { _, _ -> },
                onWebDelete = {},
                webOrder = emptyList(),
                onWebReorder = { _, _ -> },
            )
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    Column(
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surface)
                            .onGloballyPositioned { topBarBounds = it.boundsInWindow() },
                    ) {
                        TopAppBar(
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = Color.Transparent,
                                scrolledContainerColor = Color.Transparent,
                            ),
                            title = {
                                TitleBlock(
                                    path = path,
                                    info = if (listing.loadedPath == path) {
                                        listing.directoryInfo
                                    } else {
                                        DirectoryStats()
                                    },
                                    onNavigate = { raw ->
                                        navigate(raw.trim().ifEmpty { DefaultPath })
                                    },
                                    displayPath = path,
                                    editText = path,
                                    detail = if (selectedFiles.isNotEmpty()) selectLabel else null,
                                    suggestionSource = pathSuggestionSource,
                                    suggestionAnchor = topBarBounds,
                                    onSuggestion = ::chooseTitleSuggestion,
                                    onEditingChange = { titleEditing = it },
                                )
                            },
                            navigationIcon = {
                                IconButton(
                                    onClick = { scope.launch { drawerState.open() } },
                                    enabled = !titleEditing,
                                ) {
                                    Icon(
                                        Icons.Default.Menu,
                                        contentDescription = stringResource(R.string.cd_menu),
                                    )
                                }
                            },
                            actions = {
                                var showMenu by remember { mutableStateOf(false) }
                                Box {
                                    IconButton(onClick = { showMenu = true }) {
                                        Icon(
                                            Icons.Default.MoreVert,
                                            contentDescription = stringResource(R.string.cd_more),
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = showMenu,
                                        onDismissRequest = { showMenu = false },
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.refresh)) },
                                            leadingIcon = { Icon(Icons.Default.Refresh, null) },
                                            onClick = {
                                                listing.refresh(animate = false)
                                                showMenu = false
                                            },
                                        )
                                    }
                                }
                            },
                        )
                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                    }
                },
                bottomBar = {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface),
                    ) {
                        operationError?.let {
                            Text(
                                it,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                        if (request.mode == PickerMode.CREATE) {
                            Surface(tonalElevation = 2.dp) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                ) {
                                    OutlinedTextField(
                                        value = createName,
                                        onValueChange = { createName = it },
                                        label = { Text(stringResource(R.string.picker_file_name)) },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                        BottomToolbar(
                            canGoBack = false,
                            canGoForward = false,
                            canGoParent = false,
                            onBack = {},
                            onForward = {},
                            onCreate = {},
                            onTerminal = {},
                            onParent = {},
                            actions = bottomActions,
                        )
                    }
                },
            ) { padding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) {
                    FolderContent(
                        listing = listing,
                        path = path,
                        worldId = world.id,
                        committedPath = path,
                        onOpenEntry = ::open,
                        onLongPress = { entry ->
                            if (request.allowMultiple && !entry.isDirectory) {
                                directFile = null
                                listing.toggleSelection(entry)
                            }
                        },
                        clipboardAware = false,
                        singleSelection = !request.allowMultiple,
                        entryFilter = entryFilter,
                        navigateFoldersInSelection = true,
                    )
                    val listingError = listing.error
                    if (listingError != null && !listing.isLoading) {
                        DefaultRendererErrorView.Content(listingError) {
                            listing.refresh(animate = false)
                        }
                    } else if (listing.isLoading && listing.entries.isEmpty()) {
                        CircularProgressIndicator(Modifier.align(Alignment.Center))
                    }
                }
            }
            PathEditScrim(
                visible = titleEditing,
                topBarHeightPx = topBarBounds?.height ?: 0f,
            )
        }
    }
}
