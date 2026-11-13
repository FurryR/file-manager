package io.furryr.file.ui.components

import android.content.Context
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import io.furryr.file.R
import io.furryr.file.provider.model.FtpServer
import io.furryr.file.provider.model.LocalLocation
import io.furryr.file.provider.model.SftpServer
import io.furryr.file.provider.model.WebLocation
import io.furryr.file.provider.remote.RemoteLocation
import io.furryr.file.store.LocationsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Every drawer dialog's open/target/text state. */
internal class DrawerDialogState {
    var showContainerDialog by mutableStateOf(false)
    var showAddLocationDialog by mutableStateOf(false)
    var showDeleteDialog by mutableStateOf(false)
    var dialogTargetName by mutableStateOf<String?>(null)
    var showRenameDialog by mutableStateOf(false)
    var renameText by mutableStateOf("")

    var safDeleteTarget by mutableStateOf<String?>(null)
    var safRenameTarget by mutableStateOf<String?>(null)
    var safRenameText by mutableStateOf("")

    var localDeleteTarget by mutableStateOf<String?>(null)
    var localRenameTarget by mutableStateOf<String?>(null)
    var localRenameText by mutableStateOf("")

    var showAddFtpDialog by mutableStateOf(false)
    var ftpEditTarget by mutableStateOf<String?>(null)
    var ftpDeleteTarget by mutableStateOf<String?>(null)

    var showAddSftpDialog by mutableStateOf(false)
    var sftpEditTarget by mutableStateOf<String?>(null)
    var sftpDeleteTarget by mutableStateOf<String?>(null)

    var remoteEditTarget by mutableStateOf<String?>(null)
    var remoteDeleteTarget by mutableStateOf<String?>(null)
    var remoteApproval by mutableStateOf<RemoteHostApproval?>(null)

    var webRenameTarget by mutableStateOf<String?>(null)
    var webRenameText by mutableStateOf("")
    var webDeleteTarget by mutableStateOf<String?>(null)
}

/** All drawer dialogs; state lives in [DrawerDialogState], actions in the drawer. */
@Composable
internal fun DrawerDialogs(
    state: DrawerDialogState,
    context: Context,
    scope: CoroutineScope,
    rootAvailable: Boolean,
    localLocations: List<LocalLocation>,
    ftpLocations: List<FtpServer>,
    sftpLocations: List<SftpServer>,
    remoteLocations: List<RemoteLocation>,
    webLocations: List<WebLocation>,
    openRemote: (RemoteLocation) -> Unit,
    onContainerRename: (String, String) -> Unit,
    onContainerDelete: (String) -> Unit,
    onCreateContainer: (String, String, io.furryr.file.container.ContainerBackend) -> Unit,
    onUseExternalStorage: () -> Unit,
    onLocalDelete: (String) -> Unit,
    onLocalRename: (String, String) -> Unit,
    onSafDelete: (String) -> Unit,
    onSafRename: (String, String) -> Unit,
    onAddFtp: (String, String, Int, String, String) -> Unit,
    onFtpSave: (String, String, String, Int, String, String) -> Unit,
    onFtpDelete: (String) -> Unit,
    onAddSftp: (String, String, Int, String, String) -> Unit,
    onSftpSave: (String, String, String, Int, String, String) -> Unit,
    onSftpDelete: (String) -> Unit,
    onRemoteSave: (RemoteLocation) -> Unit,
    onRemoteDelete: (String) -> Unit,
    onAddRemote: (RemoteLocation) -> Unit,
    onAddWeb: (String, String) -> Unit,
    onWebRename: (String, String) -> Unit,
    onWebDelete: (String) -> Unit,
    onAddLocalPath: (String) -> Unit,
) = with(state) {
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
                ) { Text(stringResource(R.string.ok)) }
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
                ) { Text(stringResource(R.string.ok)) }
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
                ) { Text(stringResource(R.string.ok)) }
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

    // ── Web link rename dialog ─────────────────────────────────────────────
    if (webRenameTarget != null) {
        val id = webRenameTarget
        AlertDialog(
            onDismissRequest = { webRenameTarget = null },
            title = { Text(stringResource(R.string.rename_location_title)) },
            text = {
                OutlinedTextField(
                    value = webRenameText,
                    onValueChange = { webRenameText = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.name_label)) },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        id?.let { onWebRename(it, webRenameText.ifBlank { webLocations.find { w -> w.id == it }?.name ?: it }) }
                        webRenameTarget = null
                    }
                ) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { webRenameTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // ── Web link delete dialog ─────────────────────────────────────────────
    if (webDeleteTarget != null) {
        val id = webDeleteTarget
        AlertDialog(
            onDismissRequest = { webDeleteTarget = null },
            title = { Text(stringResource(R.string.delete_location_title)) },
            text = { Text(stringResource(R.string.delete_location_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        id?.let { onWebDelete(it) }
                        webDeleteTarget = null
                    }
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { webDeleteTarget = null }) {
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
                                Toast.makeText(
                                    context,
                                    it.message ?: context.getString(R.string.remote_store_error),
                                    Toast.LENGTH_LONG,
                                ).show()
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
                val handled = RemoteProtocols.add(
                    address,
                    object : RemoteProtocolTarget {
                        override fun ftp(host: String, name: String, port: Int, username: String, password: String) =
                            onAddFtp(host, name, port, username, password)

                        override fun sftp(host: String, name: String, port: Int, username: String, password: String) =
                            onAddSftp(host, name, port, username, password)

                        override fun remote(location: RemoteLocation) = onAddRemote(location)

                        override fun web(url: String, name: String) = onAddWeb(url, name)

                        override fun local(path: String) = onAddLocalPath(path)
                    },
                )
                if (!handled) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.location_invalid),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
        )
    }
}
