package io.furryr.file.ui.screens

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import io.furryr.file.AppLocale
import io.furryr.file.R
import io.furryr.file.adblock.AdBlockEngine
import io.furryr.file.adblock.AdBlockList
import io.furryr.file.adblock.AdBlockSettings
import io.furryr.file.picker.FilePickerActivity
import io.furryr.file.ui.icons.Icons
import io.furryr.file.ui.theme.FileManagerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * Browse → Ad blocking. Enables the WebView request filter and manages the
 * filter lists: built-in downloads, user URLs and imported EasyList/hosts
 * files. Bodies and the compiled engine stay in app-private storage.
 */
class AdBlockSettingsActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        setContent {
            FileManagerTheme(darkTheme = prefs.getBoolean("dark_theme", true)) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AdBlockPage(onBack = { finish() })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdBlockPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var enabled by remember { mutableStateOf(AdBlockSettings.isEnabled(context)) }
    var lists by remember { mutableStateOf(AdBlockSettings.lists(context)) }
    var lastUpdate by remember { mutableStateOf(AdBlockSettings.lastUpdate(context)) }
    var busy by remember { mutableStateOf(false) }
    var busyText by remember { mutableStateOf<String?>(null) }
    var dialogOpen by remember { mutableStateOf(false) }
    var addMenuOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<AdBlockList?>(null) }

    fun invalidateCache() {
        AdBlockSettings.engineCache(context).delete()
        AdBlockEngine.clear()
        AdBlockEngine.resetAttempt()
    }

    fun rebuild() {
        busy = true
        busyText = context.getString(R.string.adblock_rebuilding)
        AdBlockEngine.rebuildAsync(context) { result ->
            mainHandler.post {
                busy = false
                busyText = null
                if (result.isFailure) {
                    Toast.makeText(context, R.string.adblock_build_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun update() {
        if (busy) return
        busy = true
        busyText = context.getString(R.string.adblock_updating)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { AdBlockSettings.update(context) }
            }
            busy = false
            busyText = null
            lastUpdate = AdBlockSettings.lastUpdate(context)
            result.onSuccess { report ->
                if (report.failed > 0) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.adblock_update_partial, report.failed),
                        Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    Toast.makeText(context, R.string.adblock_update_done, Toast.LENGTH_SHORT).show()
                }
            }.onFailure {
                Toast.makeText(context, R.string.adblock_update_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun refreshLists() {
        lists = AdBlockSettings.lists(context)
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        if (busy) return@rememberLauncherForActivityResult
        busy = true
        busyText = context.getString(R.string.adblock_importing)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { AdBlockSettings.importFile(context, uri) }
            }
            busy = false
            busyText = null
            result.onSuccess { list ->
                refreshLists()
                if (enabled) rebuild() else invalidateCache()
                Toast.makeText(
                    context,
                    context.getString(R.string.adblock_import_done, list.title),
                    Toast.LENGTH_SHORT,
                ).show()
            }.onFailure {
                Toast.makeText(context, R.string.adblock_import_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.adblock_title)) },
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
                                contentDescription = stringResource(R.string.adblock_add_list),
                            )
                        }
                        DropdownMenu(
                            expanded = addMenuOpen,
                            onDismissRequest = { addMenuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.adblock_add_file)) },
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
                                text = { Text(stringResource(R.string.adblock_add_url)) },
                                onClick = {
                                    addMenuOpen = false
                                    editing = null
                                    dialogOpen = true
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
            if (busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            SettingsToggleRow(
                title = stringResource(R.string.adblock_enable),
                description = busyText ?: stringResource(R.string.adblock_enable_desc),
                checked = enabled,
                enabled = !busy,
                onCheckedChange = { value ->
                    enabled = value
                    AdBlockSettings.setEnabled(context, value)
                    if (value) {
                        if (AdBlockSettings.hasRules(context)) {
                            AdBlockEngine.ensureLoaded(context, force = true)
                        } else {
                            update()
                        }
                    } else {
                        // Keep the compiled cache: turning blocking back on
                        // reloads it instead of re-parsing every list.
                        AdBlockEngine.clear()
                    }
                },
            )
            SettingsDivider()
            SettingsValueRow(
                title = stringResource(R.string.adblock_update),
                value = when {
                    busyText != null -> busyText.orEmpty()
                    lastUpdate > 0L -> stringResource(
                        R.string.adblock_last_update,
                        formatUpdated(lastUpdate),
                    )
                    else -> stringResource(R.string.adblock_never)
                },
                enabled = !busy,
                onClick = ::update,
            )
            SettingsDivider()
            SettingsSection(stringResource(R.string.adblock_lists))
            for (list in lists) {
                FilterListRow(
                    list = list,
                    enabled = !busy,
                    onToggle = { checked ->
                        AdBlockSettings.setListEnabled(context, list.id, checked)
                        refreshLists()
                        if (enabled) rebuild() else invalidateCache()
                    },
                    onEdit = if (!list.builtIn && list.url != null) {
                        {
                            editing = list
                            dialogOpen = true
                        }
                    } else {
                        null
                    },
                    onDelete = if (!list.builtIn) {
                        {
                            AdBlockSettings.deleteList(context, list.id)
                            refreshLists()
                            if (enabled) rebuild() else invalidateCache()
                        }
                    } else {
                        null
                    },
                )
                SettingsDivider()
            }
        }
    }

    if (dialogOpen) {
        ListDialog(
            initial = editing,
            onDismiss = { dialogOpen = false },
            onSave = { list ->
                AdBlockSettings.saveList(context, list)
                refreshLists()
                dialogOpen = false
                if (enabled) rebuild() else invalidateCache()
            },
        )
    }
}

