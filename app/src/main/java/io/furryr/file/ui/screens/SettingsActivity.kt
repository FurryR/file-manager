package io.furryr.file.ui.screens
import io.furryr.file.ui.theme.*
import io.furryr.file.ui.screens.*
import io.furryr.file.ui.components.*
import io.furryr.file.ui.util.*
import io.furryr.file.model.*
import io.furryr.file.ui.screens.SettingsActivity

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.furryr.file.R
import io.furryr.file.AppLocale
import io.furryr.file.agent.SessionManager
import io.furryr.file.daemon.DaemonManager
import io.furryr.file.ui.theme.FileManagerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File


private const val SHIZUKU_REQUEST_CODE = 100

class SettingsActivity : ComponentActivity() {
    private var shizukuPermissionGranted by mutableStateOf(false)
    private val shizukuListener =
        rikka.shizuku.Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_REQUEST_CODE && grantResult == PackageManager.PERMISSION_GRANTED) {
                shizukuPermissionGranted = true
            }
        }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rikka.shizuku.Shizuku.addRequestPermissionResultListener(shizukuListener)

        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        setContent {
            val darkTheme = remember { prefs.getBoolean("dark_theme", true) }
            FileManagerTheme(darkTheme = darkTheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SettingsMainPage(
                        onBack = { finish() },
                        onLicenses = { startActivity(Intent(this, LicensesActivity::class.java)) },
                        shizukuPermissionGranted = shizukuPermissionGranted,
                        onShizukuPermissionHandled = { shizukuPermissionGranted = false },
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        rikka.shizuku.Shizuku.removeRequestPermissionResultListener(shizukuListener)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsMainPage(
    onBack: () -> Unit,
    onLicenses: () -> Unit,
    shizukuPermissionGranted: Boolean,
    onShizukuPermissionHandled: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    var rootEnabled by remember { mutableStateOf(prefs.getBoolean("root_enabled", false)) }
    var shellEnabled by remember { mutableStateOf(prefs.getBoolean("shell_enabled", false)) }
    var showCustomSuDialog by remember { mutableStateOf(false) }
    var suPath by remember { mutableStateOf(prefs.getString("su_path", "") ?: "") }

    var showDockerHubUrlDialog by remember { mutableStateOf(false) }
    var dockerHubUrl by remember { mutableStateOf(prefs.getString("docker_hub_url", "https://registry-1.docker.io") ?: "https://registry-1.docker.io") }
    var showDockerHubKeyDialog by remember { mutableStateOf(false) }
    var dockerHubKey by remember { mutableStateOf(prefs.getString("docker_hub_key", "") ?: "") }
    var terminalFont by remember { mutableStateOf(prefs.getString("terminal_font", "") ?: "") }
    var showTerminalFontSizeDialog by remember { mutableStateOf(false) }
    var terminalFontSize by remember { mutableStateOf(prefs.getInt("terminal_font_size", 9).toString()) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var applyingPermissions by remember { mutableStateOf(false) }
    var pendingPermissionChange by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun applyPermissionChange(change: () -> Unit) {
        applyingPermissions = true
        scope.launch {
            try {
                // Stop Termux sessions before their daemon connections disappear.
                SessionManager.destroyAll()
                change()
                withContext(NonCancellable + Dispatchers.IO) {
                    DaemonManager.reset()
                    DaemonManager.warmUp()
                        .onFailure { Log.w("SettingsActivity", "Daemon restart failed", it) }
                }
            } finally {
                applyingPermissions = false
            }
        }
    }

    fun requestPermissionChange(change: () -> Unit) {
        if (applyingPermissions) return
        if (SessionManager.getAllSessions().isNotEmpty()) {
            pendingPermissionChange = change
        } else {
            applyPermissionChange(change)
        }
    }

    LaunchedEffect(shizukuPermissionGranted, applyingPermissions) {
        if (shizukuPermissionGranted && !applyingPermissions) {
            onShizukuPermissionHandled()
            if (shellEnabled && !DaemonManager.isRootAvailable()) {
                requestPermissionChange {}
            }
        }
    }

    fun updateRootEnabled(enabled: Boolean) {
        requestPermissionChange {
            rootEnabled = enabled
            prefs.edit().putBoolean("root_enabled", enabled).apply()
            DaemonManager.setRootEnabled(enabled)
        }
    }

    fun updateShellEnabled(enabled: Boolean) {
        requestPermissionChange {
            shellEnabled = enabled
            prefs.edit().putBoolean("shell_enabled", enabled).apply()
            DaemonManager.setShellEnabled(enabled)
            if (enabled && !DaemonManager.hasShizukuPermission()) {
                try {
                    rikka.shizuku.Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
                } catch (_: Exception) {
                    // Shizuku not installed — will fall through to direct mode
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            SettingsSection(stringResource(R.string.startup))
            SettingsToggleRow(
                title = stringResource(R.string.root_enabled),
                description = stringResource(R.string.root_enabled_desc),
                checked = rootEnabled,
                enabled = !applyingPermissions,
                onCheckedChange = ::updateRootEnabled,
            )
            SettingsDivider()
            SettingsToggleRow(
                title = stringResource(R.string.shell_enabled),
                description = stringResource(R.string.shell_enabled_desc),
                checked = shellEnabled,
                enabled = !applyingPermissions,
                onCheckedChange = ::updateShellEnabled,
            )
            SettingsDivider()
            SettingsValueRow(
                title = stringResource(R.string.custom_su),
                value = if (suPath.isNotBlank()) suPath
                else stringResource(R.string.custom_su_desc),
                enabled = !applyingPermissions,
                onClick = { showCustomSuDialog = true },
            )
            SettingsDivider()

            SettingsSection(stringResource(R.string.settings_container))
            SettingsValueRow(
                title = stringResource(R.string.settings_docker_hub_url),
                value = dockerHubUrl.ifBlank { stringResource(R.string.settings_docker_hub_url_desc) },
                onClick = { showDockerHubUrlDialog = true },
            )
            SettingsDivider()
            SettingsValueRow(
                title = stringResource(R.string.settings_docker_hub_key),
                value = if (dockerHubKey.isNotBlank()) "••••••••"
                else stringResource(R.string.settings_docker_hub_key_desc),
                onClick = { showDockerHubKeyDialog = true },
            )
            SettingsDivider()
            SettingsSection(stringResource(R.string.settings_terminal))
            AgentSettingsItem()
            SettingsDivider()
            McpSettingsItem()
            SettingsDivider()
            SessionSettingsItem()
            SettingsDivider()

            val fontPickerLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri: Uri? ->
                if (uri != null) {
                    scope.launch {
                        val path = withContext(Dispatchers.IO) {
                            val displayName = context.contentResolver.query(uri, null, null, null, null)?.use {
                                if (it.moveToFirst()) it.getString(it.getColumnIndex(OpenableColumns.DISPLAY_NAME)) else null
                            } ?: "custom_font.ttf"
                            val dest = File(context.cacheDir, "fonts/$displayName")
                            dest.parentFile?.mkdirs()
                            context.contentResolver.openInputStream(uri)?.use { input ->
                                dest.outputStream().use { output -> input.copyTo(output) }
                            }
                            dest.absolutePath
                        }
                        terminalFont = path
                        prefs.edit().putString("terminal_font", path).apply()
                    }
                }
            }
            SettingsValueRow(
                title = stringResource(R.string.settings_terminal_font),
                value = terminalFont.ifBlank { stringResource(R.string.settings_terminal_font_desc) },
                onClick = {
                    fontPickerLauncher.launch(
                        arrayOf(
                            "font/ttf",
                            "font/otf",
                            "font/x-ttf",
                            "application/x-font-ttf",
                            "application/x-font-opentype",
                        ),
                    )
                },
            )
            SettingsDivider()
            SettingsValueRow(
                title = stringResource(R.string.settings_terminal_font_size),
                value = terminalFontSize,
                onClick = { showTerminalFontSizeDialog = true },
            )
            SettingsDivider()
            SettingsSection(stringResource(R.string.settings_other))
            SettingsValueRow(
                title = stringResource(R.string.about),
                value = stringResource(R.string.about_desc),
                onClick = { showAboutDialog = true },
            )
            SettingsDivider()
            SettingsValueRow(
                title = stringResource(R.string.open_source_licenses),
                value = stringResource(R.string.open_source_licenses_desc),
                onClick = onLicenses,
            )
            SettingsDivider()
            SettingsValueRow(
                title = stringResource(R.string.language),
                value = stringResource(
                    when (AppLocale.current(context)) {
                        AppLocale.ENGLISH -> R.string.language_english
                        AppLocale.CHINESE -> R.string.language_chinese
                        else -> R.string.language_system
                    },
                ),
                onClick = { showLanguageDialog = true },
            )
        }
    }

    if (showAboutDialog) {
        AboutDialog(onDismiss = { showAboutDialog = false })
    }

    if (showLanguageDialog) {
        var selected by remember { mutableStateOf(AppLocale.current(context)) }
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = { Text(stringResource(R.string.language)) },
            text = {
                Column {
                    listOf(
                        AppLocale.SYSTEM to R.string.language_system,
                        AppLocale.ENGLISH to R.string.language_english,
                        AppLocale.CHINESE to R.string.language_chinese,
                    ).forEach { (value, label) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable { selected = value }
                        ) {
                            RadioButton(selected = selected == value, onClick = { selected = value })
                            Text(stringResource(label))
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showLanguageDialog = false }) { Text(stringResource(R.string.cancel)) }
            },
            confirmButton = {
                TextButton(onClick = {
                    showLanguageDialog = false
                    if (selected != AppLocale.current(context)) {
                        AppLocale.set(context, selected)
                        (context as? android.app.Activity)?.recreate()
                    }
                }) { Text(stringResource(R.string.confirm)) }
            },
        )
    }

    if (pendingPermissionChange != null) {
        AlertDialog(
            onDismissRequest = { pendingPermissionChange = null },
            title = { Text(stringResource(R.string.permission_change_title)) },
            text = { Text(stringResource(R.string.permission_change_close_terminals)) },
            dismissButton = {
                TextButton(onClick = { pendingPermissionChange = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val change = pendingPermissionChange ?: return@TextButton
                    pendingPermissionChange = null
                    applyPermissionChange(change)
                }) {
                    Text(stringResource(R.string.confirm))
                }
            },
        )
    }

    if (showCustomSuDialog) {
        SettingsInputDialog(
            title = stringResource(R.string.custom_su_dialog_title),
            label = stringResource(R.string.su_path),
            initialValue = suPath,
            onDismiss = { showCustomSuDialog = false },
            onConfirm = { path ->
                showCustomSuDialog = false
                if (path != suPath) {
                    requestPermissionChange {
                        suPath = path
                        prefs.edit().putString("su_path", path).apply()
                        DaemonManager.setSuPath(path)
                    }
                }
            },
        )
    }

    if (showDockerHubUrlDialog) {
        SettingsInputDialog(
            title = stringResource(R.string.settings_docker_hub_url),
            label = stringResource(R.string.settings_docker_hub_url_desc),
            initialValue = dockerHubUrl,
            onDismiss = { showDockerHubUrlDialog = false },
            onConfirm = { url ->
                dockerHubUrl = url
                prefs.edit().putString("docker_hub_url", url).apply()
                showDockerHubUrlDialog = false
            },
        )
    }

    if (showDockerHubKeyDialog) {
        SettingsInputDialog(
            title = stringResource(R.string.settings_docker_hub_key),
            label = stringResource(R.string.settings_docker_hub_key_desc),
            initialValue = dockerHubKey,
            onDismiss = { showDockerHubKeyDialog = false },
            onConfirm = { key ->
                dockerHubKey = key
                prefs.edit().putString("docker_hub_key", key).apply()
                showDockerHubKeyDialog = false
            },
        )
    }

    if (showTerminalFontSizeDialog) {
        SettingsInputDialog(
            title = stringResource(R.string.settings_terminal_font_size),
            label = stringResource(R.string.settings_terminal_font_size_desc),
            initialValue = terminalFontSize,
            filter = { value -> value.filter { it.isDigit() } },
            onDismiss = { showTerminalFontSizeDialog = false },
            onConfirm = { value ->
                val size = value.toIntOrNull()?.coerceIn(8, 128) ?: 9
                terminalFontSize = size.toString()
                prefs.edit().putInt("terminal_font_size", size).apply()
                showTerminalFontSizeDialog = false
            },
        )
    }
}

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.about)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
                Text(
                    stringResource(R.string.version, version),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(24.dp))
                Text(stringResource(R.string.about_description), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(24.dp))
                Text(stringResource(R.string.source_code), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text("https://github.com/FurryR/file-manager", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.confirm)) }
        },
    )
}
