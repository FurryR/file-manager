package io.furryr.file.graphics

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.furryr.file.AppLocale
import io.furryr.file.R
import io.furryr.file.agent.Container
import io.furryr.file.agent.ContainerManager
import io.furryr.file.core.DaemonManager
import io.furryr.file.provider.installFileProviders
import io.furryr.file.ui.theme.FileManagerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GraphicsActivity : ComponentActivity() {
    override fun attachBaseContext(base: Context) { super.attachBaseContext(AppLocale.wrap(base)) }
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); installFileProviders(this)
        val dark = getSharedPreferences("settings", MODE_PRIVATE).getBoolean("dark_theme", true)
        setContent { FileManagerTheme(darkTheme = dark) {
            val state by GraphicsManager.state.collectAsState()
            var containers by remember { mutableStateOf<List<Container>>(emptyList()) }
            var busy by remember { mutableStateOf(false) }
            var revision by remember { mutableIntStateOf(0) }
            var message by remember { mutableStateOf<String?>(null) }
            val scope = rememberCoroutineScope()
            LaunchedEffect(revision) { withContext(Dispatchers.IO) { ContainerManager.listContainers(this@GraphicsActivity) }
                .onSuccess { containers = it }.onFailure { message = it.message } }
            Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.tools_graphics)) }, navigationIcon = {
                IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
            }) }) { padding -> Column(Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.graphics_description))
                Text(stringResource(if (state.running) R.string.graphics_running else R.string.graphics_stopped))
                (message ?: state.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (busy || state.starting) LinearProgressIndicator(Modifier.fillMaxWidth())
                containers.forEach { container ->
                    val enabled = remember(revision, container.name) { GraphicsManager.isEnabled(this@GraphicsActivity, container.name) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(container.name)
                        Switch(enabled, enabled = !busy, onCheckedChange = { value ->
                            busy = true; message = null
                            scope.launch {
                                runCatching { GraphicsManager.setEnabled(this@GraphicsActivity, container.name, value) }
                                    .onFailure { message = it.message }
                                busy = false; revision++
                            }
                        })
                    }
                    if (enabled) TextButton(onClick = {
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { DaemonManager.containerServices(container.name).control("logs", listOf("file-manager-virgl.service")) }
                            message = result.getOrNull()?.text ?: result.exceptionOrNull()?.message
                        }
                    }) { Text(stringResource(R.string.graphics_logs)) }
                }
                if (containers.isEmpty()) Text(stringResource(R.string.graphics_no_containers))
            } }
        } }
    }
}
