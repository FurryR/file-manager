package io.furryr.file.ui.screens

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.furryr.file.AppLocale
import io.furryr.file.MainActivity
import io.furryr.file.R
import io.furryr.file.picker.FilePickerActivity
import io.furryr.file.ui.components.ApkAction
import io.furryr.file.ui.components.ApkDetails
import io.furryr.file.ui.components.ApkDialog
import io.furryr.file.ui.components.loadApkDetails
import io.furryr.file.ui.icons.Icons
import io.furryr.file.ui.theme.FileManagerTheme
import io.furryr.file.ui.util.formatSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class InstalledAppsActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLocale.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        setContent {
            FileManagerTheme(darkTheme = prefs.getBoolean("dark_theme", true)) {
                Surface(Modifier.fillMaxSize()) { InstalledAppsPage { finish() } }
            }
        }
    }
}

private enum class AppSort(val label: Int) {
    Name(R.string.name_label), Package(R.string.apk_package_name), Size(R.string.apk_size),
    FirstInstall(R.string.apk_first_install), LastUpdate(R.string.apk_last_update),
}

/** Rows published per parsed batch; small enough to stream, large enough to avoid churn. */
private const val BatchSize = 16

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InstalledAppsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf(emptyList<ApkDetails>()) }
    var generation by remember { mutableIntStateOf(-1) }
    var system by rememberSaveable { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(AppSort.Name) }
    var sortMenu by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<ApkDetails?>(null) }
    var exportPath by rememberSaveable { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var exporting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(searching) {
        if (searching) searchFocus.requestFocus()
    }

    LifecycleResumeEffect(Unit) {
        generation++
        onPauseOrDispose { }
    }

    // Installing or removing an app while this screen is open must update the
    // list; the resume effect only covers returning from another screen.
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                generation++
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }

    LaunchedEffect(generation) {
        if (generation < 0) return@LaunchedEffect
        loading = true
        error = null
        apps = emptyList()
        try {
            withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                val packages = context.packageManager.getInstalledPackages(0)
                // Publish in small batches so rows appear while later packages
                // are still being parsed instead of after the whole list.
                val batch = ArrayList<ApkDetails>(BatchSize)
                for ((index, info) in packages.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    val app = info.applicationInfo
                    if (app != null) {
                        val details = try {
                            loadApkDetails(context, File(app.sourceDir), info)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            null
                        }
                        if (details != null) batch += details
                    }
                    if (batch.size >= BatchSize || index == packages.lastIndex) {
                        if (batch.isNotEmpty()) {
                            val publish = ArrayList(batch)
                            batch.clear()
                            withContext(Dispatchers.Main.immediate) { apps = apps + publish }
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: failure.toString()
        } finally { loading = false }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val source = exportPath
        exportPath = null
        val uri = result.data?.data?.takeIf { result.resultCode == android.app.Activity.RESULT_OK }
        if (source != null && uri != null) {
            exporting = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        File(source).inputStream().use { input ->
                            context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                                val buffer = ByteArray(256 * 1024)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    output.write(buffer, 0, count)
                                }
                            } ?: error("Cannot write APK")
                        }
                    }
                    Toast.makeText(context, R.string.apk_exported, Toast.LENGTH_SHORT).show()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    error = failure.message ?: failure.toString()
                } finally { exporting = false }
            }
        }
    }

    val visible by produceState(emptyList<ApkDetails>(), apps, system, query, sort) {
        value = withContext(Dispatchers.Default) {
            val filtered = apps.filter { apk ->
                val flags = apk.info.applicationInfo?.flags ?: 0
                val isSystem = flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                isSystem == system && (apk.label.contains(query, true) || apk.info.packageName.contains(query, true))
            }
            when (sort) {
                AppSort.Name -> filtered.sortedBy { it.label.lowercase(Locale.ROOT) }
                AppSort.Package -> filtered.sortedBy { it.info.packageName.lowercase(Locale.ROOT) }
                AppSort.Size -> filtered.sortedByDescending { it.size }
                AppSort.FirstInstall -> filtered.sortedByDescending { it.info.firstInstallTime }
                AppSort.LastUpdate -> filtered.sortedByDescending { it.info.lastUpdateTime }
            }
        }
    }

    fun navigate(path: String) {
        selected = null
        context.startActivity(Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_OPEN_PATH, path)
            putExtra(MainActivity.EXTRA_OPEN_NEW_TAB, true)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
    }

    Scaffold(topBar = {
        TopAppBar(
            title = {
                if (searching) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.titleMedium,
                        placeholder = { Text(stringResource(R.string.installed_apps_search)) },
                        modifier = Modifier.fillMaxWidth().focusRequester(searchFocus),
                    )
                } else {
                    Column {
                        Text(stringResource(R.string.installed_apps))
                        if (visible.isNotEmpty()) {
                            Text(
                                stringResource(R.string.installed_apps_count, visible.size),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
            actions = {
                IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                    Icon(
                        if (searching) Icons.Default.Close else Icons.Default.Search,
                        stringResource(R.string.installed_apps_search),
                    )
                }
                if (!searching) {
                    Box {
                        IconButton(onClick = { sortMenu = true }) {
                            Icon(Icons.AutoMirrored.Filled.Sort, stringResource(R.string.installed_apps_sort))
                        }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            AppSort.entries.forEach { choice ->
                                DropdownMenuItem(text = { Text(stringResource(choice.label)) }, onClick = { sort = choice; sortMenu = false })
                            }
                        }
                    }
                }
            },
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = if (system) 1 else 0) {
                Tab(
                    selected = !system,
                    onClick = { system = false },
                    text = { Text(stringResource(R.string.installed_apps_user)) },
                )
                Tab(
                    selected = system,
                    onClick = { system = true },
                    text = { Text(stringResource(R.string.installed_apps_system)) },
                )
            }
            if (loading || exporting) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.weight(1f)) {
                items(visible, key = { it.info.packageName }) { apk ->
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = !exporting) { selected = apk }.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        val icon = apk.icon
                        if (icon != null) {
                            Image(icon, null, Modifier.size(48.dp))
                        } else {
                            Icon(Icons.Default.Description, null, Modifier.size(48.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(apk.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${apk.info.versionName.orEmpty()}  ${formatSize(apk.size)}", style = MaterialTheme.typography.bodySmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(apk.info.packageName, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
    selected?.let { apk ->
        ApkDialog(apk, installedMode = true, onNavigate = ::navigate, onDismiss = { selected = null }, onAction = { action ->
            try {
                val packageUri = Uri.fromParts("package", apk.info.packageName, null)
                when (action) {
                    ApkAction.Launch -> {
                        val intent = context.packageManager.getLaunchIntentForPackage(apk.info.packageName)
                            ?: error(context.getString(R.string.apk_cannot_launch))
                        context.startActivity(intent)
                    }
                    ApkAction.Details -> context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri))
                    ApkAction.Uninstall -> {
                        @Suppress("DEPRECATION")
                        val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE, packageUri)
                        context.startActivity(intent)
                    }
                    ApkAction.Extract -> {
                        exportPath = requireNotNull(apk.info.applicationInfo).sourceDir
                        val filename = "${apk.info.packageName}_${apk.info.versionName.orEmpty()}".replace('/', '_').replace('\\', '_') + ".apk"
                        picker.launch(Intent(context, FilePickerActivity::class.java).apply {
                            this.action = Intent.ACTION_CREATE_DOCUMENT
                            type = "application/vnd.android.package-archive"
                            addCategory(Intent.CATEGORY_OPENABLE)
                            putExtra(Intent.EXTRA_TITLE, filename)
                        })
                    }
                    else -> Unit
                }
                selected = null
            } catch (failure: Exception) {
                error = failure.message ?: failure.toString()
            }
        })
    }
    error?.let { message ->
        AlertDialog(onDismissRequest = { error = null }, title = { Text(stringResource(R.string.warning)) },
            text = { Text(message) }, confirmButton = { TextButton(onClick = { error = null }) { Text(stringResource(R.string.ok)) } })
    }
}
