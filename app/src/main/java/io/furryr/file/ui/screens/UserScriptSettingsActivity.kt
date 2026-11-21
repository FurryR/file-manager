package io.furryr.file.ui.screens

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.furryr.file.AppLocale
import io.furryr.file.MainActivity
import io.furryr.file.R
import io.furryr.file.picker.FilePickerActivity
import io.furryr.file.ui.icons.Icons
import io.furryr.file.ui.theme.FileManagerTheme
import io.furryr.file.userscript.UserScript
import io.furryr.file.userscript.UserScriptDraft
import io.furryr.file.userscript.UserScriptEngine
import io.furryr.file.userscript.UserScriptStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Browse → User scripts. Installs from a URL, enables/disables scripts and
 * opens a script's source in the editor. Bodies live under
 * `filesDir/userscripts/`; metadata lives in `userscript_settings`.
 */
class UserScriptSettingsActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        setContent {
            FileManagerTheme(darkTheme = prefs.getBoolean("dark_theme", true)) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    UserScriptPage(onBack = { finish() })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UserScriptPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var scripts by remember { mutableStateOf(UserScriptStore.scripts(context)) }
    var busy by remember { mutableStateOf(false) }
    var busyText by remember { mutableStateOf<String?>(null) }
    var addMenuOpen by remember { mutableStateOf(false) }
    var urlOpen by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<UserScriptDraft?>(null) }
    var pendingReplace by remember { mutableStateOf<UserScript?>(null) }
    var deleteTarget by remember { mutableStateOf<UserScript?>(null) }
    var menuFor by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        scripts = UserScriptStore.scripts(context)
    }

    fun fail(message: Int) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    fun openEditor(script: UserScript) {
        val path = UserScriptStore.scriptPath(context, script.id).absolutePath
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_PATH, path)
                .putExtra(MainActivity.EXTRA_OPEN_NEW_TAB, true),
        )
    }

    fun createBlank() {
        if (busy) return
        busy = true
        busyText = context.getString(R.string.userscripts_installing)
        val name = context.getString(R.string.userscripts_new)
        val description = context.getString(R.string.userscripts_new_desc)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { UserScriptStore.createBlank(context, name, description) }
            }
            busy = false
            busyText = null
            result.onSuccess { script ->
                runCatching { UserScriptEngine.refresh(context) }
                refresh()
                openEditor(script)
            }.onFailure {
                fail(R.string.userscripts_install_failed)
            }
        }
    }

    LifecycleResumeEffect(Unit) {
        runCatching { UserScriptEngine.refresh(context) }
        refresh()
        onPauseOrDispose { }
    }

    fun fetch(url: String) {
        if (busy) return
        busy = true
        busyText = context.getString(R.string.userscripts_fetching)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { UserScriptStore.fetch(url) }
            }
            busy = false
            busyText = null
            result.onSuccess { draft ->
                pendingReplace = UserScriptStore.conflict(context, draft)
                pending = draft
            }.onFailure {
                fail(R.string.userscripts_install_failed)
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        if (busy) return@rememberLauncherForActivityResult
        busy = true
        busyText = context.getString(R.string.userscripts_fetching)
        scope.launch {
            val parsed = withContext(Dispatchers.IO) {
                runCatching { UserScriptStore.read(context, uri) }
            }
            busy = false
            busyText = null
            parsed.onSuccess { draft ->
                pendingReplace = UserScriptStore.conflict(context, draft)
                pending = draft
            }.onFailure {
                fail(R.string.userscripts_install_failed)
            }
        }
    }

    fun install(draft: UserScriptDraft, replace: UserScript?) {
        if (busy) return
        busy = true
        busyText = context.getString(R.string.userscripts_installing)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { UserScriptEngine.install(context, draft, replace?.id) }
            }
            busy = false
            busyText = null
            pending = null
            pendingReplace = null
            result.onSuccess { script ->
                refresh()
                Toast.makeText(
                    context,
                    context.getString(R.string.userscripts_install_done, script.name),
                    Toast.LENGTH_SHORT,
                ).show()
            }.onFailure {
                fail(R.string.userscripts_install_failed)
            }
        }
    }

    fun update(script: UserScript) {
        if (busy) return
        busy = true
        busyText = context.getString(R.string.userscripts_installing)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { UserScriptEngine.update(context, script.id) }
            }
            busy = false
            busyText = null
            result.onSuccess { updated ->
                refresh()
                Toast.makeText(
                    context,
                    context.getString(R.string.userscripts_update_done, updated.name),
                    Toast.LENGTH_SHORT,
                ).show()
            }.onFailure {
                fail(R.string.userscripts_update_failed)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.userscripts_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    Box {
                        IconButton(
                            enabled = !busy,
                            onClick = { addMenuOpen = true },
                        ) {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = stringResource(R.string.userscripts_add),
                            )
                        }
                        DropdownMenu(
                            expanded = addMenuOpen,
                            onDismissRequest = { addMenuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.userscripts_new)) },
                                leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                                onClick = {
                                    addMenuOpen = false
                                    createBlank()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.userscripts_import_file)) },
                                leadingIcon = {
                                    Icon(
                                        Icons.AutoMirrored.Filled.InsertDriveFile,
                                        contentDescription = null,
                                    )
                                },
                                onClick = {
                                    addMenuOpen = false
                                    importLauncher.launch(
                                        Intent(context, FilePickerActivity::class.java).apply {
                                            action = Intent.ACTION_OPEN_DOCUMENT
                                            type = "*/*"
                                            addCategory(Intent.CATEGORY_OPENABLE)
                                        },
                                    )
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.userscripts_install_url)) },
                                leadingIcon = { Icon(Icons.Filled.Link, contentDescription = null) },
                                onClick = {
                                    addMenuOpen = false
                                    urlOpen = true
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            busyText?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (scripts.isEmpty()) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                ) {
                    Icon(
                        Icons.Filled.Code,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(64.dp),
                    )
                    Text(
                        text = stringResource(R.string.userscripts_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            } else {
                SettingsSection(stringResource(R.string.userscripts_installed))
                for (script in scripts) {
                    UserScriptRow(
                        script = script,
                        enabled = !busy,
                        menuOpen = menuFor == script.id,
                        onMenuOpen = { menuFor = script.id },
                        onMenuDismiss = { menuFor = null },
                        onToggle = { checked ->
                            UserScriptEngine.setScriptEnabled(context, script.id, checked)
                            refresh()
                        },
                        onUpdate = {
                            menuFor = null
                            update(script)
                        },
                        onEdit = {
                            menuFor = null
                            openEditor(script)
                        },
                        onDelete = {
                            menuFor = null
                            deleteTarget = script
                        },
                    )
                    SettingsDivider()
                }
            }
        }
    }

    if (urlOpen) {
        InstallUrlDialog(
            onDismiss = { if (!busy) urlOpen = false },
            onInstall = { url ->
                urlOpen = false
                fetch(url)
            },
        )
    }

    val draft = pending
    if (draft != null) {
        InstallConfirmDialog(
            draft = draft,
            replace = pendingReplace,
            busy = busy,
            onDismiss = {
                if (!busy) {
                    pending = null
                    pendingReplace = null
                }
            },
            onConfirm = { install(draft, pendingReplace) },
        )
    }

    val target = deleteTarget
    if (target != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.userscripts_delete)) },
            text = {
                Text(stringResource(R.string.userscripts_delete_confirm, target.name))
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    UserScriptEngine.delete(context, target.id)
                    refresh()
                }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun UserScriptRow(
    script: UserScript,
    enabled: Boolean,
    menuOpen: Boolean,
    onMenuOpen: () -> Unit,
    onMenuDismiss: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onUpdate: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onEdit() }
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Icon(
            Icons.Filled.Code,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 12.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(script.name, style = MaterialTheme.typography.bodyLarge)
            val subtitle = buildString {
                if (script.version.isNotBlank()) append(script.version)
                val pattern = (script.matches + script.includes).firstOrNull()
                if (!pattern.isNullOrBlank()) {
                    if (isNotEmpty()) append(" · ")
                    append(pattern)
                }
            }
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Switch(
            checked = script.enabled,
            enabled = enabled,
            onCheckedChange = onToggle,
        )
        Box {
            IconButton(enabled = enabled, onClick = onMenuOpen) {
                Icon(Icons.Filled.MoreVert, contentDescription = null)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = onMenuDismiss) {
                if (script.sourceUrl.isNotBlank()) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.userscripts_update)) },
                        onClick = onUpdate,
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.userscripts_delete)) },
                    onClick = onDelete,
                )
            }
        }
    }
}