@Composable
private fun FilterListRow(
    list: AdBlockList,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onToggle(!list.enabled) }
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(list.title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = list.url ?: stringResource(R.string.adblock_local_list),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onEdit != null) {
            IconButton(onClick = onEdit, enabled = enabled) {
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = stringResource(R.string.agent_provider_edit),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (onDelete != null) {
            IconButton(onClick = onDelete, enabled = enabled) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.delete),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
        Switch(
            checked = list.enabled,
            enabled = enabled,
            onCheckedChange = onToggle,
        )
    }
}

@Composable
private fun ListDialog(
    initial: AdBlockList?,
    onDismiss: () -> Unit,
    onSave: (AdBlockList) -> Unit,
) {
    var title by remember { mutableStateOf(initial?.title.orEmpty()) }
    var url by remember { mutableStateOf(initial?.url.orEmpty()) }
    var hosts by remember { mutableStateOf(initial?.hosts ?: false) }
    var error by remember { mutableStateOf<String?>(null) }
    val nameRequired = stringResource(R.string.search_engine_name_required)
    val urlInvalid = stringResource(R.string.adblock_url_invalid)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (initial == null) R.string.adblock_add_list else R.string.adblock_edit_list,
                ),
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.search_engine_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.adblock_list_url)) },
                    placeholder = { Text("https://example.com/list.txt") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { hosts = !hosts },
                ) {
                    Checkbox(checked = hosts, onCheckedChange = { hosts = it })
                    Text(stringResource(R.string.adblock_hosts_file))
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
        confirmButton = {
            TextButton(onClick = {
                val name = title.trim()
                val address = url.trim()
                error = when {
                    name.isEmpty() -> nameRequired
                    !AdBlockSettings.isValidUrl(address) -> urlInvalid
                    else -> null
                }
                if (error == null) {
                    onSave(
                        AdBlockList(
                            id = initial?.id ?: AdBlockSettings.newId(),
                            title = name,
                            url = address,
                            enabled = initial?.enabled ?: true,
                            builtIn = false,
                            hosts = hosts,
                        ),
                    )
                }
            }) { Text(stringResource(R.string.editor_save)) }
        },
    )
}

private fun formatUpdated(time: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(time))
