package io.furryr.file.provider.remote

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.furryr.file.AppLocale
import io.furryr.file.R
import io.furryr.file.ui.theme.FileManagerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RemoteLocationsActivity : ComponentActivity() {
    companion object {
        const val EXTRA_KIND = "remote_kind"
        const val EXTRA_URI = "remote_uri"
    }
    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(AppLocale.wrap(newBase)) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RemoteLocationStore.init(applicationContext)
        val requested = intent.getStringExtra(EXTRA_KIND)
        val kind = RemoteKind.entries.firstOrNull { it.scheme == requested }
        val dark = getSharedPreferences("settings", MODE_PRIVATE).getBoolean("dark_theme", true)
        setContent { FileManagerTheme(darkTheme = dark) {
            RemoteLocationsScreen(kind, onBack = { finish() }, onOpen = { uri ->
                setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_URI, uri))
                finish()
            })
        } }
    }
}

private data class HostApproval(val location: RemoteLocation, val key: SshHostKeyApprovalRequired)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemoteLocationsScreen(filter: RemoteKind?, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<RemoteLocation>>(emptyList()) }
    var editing by remember { mutableStateOf<RemoteLocation?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var approval by remember { mutableStateOf<HostApproval?>(null) }
    var deleting by remember { mutableStateOf<RemoteLocation?>(null) }
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(refresh) {
        val result = withContext(Dispatchers.IO) { runCatching { RemoteLocationStore.all() } }
        result.onSuccess { entries = it }.onFailure { error = context.getString(R.string.remote_store_error) }
    }
    fun connect(location: RemoteLocation) {
        busy = true; error = null
        scope.launch {
            val result = RemoteProviders.verify(location)
            if (result.isSuccess) {
                val saved = withContext(Dispatchers.IO) { runCatching { RemoteLocationStore.put(location) } }
                saved.onSuccess { onOpen(location.uri) }.onFailure { error = context.getString(R.string.remote_store_error) }
            } else {
                val failure = result.exceptionOrNull()
                if (failure is SshHostKeyApprovalRequired) approval = HostApproval(location, failure)
                else error = if (failure is SshHostKeyChangedException) context.getString(R.string.remote_host_changed, failure.fingerprint)
                    else failure?.message ?: context.getString(R.string.remote_connect_error)
            }
            busy = false
        }
    }
    Scaffold(topBar = { TopAppBar(
        title = { Text(stringResource(if (editing == null) R.string.remote_locations else R.string.remote_edit)) },
        navigationIcon = { IconButton(onClick = { if (editing != null) { editing = null; error = null } else onBack() }, enabled = !busy) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cancel))
        } },
    ) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }
            val item = editing
            if (item == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (filter == null || filter == RemoteKind.ALIST) TextButton(onClick = { editing = RemoteLocation(kind = RemoteKind.ALIST); error = null }, enabled = !busy) {
                        Text(stringResource(R.string.remote_add_alist))
                    }
                    if (filter == null || filter == RemoteKind.SSH) TextButton(onClick = { editing = RemoteLocation(kind = RemoteKind.SSH); error = null }, enabled = !busy) {
                        Text(stringResource(R.string.remote_add_ssh))
                    }
                }
                val visible = entries.filter { filter == null || it.kind == filter }
                if (visible.isEmpty()) Text(stringResource(R.string.remote_empty), modifier = Modifier.padding(vertical = 20.dp))
                visible.forEach { location ->
                    Column(Modifier.fillMaxWidth().clickable(enabled = !busy) { connect(location) }.padding(vertical = 12.dp)) {
                        Text(location.name, style = MaterialTheme.typography.titleMedium)
                        Text(if (location.kind == RemoteKind.ALIST) location.endpoint else "${location.username}@${location.host}:${location.port}",
                            style = MaterialTheme.typography.bodySmall)
                        Text(location.basePath, style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(onClick = { editing = location; error = null }, enabled = !busy) { Text(stringResource(R.string.remote_edit)) }
                            TextButton(onClick = { deleting = location }, enabled = !busy) { Text(stringResource(R.string.remote_remove)) }
                        }
                    }
                    HorizontalDivider()
                }
            } else key(item.id, item.hostKey) {
                RemoteLocationForm(item, busy, onConnect = ::connect, onSave = { candidate ->
                    busy = true; error = null
                    scope.launch {
                        val saved = withContext(Dispatchers.IO) { runCatching { RemoteLocationStore.put(candidate) } }
                        saved.onSuccess { editing = null; refresh++ }.onFailure { error = it.message ?: context.getString(R.string.remote_store_error) }
                        busy = false
                    }
                })
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    approval?.let { pending -> AlertDialog(
        onDismissRequest = { approval = null }, title = { Text(stringResource(R.string.remote_host_title)) },
        text = { Text(context.getString(R.string.remote_host_confirm, "${pending.location.host}:${pending.location.port}",
            pending.key.algorithm, pending.key.fingerprint)) },
        confirmButton = { TextButton(onClick = {
            approval = null
            val trusted = pending.location.copy(hostKey = pending.key.keyBase64)
            editing = trusted
            // Trust is explicit and persisted even if authentication later fails.
            busy = true
            scope.launch {
                val saved = withContext(Dispatchers.IO) { runCatching { RemoteLocationStore.put(trusted) } }
                busy = false
                saved.onSuccess { connect(trusted) }.onFailure { error = context.getString(R.string.remote_store_error) }
            }
        }) { Text(stringResource(R.string.remote_trust)) } },
        dismissButton = { TextButton(onClick = { approval = null }) { Text(stringResource(R.string.cancel)) } },
    ) }
    deleting?.let { location -> AlertDialog(
        onDismissRequest = { deleting = null }, title = { Text(stringResource(R.string.remote_remove)) },
        text = { Text(stringResource(R.string.remote_remove_confirm, location.name)) },
        confirmButton = { TextButton(onClick = {
            deleting = null
            scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { RemoteLocationStore.remove(location.id) } }
                result.onSuccess { refresh++ }.onFailure { error = context.getString(R.string.remote_store_error) }
            }
        }) { Text(stringResource(R.string.remote_remove)) } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
    ) }
}

