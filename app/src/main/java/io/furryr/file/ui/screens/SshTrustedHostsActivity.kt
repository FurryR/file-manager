package io.furryr.file.ui.screens

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.furryr.file.AppLocale
import io.furryr.file.R
import io.furryr.file.store.LocationStores
import io.furryr.file.store.RemoteLocationStore
import io.furryr.file.ui.icons.Icons
import io.furryr.file.ui.theme.FileManagerTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SshTrustedHostsActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLocale.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        setContent {
            FileManagerTheme(darkTheme = prefs.getBoolean("dark_theme", true)) {
                Surface(Modifier.fillMaxSize()) { SshTrustedHostsPage { finish() } }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SshTrustedHostsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var hosts by remember { mutableStateOf(emptyList<RemoteLocationStore.TrustedHost>()) }
    var deleting by remember { mutableStateOf<RemoteLocationStore.TrustedHost?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun refresh(remove: RemoteLocationStore.TrustedHost? = null) {
        busy = true
        scope.launch {
            try {
                hosts = withContext(Dispatchers.IO) {
                    LocationStores.init(context)
                    remove?.let { RemoteLocationStore.removeHost(it.host, it.port) }
                    RemoteLocationStore.trustedHosts()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: failure.toString()
            } finally { busy = false }
        }
    }
    LaunchedEffect(Unit) { refresh() }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.ssh_trusted_hosts)) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Text(
                    stringResource(R.string.ssh_trusted_hosts_desc),
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            items(hosts, key = { "${it.host}:${it.port}" }) { host ->
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("${host.host}:${host.port}", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            host.fingerprint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { deleting = host }, enabled = !busy) {
                        Text(stringResource(R.string.delete))
                    }
                }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            }
        }
    }
    deleting?.let { host ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text(stringResource(R.string.warning)) },
            text = { Text(stringResource(R.string.ssh_host_delete_confirm, "${host.host}:${host.port}")) },
            confirmButton = { TextButton(onClick = { deleting = null; refresh(host) }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } })
    }
    error?.let { message ->
        AlertDialog(onDismissRequest = { error = null }, title = { Text(stringResource(R.string.warning)) },
            text = { Text(message) }, confirmButton = { TextButton(onClick = { error = null }) { Text(stringResource(R.string.ok)) } })
    }
}
