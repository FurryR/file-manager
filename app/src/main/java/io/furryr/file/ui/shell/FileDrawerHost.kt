package io.furryr.file.ui.shell

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import io.furryr.file.DefaultPath
import io.furryr.file.R
import io.furryr.file.container.ContainerBackend
import io.furryr.file.container.ContainerManager
import io.furryr.file.core.ContainerUris
import io.furryr.file.daemon.DaemonManager
import io.furryr.file.store.LocationsRepository
import io.furryr.file.terminal.SessionManager
import io.furryr.file.ui.components.FileDrawer
import io.furryr.file.ui.state.LocationCollections
import io.furryr.file.ui.util.ContainerNotification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drawer wiring: container lifecycle plus one CRUD block per location store.
 * State stays in the shell; this host only receives the current values and
 * closes over the app-owned stores.
 */
@Composable
internal fun FileDrawerHost(
    darkTheme: Boolean,
    onToggleTheme: () -> Unit,
    locations: LocationCollections,
    refreshKey: Int,
    sortMode: Boolean,
    containerOrder: List<String>,
    onToggleSortMode: () -> Unit,
    onContainerReorder: (Int, Int) -> Unit,
    onNavigate: (String) -> Unit,
    onOpenAddress: (String) -> Unit,
    onAddSaf: () -> Unit,
    onSettingsClick: () -> Unit,
    onContainersChanged: () -> Unit,
    onKillTabs: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    FileDrawer(
        darkTheme = darkTheme,
        onToggleTheme = onToggleTheme,
        onHomeClick = { onNavigate(DefaultPath) },
        onRootClick = { onNavigate("/") },
        onContainerClick = { name -> onNavigate(ContainerUris.forContainer(name) + "/") },
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
                onContainersChanged()
            }
        },
        onSettingsClick = onSettingsClick,
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
                onContainersChanged()
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
                onContainersChanged()
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
                onKillTabs(name)
                onContainersChanged()
                Toast.makeText(
                    context,
                    context.getString(R.string.toast_killed, name),
                    Toast.LENGTH_SHORT
                ).show()
            }
        },
        sortMode = sortMode,
        onToggleSortMode = onToggleSortMode,
        containerOrder = containerOrder,
        onContainerReorder = onContainerReorder,
        refreshKey = refreshKey,
        safLocations = locations.safLocations,
        safRefreshKey = locations.safRefreshKey,
        onAddLocation = onAddSaf,
        onUseExternalStorage = onAddSaf,
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
        onLocalClick = { location -> onNavigate(location.path) },
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
        onSafClick = { hexId -> onNavigate("saf://$hexId/") },
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
        onFtpClick = { host -> onNavigate("ftp://$host/") },
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
        onSftpClick = { host -> onNavigate("sftp://$host/") },
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
        onRemoteClick = { location -> onNavigate(location.uri) },
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
        onWebClick = { url -> onOpenAddress(url) },
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
