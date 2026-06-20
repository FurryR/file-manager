package io.furryr.file.ui.screens
import io.furryr.file.ui.theme.*
import io.furryr.file.ui.screens.*
import io.furryr.file.ui.components.*
import io.furryr.file.ui.util.*
import io.furryr.file.daemon.*
import io.furryr.file.model.*
import io.furryr.file.ui.screens.SettingsActivity

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.unit.dp
import io.furryr.file.R
import io.furryr.file.daemon.DaemonLauncher
import io.furryr.file.ui.theme.FileManagerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File


private enum class Screen { MAIN, LICENSES }
private const val SHIZUKU_REQUEST_CODE = 100

class SettingsActivity : ComponentActivity() {
    private val shizukuListener =
        rikka.shizuku.Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_REQUEST_CODE && grantResult == PackageManager.PERMISSION_GRANTED) {
                DaemonLauncher.reset()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rikka.shizuku.Shizuku.addRequestPermissionResultListener(shizukuListener)

        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        setContent {
            val darkTheme = remember { prefs.getBoolean("dark_theme", true) }
            FileManagerTheme(darkTheme = darkTheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var currentScreen by remember { mutableStateOf(Screen.MAIN) }

                    BackHandler(currentScreen != Screen.MAIN) {
                        currentScreen = Screen.MAIN
                    }

                    when (currentScreen) {
                        Screen.MAIN -> SettingsMainPage(
                            onBack = { finish() },
                            onLicenses = { currentScreen = Screen.LICENSES }
                        )
                        Screen.LICENSES -> LicensesPage(
                            onBack = { currentScreen = Screen.MAIN }
                        )
                    }
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
private fun SettingsMainPage(onBack: () -> Unit, onLicenses: () -> Unit) {
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
    var terminalFontSize by remember { mutableStateOf(prefs.getInt("terminal_font_size", 24).toString()) }
    val scope = rememberCoroutineScope()

    fun updateRootEnabled(enabled: Boolean) {
        rootEnabled = enabled
        prefs.edit().putBoolean("root_enabled", enabled).apply()
        DaemonLauncher.setRootEnabled(enabled)
        DaemonLauncher.reset()
    }

    fun updateShellEnabled(enabled: Boolean) {
        shellEnabled = enabled
        prefs.edit().putBoolean("shell_enabled", enabled).apply()
        if (enabled && !DaemonLauncher.hasShizukuPermission()) {
            try {
                rikka.shizuku.Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
            } catch (_: Exception) {
                // Shizuku not installed — will fall through to direct mode
            }
        }
        DaemonLauncher.setShellEnabled(enabled)
        DaemonLauncher.reset()
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
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
            // Section: Startup
            Text(
                text = stringResource(R.string.startup),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

            // Root permission toggle
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { updateRootEnabled(!rootEnabled) }
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.root_enabled),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = stringResource(R.string.root_enabled_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = rootEnabled,
                    onCheckedChange = ::updateRootEnabled
                )
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // Shell permission toggle
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { updateShellEnabled(!shellEnabled) }
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.shell_enabled),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = stringResource(R.string.shell_enabled_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = shellEnabled,
                    onCheckedChange = ::updateShellEnabled
                )
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // Custom su command
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showCustomSuDialog = true }
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.custom_su),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = if (suPath.isNotBlank()) suPath
                            else stringResource(R.string.custom_su_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // Section: Container
            Text(
                text = stringResource(R.string.settings_container),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showDockerHubUrlDialog = true }
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_docker_hub_url),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = dockerHubUrl.ifBlank { stringResource(R.string.settings_docker_hub_url_desc) },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showDockerHubKeyDialog = true }
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_docker_hub_key),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = if (dockerHubKey.isNotBlank()) "••••••••" else stringResource(R.string.settings_docker_hub_key_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // Section: Terminal
            Text(
                text = stringResource(R.string.settings_terminal),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { fontPickerLauncher.launch(arrayOf("font/ttf", "font/otf", "font/x-ttf", "application/x-font-ttf", "application/x-font-opentype")) }
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_terminal_font),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = terminalFont.ifBlank { stringResource(R.string.settings_terminal_font_desc) },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showTerminalFontSizeDialog = true }
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_terminal_font_size),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = terminalFontSize,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // Open source licenses
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onLicenses)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Text(
                    text = stringResource(R.string.open_source_licenses),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    // Custom su dialog
    if (showCustomSuDialog) {
        var dialogSuPath by remember { mutableStateOf(suPath) }
        AlertDialog(
            onDismissRequest = { showCustomSuDialog = false },
            title = { Text(stringResource(R.string.custom_su_dialog_title)) },
            text = {
                OutlinedTextField(
                    value = dialogSuPath,
                    onValueChange = { dialogSuPath = it },
                    label = { Text(stringResource(R.string.su_path)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            dismissButton = {
                TextButton(onClick = { showCustomSuDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        suPath = dialogSuPath
                        prefs.edit().putString("su_path", dialogSuPath).apply()
                        DaemonLauncher.setSuPath(dialogSuPath)
                        DaemonLauncher.reset()
                        showCustomSuDialog = false
                    }
                ) {
                    Text(stringResource(R.string.confirm))
                }
            }
        )
    }

    // Docker Hub address dialog
    if (showDockerHubUrlDialog) {
        var dialogUrl by remember { mutableStateOf(dockerHubUrl) }
        AlertDialog(
            onDismissRequest = { showDockerHubUrlDialog = false },
            title = { Text(stringResource(R.string.settings_docker_hub_url)) },
            text = {
                OutlinedTextField(
                    value = dialogUrl,
                    onValueChange = { dialogUrl = it },
                    label = { Text(stringResource(R.string.settings_docker_hub_url_desc)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            dismissButton = {
                TextButton(onClick = { showDockerHubUrlDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        dockerHubUrl = dialogUrl
                        prefs.edit().putString("docker_hub_url", dialogUrl).apply()
                        showDockerHubUrlDialog = false
                    }
                ) {
                    Text(stringResource(R.string.confirm))
                }
            }
        )
    }

    // Docker Hub key dialog
    if (showDockerHubKeyDialog) {
        var dialogKey by remember { mutableStateOf(dockerHubKey) }
        AlertDialog(
            onDismissRequest = { showDockerHubKeyDialog = false },
            title = { Text(stringResource(R.string.settings_docker_hub_key)) },
            text = {
                OutlinedTextField(
                    value = dialogKey,
                    onValueChange = { dialogKey = it },
                    label = { Text(stringResource(R.string.settings_docker_hub_key_desc)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            dismissButton = {
                TextButton(onClick = { showDockerHubKeyDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        dockerHubKey = dialogKey
                        prefs.edit().putString("docker_hub_key", dialogKey).apply()
                        showDockerHubKeyDialog = false
                    }
                ) {
                    Text(stringResource(R.string.confirm))
                }
            }
        )
    }

    // Terminal font size dialog
    if (showTerminalFontSizeDialog) {
        var dialogSize by remember { mutableStateOf(terminalFontSize) }
        AlertDialog(
            onDismissRequest = { showTerminalFontSizeDialog = false },
            title = { Text(stringResource(R.string.settings_terminal_font_size)) },
            text = {
                OutlinedTextField(
                    value = dialogSize,
                    onValueChange = { dialogSize = it.filter { c -> c.isDigit() } },
                    label = { Text(stringResource(R.string.settings_terminal_font_size_desc)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            dismissButton = {
                TextButton(onClick = { showTerminalFontSizeDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val size = dialogSize.toIntOrNull()?.coerceIn(8, 128) ?: 24
                        terminalFontSize = size.toString()
                        prefs.edit().putInt("terminal_font_size", size).apply()
                        showTerminalFontSizeDialog = false
                    }
                ) {
                    Text(stringResource(R.string.confirm))
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LicensesPage(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.licenses_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                }
            )
        }
    ) { padding ->
        LicensesScreen(modifier = Modifier.padding(padding))
    }
}
