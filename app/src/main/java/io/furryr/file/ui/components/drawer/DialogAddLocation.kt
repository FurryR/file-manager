package io.furryr.file.ui.components

import io.furryr.file.ui.theme.*
import io.furryr.file.ui.screens.*
import io.furryr.file.ui.components.*
import io.furryr.file.ui.util.*
import io.furryr.file.model.*
import io.furryr.file.container.Container
import io.furryr.file.container.ContainerState
import io.furryr.file.container.ContainerBackend
import io.furryr.file.R
import io.furryr.file.core.Vfs
import io.furryr.file.core.model.ProviderStat
import io.furryr.file.daemon.DaemonManager
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
import io.furryr.file.container.ContainerManager
import io.furryr.file.provider.LocationsRepository
import io.furryr.file.provider.remote.RemoteKind
import io.furryr.file.provider.remote.RemoteLocation
import io.furryr.file.provider.remote.SshHostKeyApprovalRequired
import io.furryr.file.provider.remote.SshHostKeyChangedException
import kotlin.math.abs
import io.furryr.file.provider.model.SafLocation
import io.furryr.file.provider.model.LocalLocation
import io.furryr.file.provider.model.FtpServer
import io.furryr.file.provider.model.SftpServer
import io.furryr.file.model.LocationAddress
import io.furryr.file.model.LocationAddressParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


@Composable
internal fun AddLocationDialog(
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
