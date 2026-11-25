package io.furryr.file.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.furryr.file.R
import io.furryr.file.provider.remote.AlistLocation
import io.furryr.file.provider.remote.RemoteLocation
import io.furryr.file.provider.remote.SshLocation
import io.furryr.file.store.RemoteLocationStore
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun RemoteLocationDialog(
    initial: RemoteLocation,
    title: String,
    onDismiss: () -> Unit,
    onConfirm: (RemoteLocation) -> Unit,
) {
    when (initial) {
        is AlistLocation -> AlistLocationDialog(initial, title, onDismiss, onConfirm)
        is SshLocation -> SshLocationDialog(initial, title, onDismiss, onConfirm)
    }
}

@Composable
private fun RemoteField(
    value: String,
    label: Int,
    secret: Boolean = false,
    multiline: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        singleLine = !multiline,
        maxLines = if (multiline) 6 else 1,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text,
        ),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

@Composable
private fun AlistLocationDialog(
    initial: AlistLocation,
    title: String,
    onDismiss: () -> Unit,
    onConfirm: (AlistLocation) -> Unit,
) {
    var form by remember(initial.id) { mutableStateOf(initial) }
    var inputError by remember { mutableStateOf<String?>(null) }

    fun submit() {
        val endpoint = form.endpoint.trim()
        val fallbackName = runCatching { URI(endpoint).host }.getOrNull()
            .orEmpty().ifBlank { "AList" }
        val candidate = form.copy(
            name = form.name.trim().ifBlank { fallbackName },
            basePath = form.basePath.trim().ifBlank { "/" },
            endpoint = endpoint,
        )
        runCatching { candidate.validate() }.onFailure {
            inputError = it.message
        }.onSuccess {
            inputError = null
            onConfirm(candidate)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.remote_alist_help),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                RemoteField(form.name, R.string.remote_name) { form = form.copy(name = it) }
                RemoteField(form.endpoint, R.string.remote_endpoint) { form = form.copy(endpoint = it) }
                RemoteField(form.username, R.string.remote_username) { form = form.copy(username = it) }
                IdentitySelector(form.identityId, allowKey = false) {
                    form = form.copy(identityId = it, password = "")
                }
                RemoteField(form.token, R.string.remote_token, secret = true) {
                    form = form.copy(token = it)
                }
                RemoteField(form.directoryPassword, R.string.remote_directory_password, secret = true) {
                    form = form.copy(directoryPassword = it)
                }
                RemoteField(form.basePath, R.string.remote_base_path) { form = form.copy(basePath = it) }
                inputError?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                TextButton(onClick = ::submit) { Text(stringResource(R.string.remote_save)) }
            }
        },
    )
}

@Composable
private fun SshLocationDialog(
    initial: SshLocation,
    title: String,
    onDismiss: () -> Unit,
    onConfirm: (SshLocation) -> Unit,
) {
    var form by remember(initial.id, initial.hostKey) { mutableStateOf(initial) }
    var port by remember(initial.id, initial.port) { mutableStateOf(initial.port.toString()) }
    var inputError by remember { mutableStateOf<String?>(null) }

    fun submit() {
        val remotePort = port.toIntOrNull() ?: 22
        val host = form.host.trim()
        val candidate = form.copy(
            name = form.name.trim().ifBlank { host.ifBlank { "SSH" } },
            basePath = form.basePath.trim().ifBlank { "/" },
            host = host,
            port = remotePort,
            hostKey = if (initial.host == host && initial.port == remotePort) initial.hostKey else "",
        )
        runCatching { candidate.validate() }.onFailure {
            inputError = it.message
        }.onSuccess {
            inputError = null
            onConfirm(candidate)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.remote_ssh_help),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                RemoteField(form.name, R.string.remote_name) { form = form.copy(name = it) }
                RemoteField(form.host, R.string.remote_host) { form = form.copy(host = it) }
                RemoteField(port, R.string.remote_port) { port = it.filter(Char::isDigit) }
                RemoteField(form.username, R.string.remote_username) { form = form.copy(username = it) }
                IdentitySelector(form.identityId, allowKey = true) {
                    form = form.copy(identityId = it, password = "", privateKey = "", passphrase = "")
                }
                if (initial.hostKey.isNotEmpty()) {
                    Text(
                        stringResource(R.string.remote_key_pinned),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                RemoteField(form.basePath, R.string.remote_base_path) { form = form.copy(basePath = it) }
                inputError?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                TextButton(onClick = ::submit) { Text(stringResource(R.string.remote_save)) }
            }
        },
    )
}
