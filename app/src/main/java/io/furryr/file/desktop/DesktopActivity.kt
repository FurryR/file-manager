package io.furryr.file.desktop

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import io.furryr.file.AppLocale
import io.furryr.file.R
import io.furryr.file.agent.ContainerManager
import io.furryr.file.ui.theme.FileManagerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.ConnectException

class DesktopActivity : ComponentActivity() {
    private var client: RfbClient? = null
    private var managed: ManagedDesktop? = null
    private var viewer: DesktopView? = null
    @Volatile private var generation = 0
    @Volatile private var connectionId = 0
    private var connected by mutableStateOf(false)
    private var connecting by mutableStateOf(false)
    private var status by mutableStateOf("")
    private var logs by mutableStateOf("")
    private var lastConnection: Connection? = null
    private data class Connection(val host: String, val port: Int, val password: String, val name: String? = null)

    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(AppLocale.wrap(newBase)) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("desktop", MODE_PRIVATE)
        setContent { FileManagerTheme(darkTheme = getSharedPreferences("settings", MODE_PRIVATE).getBoolean("dark_theme", true)) {
            DesktopScreen(
                initialHost = prefs.getString("host", "127.0.0.1").orEmpty(),
                initialPort = prefs.getInt("port", 5900), initialCommand = prefs.getString("command", "labwc").orEmpty(),
                initialContainer = prefs.getString("container", "").orEmpty(),
                onStart = { container, command, port ->
                    prefs.edit().putString("container", container).putString("command", command).putInt("port", port).apply()
                    startDesktop(container, command, port)
                },
                onConnect = { host, port, password ->
                    prefs.edit().putString("host", host).putInt("port", port).apply()
                    disconnect(); connect(Connection(host, port, password), generation)
                },
            )
        } }
    }
    private fun disconnect() {
        generation++
        connectionId++
        client?.close(); client?.frame?.close(); client = null
        viewer?.client = null
        managed?.close(); managed = null
        connected = false; connecting = false
        status = getString(R.string.desktop_disconnected)
    }
    private fun reconnect() {
        val settings = lastConnection ?: return
        if (settings.name != null && managed == null) { status = getString(R.string.desktop_start_again); return }
        client?.close(); client?.frame?.close(); client = null; connected = false
        connect(settings, generation)
    }
    private fun connect(settings: Connection, token: Int, attempt: Int = 0) {
        if (token != generation) return
        val socketToken = ++connectionId
        lastConnection = settings
        connecting = true
        status = getString(R.string.desktop_connecting)
        val connection = RfbClient(settings.host, settings.port, settings.password, settings.name,
            onConnected = { name -> runOnUiThread {
                if (token == generation && socketToken == connectionId) { connecting = false; connected = true; status = name; viewer?.client = client }
            } },
            onFrame = { if (token == generation && socketToken == connectionId) viewer?.postInvalidateOnAnimation() },
            onError = { failure -> runOnUiThread {
                if (token == generation && socketToken == connectionId) {
                    connected = false
                    if (settings.name != null && failure is ConnectException && attempt < 30 && managed != null) {
                        lifecycleScope.launch { delay(300); if (token == generation && socketToken == connectionId) connect(settings, token, attempt + 1) }
                    } else { connecting = false; status = failure.message ?: getString(R.string.desktop_connect_error) }
                }
            } },
        )
        client?.close(); client?.frame?.close()
        client = connection; viewer?.client = connection
        connection.start()
    }
    private fun startDesktop(container: String, command: String, port: Int) {
        disconnect(); logs = ""; connecting = true; status = getString(R.string.desktop_starting)
        val token = generation
        var ready = false
        var started = false
        var session: ManagedDesktop? = null
        fun attach() {
            val active = session ?: return
            if (ready && !started && token == generation) { started = true; connect(Connection("127.0.0.1", port, "", active.serverName), token) }
        }
        lifecycleScope.launch {
          // Complete ownership transfer even if the Activity is destroyed while
          // the daemon is spawning; the stale generation closes the result.
          withContext(NonCancellable) {
            try {
                val created = ManagedDesktop.start(applicationContext, container, command, port,
                    onLog = { chunk -> runOnUiThread { if (token == generation) logs = (logs + chunk).takeLast(16000) } },
                    onReady = { runOnUiThread { ready = true; attach() } },
                    onExit = { reason -> runOnUiThread {
                        if (token == generation) { disconnect(); status = reason }
                    } },
                )
                if (token != generation) created.close() else { session = created; managed = created; attach() }
            } catch (failure: Exception) {
                if (token == generation) { connecting = false; status = failure.message ?: getString(R.string.desktop_start_error) }
            }
          }
        }
    }
    override fun onDestroy() { disconnect(); viewer = null; lastConnection = null; super.onDestroy() }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun DesktopScreen(
        initialHost: String, initialPort: Int, initialCommand: String, initialContainer: String,
        onStart: (String, String, Int) -> Unit, onConnect: (String, Int, String) -> Unit,
    ) {
        var containers by remember { mutableStateOf<List<String>>(emptyList()) }
        var selected by remember { mutableStateOf(initialContainer) }
        var dropdown by remember { mutableStateOf(false) }
        var host by remember { mutableStateOf(initialHost) }
        var port by remember { mutableStateOf(initialPort.toString()) }
        var command by remember { mutableStateOf(initialCommand) }
        var password by remember { mutableStateOf("") }
        var showLogs by remember { mutableStateOf(false) }
        var pan by remember { mutableStateOf(false) }
        var ctrl by remember { mutableStateOf(false) }
        var alt by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            val result = withContext(Dispatchers.IO) { ContainerManager.listContainers(applicationContext) }
            result.onSuccess { list -> containers = list.map { it.name }; if (selected !in containers) selected = containers.firstOrNull().orEmpty() }
                .onFailure { status = it.message ?: getString(R.string.desktop_start_error) }
        }
        LaunchedEffect(connected) { if (!connected) { ctrl = false; alt = false } }
        Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.desktop_title)) }, navigationIcon = {
            IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cancel)) }
        }) }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (status.isNotEmpty()) Text(status, Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, maxLines = 3)
                if (connecting) LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    if (connected || connecting || managed != null) TextButton(onClick = ::disconnect) { Text(stringResource(R.string.desktop_disconnect)) }
                    if (lastConnection != null && !connecting) TextButton(onClick = ::reconnect) { Text(stringResource(R.string.desktop_reconnect)) }
                    if (logs.isNotEmpty()) TextButton(onClick = { showLogs = !showLogs }) { Text(stringResource(R.string.desktop_logs)) }
                    if (connected) {
                        TextButton(onClick = { viewer?.showKeyboard() }) { Text(stringResource(R.string.desktop_keyboard)) }
                        TextButton(onClick = { viewer?.fit() }) { Text(stringResource(R.string.desktop_fit)) }
                        FilterChip(selected = pan, onClick = { pan = !pan; viewer?.panMode = pan }, label = { Text(stringResource(R.string.desktop_pan)) })
                        FilterChip(selected = ctrl, onClick = { ctrl = !ctrl; viewer?.modifier(0xffe3, ctrl) }, label = { Text("Ctrl") })
                        FilterChip(selected = alt, onClick = { alt = !alt; viewer?.modifier(0xffe9, alt) }, label = { Text("Alt") })
                        TextButton(onClick = { viewer?.specialKey(0xff09) }) { Text("Tab") }
                        TextButton(onClick = { viewer?.specialKey(0xff1b) }) { Text("Esc") }
                        TextButton(onClick = { viewer?.specialKey(0xff0d) }) { Text("↵") }
                        TextButton(onClick = { viewer?.rightClick() }) { Text(stringResource(R.string.desktop_right_click)) }
                        TextButton(onClick = { viewer?.wheel(true) }) { Text("↑") }
                        TextButton(onClick = { viewer?.wheel(false) }) { Text("↓") }
                    }
                }
                if (showLogs) Text(logs, Modifier.fillMaxWidth().heightIn(max = 140.dp).verticalScroll(rememberScrollState()).padding(8.dp),
                    style = MaterialTheme.typography.bodySmall)
                if (connected) {
                    AndroidView(factory = { context -> DesktopView(context).also { it.client = client; it.panMode = pan; viewer = it } },
                        modifier = Modifier.weight(1f).fillMaxWidth())
                } else Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    Text(stringResource(R.string.desktop_help), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.desktop_container), style = MaterialTheme.typography.titleMedium)
                    Box {
                        OutlinedButton(onClick = { dropdown = true }, enabled = !connecting && containers.isNotEmpty()) {
                            Text(selected.ifEmpty { getString(R.string.desktop_no_container) })
                        }
                        DropdownMenu(expanded = dropdown, onDismissRequest = { dropdown = false }) {
                            containers.forEach { name -> DropdownMenuItem(text = { Text(name) }, onClick = { selected = name; dropdown = false }) }
                        }
                    }
                    OutlinedTextField(value = command, onValueChange = { command = it }, label = { Text(stringResource(R.string.desktop_command)) },
                        enabled = !connecting, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = port, onValueChange = { port = it }, label = { Text(stringResource(R.string.desktop_port)) },
                        enabled = !connecting, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                    Button(onClick = { onStart(selected, command, port.toInt()) }, enabled = !connecting && selected.isNotEmpty() &&
                        command.isNotBlank() && (port.toIntOrNull() ?: 0) in 1024..65535) { Text(stringResource(R.string.desktop_start)) }
                    HorizontalDivider(Modifier.padding(vertical = 16.dp))
                    Text(stringResource(R.string.desktop_existing), style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(value = host, onValueChange = { host = it.trim() }, label = { Text(stringResource(R.string.desktop_host)) },
                        enabled = !connecting, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text(stringResource(R.string.desktop_password)) },
                        enabled = !connecting, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.desktop_auth_help), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
                    Button(onClick = { onConnect(host, port.toInt(), password) }, enabled = !connecting && host.isNotBlank() &&
                        (port.toIntOrNull() ?: 0) in 1..65535) { Text(stringResource(R.string.desktop_connect)) }
                }
            }
        }
    }
}
