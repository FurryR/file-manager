package io.furryr.file.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.furryr.file.R
import io.furryr.file.provider.remote.RemoteKind
import io.furryr.file.provider.remote.RemoteLocation
import java.io.ByteArrayOutputStream
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun RemoteLocationDialog(
    initial: RemoteLocation,
    title: String,
    onDismiss: () -> Unit,
    onConfirm: (RemoteLocation) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var form by remember(initial.id, initial.kind, initial.hostKey) { mutableStateOf(initial) }
    var port by remember(initial.id, initial.port) { mutableStateOf(initial.port.toString()) }
    var inputError by remember { mutableStateOf<String?>(null) }

    val importKey = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val bytes = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(bytes.size() + count <= 512 * 1024) {
                                "The selected file is too large to be an SSH key"
                            }
                            bytes.write(buffer, 0, count)
                        }
                        bytes.toString("UTF-8")
                    } ?: error("Cannot open the selected key")
                }
            }
            loaded.onSuccess { form = form.copy(privateKey = it) }.onFailure { inputError = it.message }
        }
    }

    @Composable
    fun Field(
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

    fun submit() {
        val remotePort = port.toIntOrNull() ?: if (form.kind == RemoteKind.SSH) 22 else 0
        val fallbackName = if (form.kind == RemoteKind.ALIST) {
            runCatching { URI(form.endpoint.trim()).host }.getOrNull().orEmpty().ifBlank { "AList" }
        } else {
            form.host.trim().ifBlank { "SSH" }
        }
        val candidate = form.copy(
            name = form.name.trim().ifBlank { fallbackName },
            basePath = form.basePath.trim().ifBlank { "/" },
            endpoint = form.endpoint.trim(),
            host = form.host.trim(),
            port = remotePort,
            hostKey = if (form.kind == RemoteKind.SSH &&
                initial.host == form.host.trim() && initial.port == remotePort
            ) initial.hostKey else "",
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
                    stringResource(
                        if (form.kind == RemoteKind.ALIST) R.string.remote_alist_help
                        else R.string.remote_ssh_help,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                Field(form.name, R.string.remote_name) { form = form.copy(name = it) }
                if (form.kind == RemoteKind.ALIST) {
                    Field(form.endpoint, R.string.remote_endpoint) { form = form.copy(endpoint = it) }
                    Field(form.username, R.string.remote_username) { form = form.copy(username = it) }
                    Field(form.password, R.string.remote_password, secret = true) {
                        form = form.copy(password = it)
                    }
                    Field(form.token, R.string.remote_token, secret = true) { form = form.copy(token = it) }
                    Field(form.directoryPassword, R.string.remote_directory_password, secret = true) {
                        form = form.copy(directoryPassword = it)
                    }
                } else {
                    Field(form.host, R.string.remote_host) { form = form.copy(host = it) }
                    Field(port, R.string.remote_port) { port = it.filter(Char::isDigit) }
                    Field(form.username, R.string.remote_username) { form = form.copy(username = it) }
                    Field(form.password, R.string.remote_password, secret = true) {
                        form = form.copy(password = it)
                    }
                    Field(form.privateKey, R.string.remote_private_key, secret = true, multiline = true) {
                        form = form.copy(privateKey = it)
                    }
                    TextButton(onClick = { importKey.launch(arrayOf("*/*")) }) {
                        Text(stringResource(R.string.remote_import_key))
                    }
                    Field(form.passphrase, R.string.remote_passphrase, secret = true) {
                        form = form.copy(passphrase = it)
                    }
                    if (initial.hostKey.isNotEmpty()) {
                        Text(
                            stringResource(R.string.remote_key_pinned),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Field(form.basePath, R.string.remote_base_path) { form = form.copy(basePath = it) }
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