@Composable
private fun RemoteLocationForm(original: RemoteLocation, busy: Boolean, onConnect: (RemoteLocation) -> Unit, onSave: (RemoteLocation) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var form by remember { mutableStateOf(original) }
    var port by remember { mutableStateOf(original.port.toString()) }
    var inputError by remember { mutableStateOf<String?>(null) }
    val importKey = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val loaded = withContext(Dispatchers.IO) { runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val bytes = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(bytes.size() + count <= 512 * 1024) { "The selected file is too large to be an SSH key" }
                        bytes.write(buffer, 0, count)
                    }
                    bytes.toString("UTF-8")
                } ?: error("Cannot open the selected key")
            } }
            loaded.onSuccess { form = form.copy(privateKey = it) }.onFailure { inputError = it.message }
        }
    }
    @Composable fun Field(value: String, label: Int, secret: Boolean = false, multiline: Boolean = false, onChange: (String) -> Unit) {
        OutlinedTextField(value = value, onValueChange = onChange, enabled = !busy,
            label = { Text(stringResource(label)) }, singleLine = !multiline, maxLines = if (multiline) 6 else 1,
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text),
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
    }
    Text(stringResource(if (original.kind == RemoteKind.ALIST) R.string.remote_alist_help else R.string.remote_ssh_help),
        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
    Field(form.name, R.string.remote_name) { form = form.copy(name = it) }
    if (original.kind == RemoteKind.ALIST) {
        Field(form.endpoint, R.string.remote_endpoint) { form = form.copy(endpoint = it.trim()) }
        Field(form.username, R.string.remote_username) { form = form.copy(username = it) }
        Field(form.password, R.string.remote_password, secret = true) { form = form.copy(password = it) }
        Field(form.token, R.string.remote_token, secret = true) { form = form.copy(token = it) }
        Field(form.directoryPassword, R.string.remote_directory_password, secret = true) { form = form.copy(directoryPassword = it) }
    } else {
        Field(form.host, R.string.remote_host) { form = form.copy(host = it.trim()) }
        Field(port, R.string.remote_port) { port = it }
        Field(form.username, R.string.remote_username) { form = form.copy(username = it) }
        Field(form.password, R.string.remote_password, secret = true) { form = form.copy(password = it) }
        Field(form.privateKey, R.string.remote_private_key, secret = true, multiline = true) { form = form.copy(privateKey = it) }
        TextButton(onClick = { importKey.launch(arrayOf("*/*")) }, enabled = !busy) { Text(stringResource(R.string.remote_import_key)) }
        Field(form.passphrase, R.string.remote_passphrase, secret = true) { form = form.copy(passphrase = it) }
        if (original.hostKey.isNotEmpty()) Text(stringResource(R.string.remote_key_pinned), style = MaterialTheme.typography.bodySmall)
    }
    Field(form.basePath, R.string.remote_base_path) { form = form.copy(basePath = it) }
    inputError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    fun submit(open: Boolean) {
        val candidate = form.copy(port = port.toIntOrNull() ?: 0,
            hostKey = original.hostKey.takeIf { original.host == form.host && original.port == port.toIntOrNull() }.orEmpty())
        runCatching { candidate.validate() }.onFailure { inputError = it.message }.onSuccess {
            inputError = null
            if (open) onConnect(candidate) else onSave(candidate)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { submit(false) }, enabled = !busy) { Text(stringResource(R.string.remote_save)) }
        Button(onClick = { submit(true) }, enabled = !busy) { Text(stringResource(R.string.remote_save_open)) }
    }
}
