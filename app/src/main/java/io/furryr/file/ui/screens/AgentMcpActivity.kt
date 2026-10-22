package io.furryr.file.ui.screens

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.furryr.file.AppLocale
import io.furryr.file.R
import io.furryr.file.agent.AgentSettings
import io.furryr.file.ui.theme.FileManagerTheme

class AgentMcpActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        setContent {
            FileManagerTheme(darkTheme = prefs.getBoolean("dark_theme", true)) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    McpPage(onBack = { finish() })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun McpPage(onBack: () -> Unit) {
    val context = LocalContext.current
    var servers by remember { mutableStateOf(AgentSettings.mcpServers(context)) }
    var workspace by remember { mutableStateOf(AgentSettings.workspaceMcpEnabled(context)) }
    var editing by remember { mutableStateOf<AgentSettings.McpServer?>(null) }
    var adding by remember { mutableStateOf(false) }

    fun reload() {
        servers = AgentSettings.mcpServers(context)
        workspace = AgentSettings.workspaceMcpEnabled(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.agent_mcp)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = { adding = true }) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.agent_mcp_add))
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.agent_mcp_workspace),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        stringResource(R.string.agent_mcp_workspace_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = workspace,
                    onCheckedChange = {
                        AgentSettings.setWorkspaceMcpEnabled(context, it)
                        reload()
                    },
                )
            }
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            if (servers.isEmpty()) {
                Text(
                    stringResource(R.string.agent_mcp_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            for (server in servers) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { editing = server }
                        .padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                ) {
                    Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                        Text(server.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            server.url.ifBlank {
                                (listOf(server.command) + server.args).joinToString(" ")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = server.enabled,
                        onCheckedChange = {
                            AgentSettings.setMcpServerEnabled(context, server.id, it)
                            reload()
                        },
                    )
                    TextButton(onClick = { editing = server }) {
                        Text(stringResource(R.string.agent_mcp_edit))
                    }
                    TextButton(
                        onClick = {
                            AgentSettings.deleteMcpServer(context, server.id)
                            reload()
                        },
                    ) {
                        Text(stringResource(R.string.agent_mcp_delete))
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
    }

    val initial = if (adding) null else editing
    if (adding || editing != null) {
        McpDialog(
            initial = initial,
            onDismiss = {
                adding = false
                editing = null
            },
            onSave = { server ->
                AgentSettings.saveMcpServer(context, server)
                adding = false
                editing = null
                reload()
            },
        )
    }
}

@Composable
private fun McpDialog(
    initial: AgentSettings.McpServer?,
    onDismiss: () -> Unit,
    onSave: (AgentSettings.McpServer) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var transport by remember {
        mutableStateOf(initial?.transport?.ifBlank { "http" } ?: "http")
    }
    var url by remember { mutableStateOf(initial?.url.orEmpty()) }
    var headers by remember {
        mutableStateOf(initial?.headers?.entries?.joinToString("\n") { "${it.key}: ${it.value}" }.orEmpty())
    }
    var command by remember { mutableStateOf(initial?.command.orEmpty()) }
    var args by remember { mutableStateOf(initial?.args?.joinToString(" ").orEmpty()) }
    var env by remember {
        mutableStateOf(initial?.env?.entries?.joinToString("\n") { "${it.key}=${it.value}" }.orEmpty())
    }
    var cwd by remember { mutableStateOf(initial?.cwd.orEmpty()) }
    var enabled by remember { mutableStateOf(initial?.enabled ?: true) }
    val remote = transport != "stdio"
    val valid = name.isNotBlank() && if (remote) {
        url.startsWith("http://") || url.startsWith("https://")
    } else {
        command.isNotBlank()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (initial == null) R.string.agent_mcp_add else R.string.agent_mcp_edit,
                ),
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.agent_mcp_name)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = remote,
                        onClick = { transport = "http" },
                    )
                    Text(
                        stringResource(R.string.agent_mcp_transport_http),
                        modifier = Modifier.clickable { transport = "http" },
                    )
                    RadioButton(
                        selected = !remote,
                        onClick = { transport = "stdio" },
                    )
                    Text(
                        stringResource(R.string.agent_mcp_transport_stdio),
                        modifier = Modifier.clickable { transport = "stdio" },
                    )
                }
                if (remote) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.agent_mcp_url)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = headers,
                        onValueChange = { headers = it },
                        label = { Text(stringResource(R.string.agent_mcp_headers)) },
                        supportingText = { Text(stringResource(R.string.agent_mcp_headers_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                    )
                } else {
                    OutlinedTextField(
                        value = command,
                        onValueChange = { command = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.agent_mcp_command)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = args,
                        onValueChange = { args = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.agent_mcp_args)) },
                        supportingText = { Text(stringResource(R.string.agent_mcp_args_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = env,
                        onValueChange = { env = it },
                        label = { Text(stringResource(R.string.agent_mcp_env)) },
                        supportingText = { Text(stringResource(R.string.agent_mcp_env_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                    )
                    OutlinedTextField(
                        value = cwd,
                        onValueChange = { cwd = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.agent_mcp_cwd)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(R.string.agent_mcp_enabled),
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    val envMap = env.lineSequence()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() && it.contains('=') }
                        .associate { line ->
                            val index = line.indexOf('=')
                            line.substring(0, index) to line.substring(index + 1)
                        }
                    val headerMap = headers.lineSequence()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .mapNotNull { line ->
                            val index = line.indexOf(':').takeIf { it > 0 }
                                ?: line.indexOf('=').takeIf { it > 0 }
                                ?: return@mapNotNull null
                            line.substring(0, index).trim() to line.substring(index + 1).trim()
                        }
                        .toMap()
                    onSave(
                        AgentSettings.McpServer(
                            id = initial?.id.orEmpty(),
                            name = name.trim(),
                            url = if (remote) url.trim() else "",
                            headers = if (remote) headerMap else emptyMap(),
                            command = if (remote) "" else command.trim(),
                            args = if (remote) emptyList() else args.split(' ').filter { it.isNotBlank() },
                            env = if (remote) emptyMap() else envMap,
                            cwd = if (remote) "" else cwd.trim(),
                            enabled = enabled,
                            transport = transport,
                        ),
                    )
                },
            ) { Text(stringResource(R.string.confirm)) }
        },
    )
}
