package io.furryr.file.ui.screens

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
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
import io.furryr.file.store.IdentityRepository
import io.furryr.file.store.LocationStores
import io.furryr.file.store.RemoteLocationStore
import io.furryr.file.ui.components.IdentityEditorDialog
import io.furryr.file.ui.icons.Icons
import io.furryr.file.ui.theme.FileManagerTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SshKeysActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLocale.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        setContent {
            FileManagerTheme(darkTheme = prefs.getBoolean("dark_theme", true)) {
                Surface(Modifier.fillMaxSize()) { SshKeysPage { finish() } }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SshKeysPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var identities by remember { mutableStateOf(emptyList<RemoteLocationStore.Identity>()) }
    var editing by remember { mutableStateOf<RemoteLocationStore.Identity?>(null) }
    var deleting by remember { mutableStateOf<RemoteLocationStore.Identity?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun change(rejectedRes: Int, failureRes: Int, action: () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                identities = withContext(Dispatchers.IO) {
                    LocationStores.init(context)
                    action()
                    IdentityRepository.identities()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = context.getString(
                    if (failure is IllegalArgumentException) rejectedRes else failureRes,
                )
            } finally {
                busy = false
            }
        }
    }
    LaunchedEffect(Unit) { change(R.string.identity_store_failed, R.string.identity_store_failed) {} }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.ssh_keys)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
            actions = {
                IconButton(
                    onClick = { editing = RemoteLocationStore.Identity(name = "", password = "") },
                    enabled = !busy,
                ) { Icon(Icons.Default.Add, stringResource(R.string.ssh_key_add)) }
            },
        )
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            items(identities, key = { it.id }) { identity ->
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = !busy) { editing = identity }
                        .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(identity.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(
                                if (identity.usesPrivateKey) R.string.identity_private_key
                                else R.string.identity_password,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { deleting = identity }, enabled = !busy) {
                        Text(stringResource(R.string.delete))
                    }
                }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            }
        }
    }
    editing?.let { identity ->
        IdentityEditorDialog(
            initial = identity,
            onDismiss = { editing = null },
            onSave = { value ->
                editing = null
                change(R.string.identity_save_failed, R.string.identity_store_failed) {
                    IdentityRepository.save(value)
                }
            },
        )
    }
    deleting?.let { identity ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.warning)) },
            text = { Text(stringResource(R.string.ssh_key_delete_confirm, identity.name)) },
            confirmButton = { TextButton(onClick = {
                deleting = null
                change(R.string.identity_delete_in_use, R.string.identity_store_failed) {
                    IdentityRepository.delete(identity.id)
                }
            }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    error?.let { message ->
        AlertDialog(onDismissRequest = { error = null }, title = { Text(stringResource(R.string.warning)) },
            text = { Text(message) }, confirmButton = { TextButton(onClick = { error = null }) { Text(stringResource(R.string.ok)) } })
    }
}