@Composable
private fun InstallUrlDialog(onDismiss: () -> Unit, onInstall: (String) -> Unit) {
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.userscripts_install_url)) },
        text = {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                singleLine = true,
                label = { Text(stringResource(R.string.userscripts_url_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                enabled = url.trim().startsWith("http://", true) ||
                    url.trim().startsWith("https://", true),
                onClick = { onInstall(url.trim()) },
            ) {
                Text(stringResource(R.string.userscripts_install))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun InstallConfirmDialog(
    draft: UserScriptDraft,
    replace: UserScript?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val metadata = draft.metadata
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(metadata.name) },
        text = {
            Column {
                if (metadata.version.isNotBlank()) {
                    Text(stringResource(R.string.userscripts_version, metadata.version))
                }
                if (metadata.description.isNotBlank()) {
                    Text(metadata.description)
                }
                if (replace != null) {
                    Text(
                        text = stringResource(R.string.userscripts_replace),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                val patterns = metadata.matches + metadata.includes
                if (patterns.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.userscripts_matches) + ": " +
                            patterns.take(6).joinToString(", ") +
                            if (patterns.size > 6) "…" else "",
                    )
                }
                if (metadata.grants.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.userscripts_grants) + ": " +
                            metadata.grants.joinToString(", "),
                    )
                }
                if (metadata.connects.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.userscripts_connects) + ": " +
                            metadata.connects.joinToString(", "),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = onConfirm) {
                Text(stringResource(R.string.userscripts_install))
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
