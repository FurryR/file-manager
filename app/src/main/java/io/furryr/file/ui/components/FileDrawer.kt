package io.furryr.file.ui.components
import io.furryr.file.ui.theme.*
import io.furryr.file.ui.screens.*
import io.furryr.file.ui.components.*
import io.furryr.file.ui.util.*
import io.furryr.file.model.*
import io.furryr.file.agent.Container
import io.furryr.file.agent.ContainerState
import io.furryr.file.agent.ContainerBackend
import io.furryr.file.R
import io.furryr.file.FileRepository
import io.furryr.file.core.model.ProviderStat
import io.furryr.file.core.DaemonManager
import io.furryr.file.DefaultPath
import io.furryr.file.ui.util.formatSize


import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.furryr.file.agent.ContainerManager
import io.furryr.file.LocationsRepository
import io.furryr.file.provider.remote.RemoteKind
import io.furryr.file.provider.remote.RemoteLocation
import io.furryr.file.provider.remote.SshHostKeyApprovalRequired
import io.furryr.file.provider.remote.SshHostKeyChangedException
import kotlin.math.abs
import io.furryr.file.model.SafLocation
import io.furryr.file.model.LocalLocation
import io.furryr.file.model.FtpServer
import io.furryr.file.model.SftpServer
import io.furryr.file.model.LocationAddress
import io.furryr.file.model.LocationAddressParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun FileDrawer(
    darkTheme: Boolean,
    onToggleTheme: () -> Unit,
    onHomeClick: () -> Unit,
    onRootClick: () -> Unit,
    onContainerClick: (name: String) -> Unit,
    onNewContainer: () -> Unit,
    onCreateContainer: (name: String, image: String, backend: ContainerBackend) -> Unit = { _, _, _ -> },
    rootAvailable: Boolean = DaemonManager.isRootAvailable(),
    onSettingsClick: () -> Unit,
    onContainerRename: (oldName: String, newName: String) -> Unit = { _, _ -> },
    onContainerDelete: (name: String) -> Unit = {},
    onContainerKill: (name: String) -> Unit = {},
    sortMode: Boolean = false,
    onToggleSortMode: () -> Unit = {},
    containerOrder: List<String> = emptyList(),
    onContainerReorder: (from: Int, to: Int) -> Unit = { _, _ -> },
    refreshKey: Int = 0,
    safLocations: List<SafLocation> = emptyList(),
    safRefreshKey: Int = 0,
    onAddLocation: () -> Unit = {},
    onUseExternalStorage: () -> Unit = {},
    localLocations: List<LocalLocation> = emptyList(),
    localOrder: List<String> = emptyList(),
    onAddLocalPath: (String) -> Unit = {},
    onLocalClick: (LocalLocation) -> Unit = {},
    onLocalRename: (id: String, newName: String) -> Unit = { _, _ -> },
    onLocalDelete: (id: String) -> Unit = {},
    onLocalReorder: (from: Int, to: Int) -> Unit = { _, _ -> },
    onSafClick: (hexId: String) -> Unit = {},
    onSafRename: (hexId: String, newName: String) -> Unit = { _, _ -> },
    onSafDelete: (hexId: String) -> Unit = {},
    safOrder: List<String> = emptyList(),
    onSafReorder: (from: Int, to: Int) -> Unit = { _, _ -> },
    ftpLocations: List<FtpServer> = emptyList(),
    ftpRefreshKey: Int = 0,
    onAddFtp: (host: String, name: String, port: Int, username: String, password: String) -> Unit = { _, _, _, _, _ -> },
    onFtpSave: (oldHost: String, newHost: String, name: String, port: Int, username: String, password: String) -> Unit = { _, _, _, _, _, _ -> },
    onFtpClick: (host: String) -> Unit = {},
    onFtpDelete: (host: String) -> Unit = {},
    ftpOrder: List<String> = emptyList(),
    onFtpReorder: (from: Int, to: Int) -> Unit = { _, _ -> },
    sftpLocations: List<SftpServer> = emptyList(),
    sftpRefreshKey: Int = 0,
    onAddSftp: (host: String, name: String, port: Int, username: String, password: String) -> Unit = { _, _, _, _, _ -> },
    onSftpSave: (oldHost: String, newHost: String, name: String, port: Int, username: String, password: String) -> Unit = { _, _, _, _, _, _ -> },
    onSftpClick: (host: String) -> Unit = {},
    onSftpDelete: (host: String) -> Unit = {},
    sftpOrder: List<String> = emptyList(),
    onSftpReorder: (from: Int, to: Int) -> Unit = { _, _ -> },
    remoteLocations: List<RemoteLocation> = emptyList(),
    onAddRemote: (RemoteLocation) -> Unit = {},
    onRemoteSave: (RemoteLocation) -> Unit = {},
    onRemoteClick: (RemoteLocation) -> Unit = {},
    onRemoteDelete: (id: String) -> Unit = {},
    remoteOrder: List<String> = emptyList(),
    onRemoteReorder: (from: Int, to: Int) -> Unit = { _, _ -> },
) {
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    var rootStat by remember { mutableStateOf<ProviderStat?>(null) }
    var homeStat by remember { mutableStateOf<ProviderStat?>(null) }
    var containers by remember { mutableStateOf<List<Container>>(emptyList()) }
    var showDrawerMenu by remember { mutableStateOf(false) }
    var showContainerDialog by remember { mutableStateOf(false) }
    var showAddLocationDialog by remember { mutableStateOf(false) }
    var contextMenuTarget by remember { mutableStateOf<Container?>(null) }
    var dialogTargetName by remember { mutableStateOf<String?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var draggedContainerName by remember { mutableStateOf<String?>(null) }
    var dragOffsetDp by remember { mutableStateOf(0f) }

    // SAF dialog states
    var safContextMenuTarget by remember { mutableStateOf<String?>(null) }
    var safDeleteTarget by remember { mutableStateOf<String?>(null) }
    var safRenameTarget by remember { mutableStateOf<String?>(null) }
    var safRenameText by remember { mutableStateOf("") }
    var draggedSafHex by remember { mutableStateOf<String?>(null) }
    var safDragOffsetDp by remember { mutableStateOf(0f) }

    // Local path dialog states
    var localContextMenuTarget by remember { mutableStateOf<String?>(null) }
    var localDeleteTarget by remember { mutableStateOf<String?>(null) }
    var localRenameTarget by remember { mutableStateOf<String?>(null) }
    var localRenameText by remember { mutableStateOf("") }
    var draggedLocalId by remember { mutableStateOf<String?>(null) }
    var localDragOffsetDp by remember { mutableStateOf(0f) }

    // FTP dialog states
    var showAddFtpDialog by remember { mutableStateOf(false) }
    var ftpContextMenuTarget by remember { mutableStateOf<String?>(null) }
    var ftpEditTarget by remember { mutableStateOf<String?>(null) }
    var ftpDeleteTarget by remember { mutableStateOf<String?>(null) }
    var draggedFtpHost by remember { mutableStateOf<String?>(null) }
    var ftpDragOffsetDp by remember { mutableStateOf(0f) }

    // SFTP dialog states
    var showAddSftpDialog by remember { mutableStateOf(false) }
    var sftpContextMenuTarget by remember { mutableStateOf<String?>(null) }
    var sftpEditTarget by remember { mutableStateOf<String?>(null) }
    var sftpDeleteTarget by remember { mutableStateOf<String?>(null) }
    var draggedSftpHost by remember { mutableStateOf<String?>(null) }
    var sftpDragOffsetDp by remember { mutableStateOf(0f) }

    // Remote (AList / SSH) dialog states
    val scope = rememberCoroutineScope()
    var remoteEditTarget by remember { mutableStateOf<String?>(null) }
    var remoteDeleteTarget by remember { mutableStateOf<String?>(null) }
    var remoteContextMenuTarget by remember { mutableStateOf<String?>(null) }
    var remoteApproval by remember { mutableStateOf<RemoteHostApproval?>(null) }
    var remoteBusyId by remember { mutableStateOf<String?>(null) }
    var remoteError by remember { mutableStateOf<String?>(null) }
    var draggedRemoteId by remember { mutableStateOf<String?>(null) }
    var remoteDragOffsetDp by remember { mutableStateOf(0f) }

    // Display order for pinned local paths
    val displayLocalLocations = remember(localLocations, localOrder, sortMode) {
        if (sortMode && localOrder.isNotEmpty()) {
            localOrder.mapNotNull { id -> localLocations.find { it.id == id } }
        } else {
            localLocations.sortedBy { it.sortOrder }
        }
    }

    // Display order: root and home fixed first, then SAF items in order
    val displaySafLocations = remember(safLocations, safOrder, sortMode) {
        if (sortMode && safOrder.isNotEmpty()) {
            safOrder.mapNotNull { hex -> safLocations.find { it.hexId == hex } }
        } else {
            safLocations.sortedBy { it.sortOrder }
        }
    }

    // Display order for FTP locations (sortable among themselves)
    val displayFtpLocations = remember(ftpLocations, ftpOrder, sortMode) {
        if (sortMode && ftpOrder.isNotEmpty()) {
            ftpOrder.mapNotNull { host -> ftpLocations.find { it.host == host } }
        } else {
            ftpLocations.sortedBy { it.sortOrder }
        }
    }

    // Display order for SFTP locations (sortable among themselves)
    val displaySftpLocations = remember(sftpLocations, sftpOrder, sortMode) {
        if (sortMode && sftpOrder.isNotEmpty()) {
            sftpOrder.mapNotNull { host -> sftpLocations.find { it.host == host } }
        } else {
            sftpLocations.sortedBy { it.sortOrder }
        }
    }

    // Display order for encrypted remote locations (AList / SSH)
    val displayRemoteLocations = remember(remoteLocations, remoteOrder, sortMode) {
        if (sortMode && remoteOrder.isNotEmpty()) {
            remoteOrder.mapNotNull { id -> remoteLocations.find { it.id == id } }
        } else {
            remoteLocations.sortedBy { it.sortOrder }
        }
    }

    // Stable references for drag gesture handlers
    val currentContainerOrder by rememberUpdatedState(containerOrder)
    val currentOnReorder by rememberUpdatedState(onContainerReorder)
    val currentSafOrder by rememberUpdatedState(safOrder)
    val currentOnSafReorder by rememberUpdatedState(onSafReorder)
    val currentLocalOrder by rememberUpdatedState(localOrder)
    val currentOnLocalReorder by rememberUpdatedState(onLocalReorder)
    val currentFtpOrder by rememberUpdatedState(ftpOrder)
    val currentOnFtpReorder by rememberUpdatedState(onFtpReorder)
    val currentSftpOrder by rememberUpdatedState(sftpOrder)
    val currentOnSftpReorder by rememberUpdatedState(onSftpReorder)
    val currentRemoteOrder by rememberUpdatedState(remoteOrder)
    val currentOnRemoteReorder by rememberUpdatedState(onRemoteReorder)

    // Ordered container list for sort mode
    val displayContainers = remember(containers, containerOrder, sortMode) {
        if (sortMode && containerOrder.isNotEmpty()) {
            containerOrder.mapNotNull { name -> containers.find { it.name == name } }
        } else {
            containers
        }
    }

    // Collapse state for each group
    var collapsedGroups by remember { mutableStateOf(setOf<Int>()) }

    fun toggleGroup(index: Int) {
        collapsedGroups = if (index in collapsedGroups) {
            collapsedGroups - index
        } else {
            collapsedGroups + index
        }
    }

    fun openRemote(location: RemoteLocation) {
        if (remoteBusyId != null) return
        remoteBusyId = location.id
        scope.launch {
            val result = LocationsRepository.verifyRemote(location)
            remoteBusyId = null
            result.onSuccess {
                onRemoteClick(location)
            }.onFailure { failure ->
                when (failure) {
                    is SshHostKeyApprovalRequired -> remoteApproval = RemoteHostApproval(location, failure)
                    is SshHostKeyChangedException -> remoteError =
                        context.getString(R.string.remote_host_changed, failure.fingerprint)
                    else -> remoteError = failure.message ?: context.getString(R.string.remote_connect_error)
                }
            }
        }
    }

    LaunchedEffect(refreshKey, safRefreshKey) {
        rootStat = withContext(Dispatchers.IO) {
            FileRepository.stat("/").getOrNull()
        }
        homeStat = withContext(Dispatchers.IO) {
            FileRepository.stat(DefaultPath).getOrNull()
        }
        containers = withContext(Dispatchers.IO) {
            ContainerManager.listContainers(context).getOrDefault(emptyList())
        }
    }

    ModalDrawerSheet(modifier = Modifier.width((configuration.screenWidthDp * 0.80).dp)) {
        LazyColumn {
            // ── Header row ───────────────────────────────────────────────
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, top = 16.dp, bottom = 8.dp, end = 4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.drawer_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    if (!sortMode) {
                        IconButton(onClick = onToggleTheme) {
                            Icon(
                                if (darkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                                contentDescription = "Toggle theme"
                            )
                        }
                    }
                    Box {
                        IconButton(onClick = {
                            if (sortMode) {
                                safContextMenuTarget = null
                                onToggleSortMode()
                            } else {
                                showDrawerMenu = true
                            }
                        }) {
                            Icon(
                                if (sortMode) Icons.Default.Check else Icons.Default.MoreVert,
                                contentDescription = if (sortMode) "Done sorting" else "More"
                            )
                        }
                        if (!sortMode) {
                            DropdownMenu(
                                expanded = showDrawerMenu,
                                onDismissRequest = { showDrawerMenu = false }
                            ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.drawer_add_unified_location)) },
                                leadingIcon = { Icon(Icons.Default.FolderOpen, null) },
                                onClick = {
                                    showDrawerMenu = false
                                    showAddLocationDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.drawer_new_container)) },
                                leadingIcon = { Icon(Icons.Default.Add, null) },
                                onClick = {
                                    showDrawerMenu = false
                                    showContainerDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.drawer_sort)) },
                                leadingIcon = { Icon(Icons.Default.Menu, null) },
                                onClick = {
                                    showDrawerMenu = false
                                    onToggleSortMode()
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.settings)) },
                                leadingIcon = { Icon(Icons.Default.Settings, null) },
                                onClick = {
                                    showDrawerMenu = false
                                    onSettingsClick()
                                }
                            )
                        }
                        }
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp))
                Spacer(Modifier.height(4.dp))
            }

            // ── Group: 本地 (0) ─────────────────────────────────────────
            item {
                GroupHeader(
                    labelResId = R.string.drawer_group_local,
                    isCollapsed = 0 in collapsedGroups,
                    onToggle = { toggleGroup(0) },
                )
            }
            if (0 !in collapsedGroups) {
                // Root / — always first, not sortable
                item(key = "_root") {
                    val stat = rootStat
                    val rootPct = if (stat != null && stat.totalBytes > 0)
                        (stat.totalBytes - stat.usableBytes).toFloat() / stat.totalBytes else 0f
                    StorageItem(
                        icon = Icons.Default.Folder,
                        label = stringResource(R.string.nav_root),
                        progress = rootPct,
                        usedText = stat?.let { formatSize(it.totalBytes - it.usableBytes) } ?: "—",
                        totalText = stat?.let { formatSize(it.totalBytes) } ?: "—",
                        onClick = onRootClick,
                    )
                }
                // Internal storage — always second, not sortable
                item(key = "_home") {
                    val stat = homeStat
                    val homePct = if (stat != null && stat.totalBytes > 0)
                        (stat.totalBytes - stat.usableBytes).toFloat() / stat.totalBytes else 0f
                    StorageItem(
                        icon = Icons.Default.SdCard,
                        label = stringResource(R.string.nav_home),
                        progress = homePct,
                        usedText = stat?.let { formatSize(it.totalBytes - it.usableBytes) } ?: "—",
                        totalText = stat?.let { formatSize(it.totalBytes) } ?: "—",
                        onClick = onHomeClick,
                    )
                }
                // Pinned local paths (sortable among themselves)
                itemsIndexed(
                    displayLocalLocations,
                    key = { _, loc -> "local_${loc.id}" }
                ) { _, loc ->
                    Box(modifier = Modifier.fillMaxWidth()) {
                        LocalLocationItem(
                            name = loc.name,
                            path = loc.path,
                            onClick = { onLocalClick(loc) },
                            onLongClick = { localContextMenuTarget = loc.id },
                            sortMode = sortMode,
                            isDragging = draggedLocalId == loc.id,
                            dragOffsetDp = if (draggedLocalId == loc.id) localDragOffsetDp else 0f,
                            dragHandleModifier = if (sortMode) {
                                Modifier.pointerInput(loc.id) {
                                    detectVerticalDragGestures(
                                        onDragStart = { draggedLocalId = loc.id },
                                        onVerticalDrag = { change, dragAmount ->
                                            change.consume()
                                            localDragOffsetDp += dragAmount.toDp().value
                                        },
                                        onDragEnd = {
                                            val id = draggedLocalId
                                            if (id != null) {
                                                val from = currentLocalOrder.indexOf(id)
                                                if (from >= 0) {
                                                    val itemH = 56f
                                                    val steps = (localDragOffsetDp / itemH).toInt()
                                                    val to = (from + steps).coerceIn(currentLocalOrder.indices)
                                                    if (to != from) currentOnLocalReorder(from, to)
                                                }
                                            }
                                            draggedLocalId = null
                                            localDragOffsetDp = 0f
                                        },
                                        onDragCancel = { draggedLocalId = null; localDragOffsetDp = 0f },
                                    )
                                }
                            } else Modifier,
                        )
                        DropdownMenu(
                            expanded = localContextMenuTarget == loc.id,
                            onDismissRequest = { localContextMenuTarget = null }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.drawer_rename)) },
                                leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, null) },
                                onClick = {
                                    localRenameTarget = loc.id
                                    localRenameText = loc.name
                                    localContextMenuTarget = null
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.drawer_delete)) },
                                leadingIcon = { Icon(Icons.Default.Delete, null) },
                                onClick = {
                                    localDeleteTarget = loc.id
                                    localContextMenuTarget = null
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.drawer_sort)) },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Sort, null) },
                                onClick = {
                                    localContextMenuTarget = null
                                    onToggleSortMode()
                                }
                            )
                        }
                    }
                }
                // SAF locations (sortable among themselves)
                itemsIndexed(
                    displaySafLocations,
                    key = { _, loc -> "saf_${loc.hexId}" }
                ) { idx, loc ->
                    Box(modifier = Modifier.fillMaxWidth()) {
                        SafLocationItem(
                            name = loc.name,
                            hexId = loc.hexId,
                            onClick = { onSafClick(loc.hexId) },
                            onLongClick = { safContextMenuTarget = loc.hexId },
                            sortMode = sortMode,
                            isDragging = draggedSafHex == loc.hexId,
                            dragOffsetDp = if (draggedSafHex == loc.hexId) safDragOffsetDp else 0f,
                            dragHandleModifier = if (sortMode) {
                                Modifier.pointerInput(loc.hexId) {
                                    detectVerticalDragGestures(
                                        onDragStart = { draggedSafHex = loc.hexId },
                                        onVerticalDrag = { change, dragAmount ->
                                            change.consume()
                                            safDragOffsetDp += dragAmount.toDp().value
                                        },
                                        onDragEnd = {
                                            val hex = draggedSafHex
                                            if (hex != null) {
                                                val from = currentSafOrder.indexOf(hex)
                                                if (from >= 0) {
                                                    val itemH = 56f
                                                    val steps = (safDragOffsetDp / itemH).toInt()
                                                    val to = (from + steps).coerceIn(currentSafOrder.indices)
                                                    if (to != from) currentOnSafReorder(from, to)
                                                }
                                            }
                                            draggedSafHex = null
                                            safDragOffsetDp = 0f
                                        },
                                        onDragCancel = { draggedSafHex = null; safDragOffsetDp = 0f },
                                    )
                                }
                            } else Modifier,
                        )
                        // Context menu for SAF items
                        DropdownMenu(
                            expanded = safContextMenuTarget == loc.hexId,
                            onDismissRequest = { safContextMenuTarget = null }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.drawer_rename)) },
                                leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, null) },
                                onClick = {
                                    safRenameTarget = loc.hexId
                                    safRenameText = loc.name
                                    safContextMenuTarget = null
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.drawer_delete)) },
                                leadingIcon = { Icon(Icons.Default.Delete, null) },
                                onClick = {
                                    safDeleteTarget = loc.hexId
                                    safContextMenuTarget = null
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.drawer_sort)) },
                                             leadingIcon = { Icon(Icons.AutoMirrored.Filled.Sort, null) },
                                             onClick = {
                                                 contextMenuTarget = null
                                                 onToggleSortMode()
                                             }
                            )
                        }
                    }
                }
            }

            // ── Group: 网络 (1) ─────────────────────────────────────────
            item {
                GroupHeader(
                    labelResId = R.string.drawer_group_network,
                    isCollapsed = 1 in collapsedGroups,
                    onToggle = { toggleGroup(1) },
                )
            }
            if (1 !in collapsedGroups) {
                if (remoteBusyId != null) {
                    item {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                        )
                    }
                }
                if (displayFtpLocations.isEmpty() && displaySftpLocations.isEmpty() &&
                    displayRemoteLocations.isEmpty()
                ) {
                    item {
                        Text(
                            text = stringResource(R.string.no_locations),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        )
                    }
                } else {
                    itemsIndexed(
                        displayFtpLocations,
                        key = { _, server -> "ftp_${server.host}" }
                    ) { _, server ->
                        Box(modifier = Modifier.fillMaxWidth()) {
                            RemoteLocationItem(
                                name = server.name,
                                detail = "ftp://${server.host}/",
                                onClick = { onFtpClick(server.host) },
                                onLongClick = { ftpContextMenuTarget = server.host },
                                sortMode = sortMode,
                                isDragging = draggedFtpHost == server.host,
                                dragOffsetDp = if (draggedFtpHost == server.host) ftpDragOffsetDp else 0f,
                                dragHandleModifier = if (sortMode) {
                                    Modifier.pointerInput(server.host) {
                                        detectVerticalDragGestures(
                                            onDragStart = { draggedFtpHost = server.host },
                                            onVerticalDrag = { change, dragAmount ->
                                                change.consume()
                                                ftpDragOffsetDp += dragAmount.toDp().value
                                            },
                                            onDragEnd = {
                                                val host = draggedFtpHost
                                                if (host != null) {
                                                    val from = currentFtpOrder.indexOf(host)
                                                    if (from >= 0) {
                                                        val itemH = 56f
                                                        val steps = (ftpDragOffsetDp / itemH).toInt()
                                                        val to = (from + steps).coerceIn(currentFtpOrder.indices)
                                                        if (to != from) currentOnFtpReorder(from, to)
                                                    }
                                                }
                                                draggedFtpHost = null
                                                ftpDragOffsetDp = 0f
                                            },
                                            onDragCancel = { draggedFtpHost = null; ftpDragOffsetDp = 0f },
                                        )
                                    }
                                } else Modifier,
                            )
                            DropdownMenu(
                                expanded = ftpContextMenuTarget == server.host,
                                onDismissRequest = { ftpContextMenuTarget = null }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.drawer_rename)) },
                                    leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, null) },
                                    onClick = {
                                        ftpEditTarget = server.host
                                        ftpContextMenuTarget = null
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.drawer_delete)) },
                                    leadingIcon = { Icon(Icons.Default.Delete, null) },
                                    onClick = {
                                        ftpDeleteTarget = server.host
                                        ftpContextMenuTarget = null
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.drawer_sort)) },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.Sort, null) },
                                    onClick = {
                                        ftpContextMenuTarget = null
                                        onToggleSortMode()
                                    }
                                )
                            }
                        }
                    }
                    itemsIndexed(
                            displaySftpLocations,
                            key = { _, server -> "sftp_${server.host}" }
                        ) { _, server ->
                            Box(modifier = Modifier.fillMaxWidth()) {
                                RemoteLocationItem(
                                    name = server.name,
                                    detail = "sftp://${server.host}/",
                                    onClick = { onSftpClick(server.host) },
                                    onLongClick = { sftpContextMenuTarget = server.host },
                                    sortMode = sortMode,
                                    isDragging = draggedSftpHost == server.host,
                                    dragOffsetDp = if (draggedSftpHost == server.host) sftpDragOffsetDp else 0f,
                                    dragHandleModifier = if (sortMode) {
                                        Modifier.pointerInput(server.host) {
                                            detectVerticalDragGestures(
                                                onDragStart = { draggedSftpHost = server.host },
                                                onVerticalDrag = { change, dragAmount ->
                                                    change.consume()
                                                    sftpDragOffsetDp += dragAmount.toDp().value
                                                },
                                                onDragEnd = {
                                                    val host = draggedSftpHost
                                                    if (host != null) {
                                                        val from = currentSftpOrder.indexOf(host)
                                                        if (from >= 0) {
                                                            val itemH = 56f
                                                            val steps = (sftpDragOffsetDp / itemH).toInt()
                                                            val to = (from + steps).coerceIn(currentSftpOrder.indices)
                                                            if (to != from) currentOnSftpReorder(from, to)
                                                        }
                                                    }
                                                    draggedSftpHost = null
                                                    sftpDragOffsetDp = 0f
                                                },
                                                onDragCancel = { draggedSftpHost = null; sftpDragOffsetDp = 0f },
                                            )
                                        }
                                    } else Modifier,
                                )
                                DropdownMenu(
                                    expanded = sftpContextMenuTarget == server.host,
                                    onDismissRequest = { sftpContextMenuTarget = null }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.drawer_rename)) },
                                        leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, null) },
                                        onClick = {
                                            sftpEditTarget = server.host
                                            sftpContextMenuTarget = null
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.drawer_delete)) },
                                        leadingIcon = { Icon(Icons.Default.Delete, null) },
                                        onClick = {
                                            sftpDeleteTarget = server.host
                                            sftpContextMenuTarget = null
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.drawer_sort)) },
                                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.Sort, null) },
                                        onClick = {
                                            sftpContextMenuTarget = null
                                            onToggleSortMode()
                                        }
                                    )
                                }
                            }
                        }
                    itemsIndexed(
                        displayRemoteLocations,
                        key = { _, location -> "remote_${location.id}" }
                    ) { _, location ->
                        Box(modifier = Modifier.fillMaxWidth()) {
                            RemoteLocationItem(
                                name = location.name,
                                detail = if (location.kind == RemoteKind.ALIST) {
                                    "AList · ${location.endpoint}"
                                } else {
                                    "SSH · ${location.username}@${location.host}:${location.port}"
                                },
                                onClick = { openRemote(location) },
                                onLongClick = { remoteContextMenuTarget = location.id },
                                sortMode = sortMode,
                                isDragging = draggedRemoteId == location.id,
                                dragOffsetDp = if (draggedRemoteId == location.id) remoteDragOffsetDp else 0f,
                                dragHandleModifier = if (sortMode) {
                                    Modifier.pointerInput(location.id) {
                                        detectVerticalDragGestures(
                                            onDragStart = { draggedRemoteId = location.id },
                                            onVerticalDrag = { change, dragAmount ->
                                                change.consume()
                                                remoteDragOffsetDp += dragAmount.toDp().value
                                            },
                                            onDragEnd = {
                                                val id = draggedRemoteId
                                                if (id != null) {
                                                    val from = currentRemoteOrder.indexOf(id)
                                                    if (from >= 0) {
                                                        val itemH = 56f
                                                        val steps = (remoteDragOffsetDp / itemH).toInt()
                                                        val to = (from + steps).coerceIn(currentRemoteOrder.indices)
                                                        if (to != from) currentOnRemoteReorder(from, to)
                                                    }
                                                }
                                                draggedRemoteId = null
                                                remoteDragOffsetDp = 0f
                                            },
                                            onDragCancel = {
                                                draggedRemoteId = null
                                                remoteDragOffsetDp = 0f
                                            },
                                        )
                                    }
                                } else Modifier,
                            )
                            DropdownMenu(
                                expanded = remoteContextMenuTarget == location.id,
                                onDismissRequest = { remoteContextMenuTarget = null },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.drawer_rename)) },
                                    leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, null) },
                                    onClick = {
                                        remoteEditTarget = location.id
                                        remoteContextMenuTarget = null
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.drawer_delete)) },
                                    leadingIcon = { Icon(Icons.Default.Delete, null) },
                                    onClick = {
                                        remoteDeleteTarget = location.id
                                        remoteContextMenuTarget = null
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.drawer_sort)) },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.Sort, null) },
                                    onClick = {
                                        remoteContextMenuTarget = null
                                        onToggleSortMode()
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // ── Group: 容器 (2) ─────────────────────────────────────────
            item {
                GroupHeader(
                    labelResId = R.string.drawer_group_container,
                    isCollapsed = 2 in collapsedGroups,
                    onToggle = { toggleGroup(2) },
                )
            }
            if (2 !in collapsedGroups) {
                if (displayContainers.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.no_containers),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        )
                    }
                } else {
                    items(displayContainers, key = { it.name }) { container ->
                        Box(modifier = Modifier.fillMaxWidth()) {
                            ContainerItem(
                                container = container,
                                onClick = { onContainerClick(container.name) },
                                onLongClick = { contextMenuTarget = container },
                                sortMode = sortMode,
                                isDragging = draggedContainerName == container.name,
                                dragOffsetDp = if (draggedContainerName == container.name) dragOffsetDp else 0f,
                                dragHandleModifier = if (sortMode) {
                                    Modifier.pointerInput(Unit) {
                                        detectVerticalDragGestures(
                                            onDragStart = { draggedContainerName = container.name },
                                            onVerticalDrag = { change, dragAmount ->
                                                change.consume()
                                                dragOffsetDp += dragAmount.toDp().value
                                            },
                                            onDragEnd = {
                                                val name = draggedContainerName
                                                if (name != null) {
                                                    val from = currentContainerOrder.indexOf(name)
                                                    if (from >= 0) {
                                                        val itemH = 56f
                                                        val steps = (dragOffsetDp / itemH).toInt()
                                                        val to = (from + steps).coerceIn(currentContainerOrder.indices)
                                                         if (to != from) currentOnReorder(from, to)
                                                    }
                                                }
                                                draggedContainerName = null
                                                dragOffsetDp = 0f
                                            },
                                            onDragCancel = { draggedContainerName = null; dragOffsetDp = 0f },
                                        )
                                    }
                                } else Modifier,
                            )
                            DropdownMenu(
                                expanded = contextMenuTarget?.name == container.name,
                                onDismissRequest = { contextMenuTarget = null }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.drawer_rename)) },
                                    leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, null) },
                                    onClick = {
                                        dialogTargetName = contextMenuTarget?.name
                                        renameText = contextMenuTarget?.name ?: ""
                                        contextMenuTarget = null
                                        showRenameDialog = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.drawer_delete)) },
                                    leadingIcon = { Icon(Icons.Default.Delete, null) },
                                    enabled = container.backend != ContainerBackend.CHROOT || rootAvailable,
                                    onClick = {
                                        dialogTargetName = contextMenuTarget?.name
                                        contextMenuTarget = null
                                        showDeleteDialog = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.drawer_kill)) },
                                    leadingIcon = { Icon(Icons.Default.PowerSettingsNew, null) },
                                    enabled = container.backend != ContainerBackend.CHROOT || rootAvailable,
                                    onClick = {
                                        contextMenuTarget?.name?.let { onContainerKill(it) }
                                        contextMenuTarget = null
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.drawer_sort)) },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.Sort, null) },
                                    onClick = {
                                        contextMenuTarget = null
                                        onToggleSortMode()
                                    }
                                )
            }
        }
    }
            }
        }

            // ── Group: 工具 (3) ─────────────────────────────────────────
            item {
                GroupHeader(
                    labelResId = R.string.drawer_group_tools,
                    isCollapsed = 3 in collapsedGroups,
                    onToggle = { toggleGroup(3) },
                )
            }
            if (3 !in collapsedGroups) {
                item {
                    Text(
                        text = stringResource(R.string.drawer_tools_placeholder),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
            }

            // ── Bottom spacer ──────────────────────────────────────────
            item { Spacer(Modifier.height(8.dp)) }
        }
    }

    // ── Container delete confirmation dialog ────────────────────────────────
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = {
                showDeleteDialog = false
                dialogTargetName = null
            },
            title = { Text(stringResource(R.string.delete_container_title)) },
            text = { Text(stringResource(R.string.delete_container_confirm, dialogTargetName ?: "")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        dialogTargetName?.let { onContainerDelete(it) }
                        showDeleteDialog = false
                        dialogTargetName = null
                    }
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    dialogTargetName = null
                }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    // ── Container rename dialog ─────────────────────────────────────────────
    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = {
                showRenameDialog = false
                dialogTargetName = null
            },
            title = { Text(stringResource(R.string.rename_container_title)) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.name_label)) },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        dialogTargetName?.let { oldName ->
                            onContainerRename(oldName, renameText.ifBlank { oldName })
                        }
                        showRenameDialog = false
                        renameText = ""
                        dialogTargetName = null
                    }
                ) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showRenameDialog = false
                    dialogTargetName = null
                }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    // ── Local path delete dialog ────────────────────────────────────────────
    if (localDeleteTarget != null) {
        val id = localDeleteTarget
        AlertDialog(
            onDismissRequest = { localDeleteTarget = null },
            title = { Text(stringResource(R.string.delete_location_title)) },
            text = { Text(stringResource(R.string.delete_location_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        id?.let { onLocalDelete(it) }
                        localDeleteTarget = null
                    }
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { localDeleteTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // ── Local path rename dialog ─────────────────────────────────────────────
    if (localRenameTarget != null) {
        val id = localRenameTarget
        val current = localLocations.find { it.id == id }
        AlertDialog(
            onDismissRequest = { localRenameTarget = null },
            title = { Text(stringResource(R.string.rename_location_title)) },
            text = {
                OutlinedTextField(
                    value = localRenameText,
                    onValueChange = { localRenameText = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.name_label)) },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        id?.let { onLocalRename(it, localRenameText.ifBlank { current?.name ?: it }) }
                        localRenameTarget = null
                    }
                ) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { localRenameTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // ── SAF delete dialog ───────────────────────────────────────────────────
    if (safDeleteTarget != null) {
        val hex = safDeleteTarget
        AlertDialog(
            onDismissRequest = { safDeleteTarget = null },
            title = { Text(stringResource(R.string.delete_location_title)) },
            text = { Text(stringResource(R.string.delete_location_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        hex?.let { onSafDelete(it) }
                        safDeleteTarget = null
                    }
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { safDeleteTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // ── SAF rename dialog ───────────────────────────────────────────────────
    if (safRenameTarget != null) {
        val hex = safRenameTarget
        AlertDialog(
            onDismissRequest = { safRenameTarget = null },
            title = { Text(stringResource(R.string.rename_location_title)) },
            text = {
                OutlinedTextField(
                    value = safRenameText,
                    onValueChange = { safRenameText = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.name_label)) },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        hex?.let { onSafRename(it, safRenameText.ifBlank { it }) }
                        safRenameTarget = null
                    }
                ) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { safRenameTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // ── FTP add dialog ────────────────────────────────────────────────────
    if (showAddFtpDialog) {
        RemoteServerDialog(
            initialPort = 21,
            title = stringResource(R.string.ftp_add_title),
            onDismiss = { showAddFtpDialog = false },
            onConfirm = { host, name, port, username, password ->
                onAddFtp(host, name, port, username, password)
                showAddFtpDialog = false
            },
        )
    }

    // ── FTP edit dialog ───────────────────────────────────────────────────
    ftpEditTarget?.let { oldHost ->
        val server = ftpLocations.find { it.host == oldHost }
        if (server != null) {
            RemoteServerDialog(
                initialName = server.name,
                initialHost = server.host,
                initialPort = server.port,
                initialUsername = server.username,
                initialPassword = server.password,
                title = stringResource(R.string.ftp_edit_title),
                onDismiss = { ftpEditTarget = null },
                onConfirm = { host, name, port, username, password ->
                    onFtpSave(oldHost, host, name, port, username, password)
                    ftpEditTarget = null
                },
            )
        }
    }

    // ── FTP delete dialog ─────────────────────────────────────────────────
    if (ftpDeleteTarget != null) {
        val host = ftpDeleteTarget
        val server = ftpLocations.find { it.host == host }
        AlertDialog(
            onDismissRequest = { ftpDeleteTarget = null },
            title = { Text(stringResource(R.string.drawer_delete)) },
            text = { Text(stringResource(R.string.delete_ftp_confirm, server?.name ?: host ?: "")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        host?.let { onFtpDelete(it) }
                        ftpDeleteTarget = null
                    }
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { ftpDeleteTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // ── SFTP add dialog ───────────────────────────────────────────────────
    if (showAddSftpDialog) {
        RemoteServerDialog(
            initialPort = 22,
            title = stringResource(R.string.sftp_add_title),
            onDismiss = { showAddSftpDialog = false },
            onConfirm = { host, name, port, username, password ->
                onAddSftp(host, name, port, username, password)
                showAddSftpDialog = false
            },
        )
    }

    // ── SFTP edit dialog ──────────────────────────────────────────────────
    sftpEditTarget?.let { oldHost ->
        val server = sftpLocations.find { it.host == oldHost }
        if (server != null) {
            RemoteServerDialog(
                initialName = server.name,
                initialHost = server.host,
                initialPort = server.port,
                initialUsername = server.username,
                initialPassword = server.password,
                title = stringResource(R.string.sftp_edit_title),
                onDismiss = { sftpEditTarget = null },
                onConfirm = { host, name, port, username, password ->
                    onSftpSave(oldHost, host, name, port, username, password)
                    sftpEditTarget = null
                },
            )
        }
    }

    // ── SFTP delete dialog ────────────────────────────────────────────────
    if (sftpDeleteTarget != null) {
        val host = sftpDeleteTarget
        val server = sftpLocations.find { it.host == host }
        AlertDialog(
            onDismissRequest = { sftpDeleteTarget = null },
            title = { Text(stringResource(R.string.drawer_delete)) },
            text = { Text(stringResource(R.string.delete_sftp_confirm, server?.name ?: host ?: "")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        host?.let { onSftpDelete(it) }
                        sftpDeleteTarget = null
                    }
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { sftpDeleteTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // ── Remote edit dialog ─────────────────────────────────────────────────
    remoteEditTarget?.let { id ->
        remoteLocations.find { it.id == id }?.let { location ->
            RemoteLocationDialog(
                initial = location,
                title = stringResource(R.string.remote_edit),
                onDismiss = { remoteEditTarget = null },
                onConfirm = { updated ->
                    onRemoteSave(updated)
                    remoteEditTarget = null
                },
            )
        }
    }

    // ── Remote delete dialog ───────────────────────────────────────────────
    remoteDeleteTarget?.let { id ->
        val location = remoteLocations.find { it.id == id }
        AlertDialog(
            onDismissRequest = { remoteDeleteTarget = null },
            title = { Text(stringResource(R.string.drawer_delete)) },
            text = {
                Text(
                    stringResource(
                        R.string.remote_remove_confirm,
                        location?.name ?: id,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRemoteDelete(id)
                        remoteDeleteTarget = null
                    },
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { remoteDeleteTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // ── SSH host key verification ──────────────────────────────────────────
    remoteApproval?.let { pending ->
        AlertDialog(
            onDismissRequest = { remoteApproval = null },
            title = { Text(stringResource(R.string.remote_host_title)) },
            text = {
                Text(
                    context.getString(
                        R.string.remote_host_confirm,
                        "${pending.location.host}:${pending.location.port}",
                        pending.key.algorithm,
                        pending.key.fingerprint,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trusted = pending.location.copy(hostKey = pending.key.keyBase64)
                        remoteApproval = null
                        scope.launch {
                            val saved = withContext(Dispatchers.IO) {
                                runCatching { LocationsRepository.updateRemote(trusted) }
                            }
                            saved.onSuccess { openRemote(trusted) }.onFailure {
                                remoteError = it.message ?: context.getString(R.string.remote_store_error)
                            }
                        }
                    },
                ) { Text(stringResource(R.string.remote_trust)) }
            },
            dismissButton = {
                TextButton(onClick = { remoteApproval = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    remoteError?.let { message ->
        AlertDialog(
            onDismissRequest = { remoteError = null },
            title = { Text(stringResource(R.string.remote_locations)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { remoteError = null }) {
                    Text(stringResource(R.string.confirm))
                }
            },
        )
    }

    if (showContainerDialog) {
        ContainerCreateDialog(
            rootAvailable = rootAvailable,
            onDismiss = { showContainerDialog = false },
            onConfirm = { name, image, backend ->
                showContainerDialog = false
                onCreateContainer(name, image, backend)
            },
        )
    }

    if (showAddLocationDialog) {
        AddLocationDialog(
            onDismiss = { showAddLocationDialog = false },
            onUseExternalStorage = {
                showAddLocationDialog = false
                onUseExternalStorage()
            },
            onConfirm = { address ->
                showAddLocationDialog = false
                when (address.scheme) {
                    "ftp" -> onAddFtp(address.host, address.name.ifBlank { address.host }, address.port ?: 21, address.username, address.password)
                    "sftp" -> onAddSftp(address.host, address.name.ifBlank { address.host }, address.port ?: 22, address.username, address.password)
                    "ssh" -> onAddRemote(remoteLocationFrom(address, RemoteKind.SSH))
                    "alist" -> onAddRemote(remoteLocationFrom(address, RemoteKind.ALIST))
                    else -> onAddLocalPath(address.path)
                }
            },
        )
    }
}

@Composable
private fun ContainerCreateDialog(
    rootAvailable: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String, String, ContainerBackend) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var image by remember { mutableStateOf("library/alpine:latest") }
    var useChroot by remember { mutableStateOf(rootAvailable) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.wizard_create)) },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
            ) {
                Text(stringResource(R.string.wizard_pull_title), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.wizard_pull_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.wizard_name_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(image, { image = it }, label = { Text(stringResource(R.string.wizard_image_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.wizard_backend_label), style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = useChroot, enabled = rootAvailable, onClick = { useChroot = true })
                    Column {
                        Text(stringResource(R.string.wizard_backend_chroot), color = if (rootAvailable) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(R.string.wizard_backend_chroot_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = !useChroot, onClick = { useChroot = false })
                    Column {
                        Text(stringResource(R.string.wizard_backend_proot))
                        Text(stringResource(R.string.wizard_backend_proot_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (!rootAvailable) {
                    Text(
                        stringResource(R.string.wizard_backend_no_root),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 48.dp, top = 4.dp),
                    )
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && image.isNotBlank(),
                onClick = { onConfirm(name.trim(), image.trim(), if (useChroot && rootAvailable) ContainerBackend.CHROOT else ContainerBackend.PROOT) },
            ) { Text(stringResource(R.string.wizard_create_button)) }
        },
    )
}

@Composable
private fun AddLocationDialog(
    onDismiss: () -> Unit,
    onUseExternalStorage: () -> Unit,
    onConfirm: (LocationAddress) -> Unit,
) {
    var path by remember { mutableStateOf("") }
    var address by remember { mutableStateOf(LocationAddress()) }
    var invalid by remember { mutableStateOf(false) }

    fun updatePath(value: String) {
        path = value
        address = LocationAddressParser.parse(value) ?: LocationAddress()
        invalid = false
    }
    fun updateRemote(update: LocationAddress) {
        address = update
        path = update.toUrl()
        invalid = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.drawer_add_unified_location)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = path,
                    onValueChange = ::updatePath,
                    label = { Text(stringResource(R.string.location_path)) },
                    placeholder = { Text(stringResource(R.string.location_path_hint)) },
                    singleLine = true,
                    isError = invalid,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (address.isRemote) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.location_scheme) + ": ${address.scheme}", style = MaterialTheme.typography.labelLarge)
                    OutlinedTextField(address.name, { updateRemote(address.copy(name = it)) }, label = { Text(stringResource(R.string.ftp_name_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        address.host,
                        { updateRemote(address.copy(host = it)) },
                        label = {
                            Text(stringResource(if (address.scheme == "alist") R.string.remote_endpoint else R.string.ftp_host_label))
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(address.port?.toString().orEmpty(), { updateRemote(address.copy(port = it.toIntOrNull())) }, label = { Text(stringResource(R.string.ftp_port_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(address.username, { updateRemote(address.copy(username = it)) }, label = { Text(stringResource(R.string.ftp_username_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(address.password, { updateRemote(address.copy(password = it)) }, label = { Text(stringResource(R.string.ftp_password_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(address.path, { updateRemote(address.copy(path = it)) }, label = { Text(stringResource(R.string.remote_base_path)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (address.scheme == "alist") {
                        OutlinedTextField(address.token, { updateRemote(address.copy(token = it)) }, label = { Text(stringResource(R.string.remote_token)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(address.directoryPassword, { updateRemote(address.copy(directoryPassword = it)) }, label = { Text(stringResource(R.string.remote_directory_password)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    if (address.scheme == "ssh") {
                        OutlinedTextField(
                            address.privateKey,
                            { updateRemote(address.copy(privateKey = it)) },
                            label = { Text(stringResource(R.string.remote_private_key)) },
                            maxLines = 5,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(address.passphrase, { updateRemote(address.copy(passphrase = it)) }, label = { Text(stringResource(R.string.remote_passphrase)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                }
                if (invalid) Text(stringResource(R.string.location_invalid), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onUseExternalStorage) {
                    Text(stringResource(R.string.use_external_storage))
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
                TextButton(onClick = {
                    val parsed = LocationAddressParser.parse(path)
                    val missingHost = parsed != null && parsed.isRemote && parsed.host.isBlank()
                    val missingSshUser = parsed?.scheme == "ssh" && parsed.username.isBlank()
                    if (parsed == null || missingHost || missingSshUser) invalid = true else onConfirm(parsed)
                }) { Text(stringResource(R.string.confirm)) }
            }
        },
    )
}

@Composable
private fun GroupHeader(
    labelResId: Int = 0,
    label: String = "",
    isCollapsed: Boolean = false,
    onToggle: () -> Unit = {},
    showToggle: Boolean = true,
) {
    val display = if (label.isNotEmpty()) label else if (labelResId != 0) stringResource(labelResId) else ""
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (showToggle) Modifier.clickable(onClick = onToggle) else Modifier)
            .padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 2.dp),
    ) {
        Text(
            text = display,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onToggle, modifier = Modifier.size(32.dp)) {
            Icon(
                imageVector = if (isCollapsed) Icons.Default.KeyboardArrowDown
                    else Icons.Default.KeyboardArrowUp,
                contentDescription = if (isCollapsed) "Expand" else "Collapse",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StorageItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    progress: Float,
    usedText: String,
    totalText: String,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.width(24.dp))
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(4.dp),
            )
            Text(
                text = "${stringResource(R.string.used)} $usedText/$totalText",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContainerItem(
    container: Container,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    sortMode: Boolean = false,
    isDragging: Boolean = false,
    dragOffsetDp: Float = 0f,
    dragHandleModifier: Modifier = Modifier,
) {
    val stateLabel = when (container.state) {
        ContainerState.RUNNING -> stringResource(R.string.container_state_running)
        ContainerState.CREATED -> stringResource(R.string.container_state_stopped)
        ContainerState.STOPPED -> stringResource(R.string.container_state_stopped)
        ContainerState.STARTING, ContainerState.STOPPING -> stringResource(R.string.container_state_creating)
        ContainerState.FAILED -> stringResource(R.string.container_state_failed)
        ContainerState.DELETED -> stringResource(R.string.container_state_deleted)
    }
    val stateColor = when (container.state) {
        ContainerState.RUNNING -> MaterialTheme.colorScheme.primary
        ContainerState.CREATED, ContainerState.STOPPED -> MaterialTheme.colorScheme.onSurfaceVariant
        ContainerState.STARTING, ContainerState.STOPPING -> MaterialTheme.colorScheme.tertiary
        ContainerState.FAILED -> MaterialTheme.colorScheme.error
        ContainerState.DELETED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val backendLabel = stringResource(
        when (container.backend) {
            ContainerBackend.CHROOT -> R.string.container_backend_chroot
            ContainerBackend.PROOT -> R.string.container_backend_proot
        }
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (isDragging) Modifier.offset(y = dragOffsetDp.dp) else Modifier)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Icon(
            Icons.Default.Terminal,
            contentDescription = null,
            modifier = Modifier.width(24.dp),
            tint = stateColor,
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = container.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${container.distroId} ($backendLabel) · $stateLabel",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (sortMode) {
            Spacer(Modifier.width(8.dp))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(36.dp)
                    .then(dragHandleModifier),
            ) {
                Icon(
                    Icons.Default.Menu,
                    contentDescription = "Drag to reorder",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LocalLocationItem(
    name: String,
    path: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    sortMode: Boolean = false,
    isDragging: Boolean = false,
    dragOffsetDp: Float = 0f,
    dragHandleModifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (isDragging) Modifier.offset(y = dragOffsetDp.dp) else Modifier)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Icon(
            Icons.Default.Folder,
            contentDescription = null,
            modifier = Modifier.width(24.dp)
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = path,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (sortMode) {
            Spacer(Modifier.width(8.dp))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(36.dp)
                    .then(dragHandleModifier),
            ) {
                Icon(
                    Icons.Default.Menu,
                    contentDescription = "Drag to reorder",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SafLocationItem(
    name: String,
    hexId: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    sortMode: Boolean = false,
    isDragging: Boolean = false,
    dragOffsetDp: Float = 0f,
    dragHandleModifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (isDragging) Modifier.offset(y = dragOffsetDp.dp) else Modifier)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Icon(
            Icons.Default.Folder,
            contentDescription = null,
            modifier = Modifier.width(24.dp)
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "saf://$hexId/",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (sortMode) {
            Spacer(Modifier.width(8.dp))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(36.dp)
                    .then(dragHandleModifier),
            ) {
                Icon(
                    Icons.Default.Menu,
                    contentDescription = "Drag to reorder",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RemoteServerDialog(
    initialName: String = "",
    initialHost: String = "",
    initialPort: Int = 21,
    initialUsername: String = "",
    initialPassword: String = "",
    title: String,
    onDismiss: () -> Unit,
    onConfirm: (host: String, name: String, port: Int, username: String, password: String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var host by remember { mutableStateOf(initialHost) }
    var port by remember { mutableStateOf(initialPort.toString()) }
    var username by remember { mutableStateOf(initialUsername) }
    var password by remember { mutableStateOf(initialPassword) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.ftp_name_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.ftp_host_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row {
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it.filter(Char::isDigit) },
                        singleLine = true,
                        label = { Text(stringResource(R.string.ftp_port_label)) },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.ftp_username_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.ftp_password_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        host.trim(),
                        name.ifBlank { host.trim() },
                        port.toIntOrNull() ?: 21,
                        username,
                        password,
                    )
                },
                enabled = host.isNotBlank(),
            ) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RemoteLocationItem(
    name: String,
    detail: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    sortMode: Boolean = false,
    isDragging: Boolean = false,
    dragOffsetDp: Float = 0f,
    dragHandleModifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (isDragging) Modifier.offset(y = dragOffsetDp.dp) else Modifier)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Icon(
            Icons.Default.Cloud,
            contentDescription = null,
            modifier = Modifier.width(24.dp)
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (sortMode) {
            Spacer(Modifier.width(8.dp))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(36.dp)
                    .then(dragHandleModifier),
            ) {
                Icon(
                    Icons.Default.Menu,
                    contentDescription = "Drag to reorder",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun remoteLocationFrom(address: LocationAddress, kind: RemoteKind): RemoteLocation {
    val host = address.host.trim().trimEnd('/')
    val name = address.name.ifBlank { host }
    return if (kind == RemoteKind.ALIST) {
        val endpoint = if (host.startsWith("http://") || host.startsWith("https://")) {
            host
        } else {
            val port = address.port?.takeIf { it > 0 && it != 443 }
            "https://$host" + if (port == null) "" else ":$port"
        }
        RemoteLocation(
            name = name,
            kind = kind,
            basePath = address.path,
            endpoint = endpoint,
            username = address.username,
            password = address.password,
            token = address.token,
            directoryPassword = address.directoryPassword,
        )
    } else {
        RemoteLocation(
            name = name,
            kind = kind,
            basePath = address.path,
            host = host,
            port = address.port ?: 22,
            username = address.username,
            password = address.password,
            privateKey = address.privateKey,
            passphrase = address.passphrase,
        )
    }
}

private data class RemoteHostApproval(
    val location: RemoteLocation,
    val key: SshHostKeyApprovalRequired,
)
