package io.furryr.file.agent

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.furryr.file.AppLocale
import io.furryr.file.R
import io.furryr.file.core.ContainerServiceStatus
import io.furryr.file.core.DaemonManager
import io.furryr.file.provider.installFileProviders
import io.furryr.file.ui.theme.FileManagerTheme
import kotlinx.coroutines.launch

class ContainerServicesActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(AppLocale.wrap(newBase)) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installFileProviders(this)
        setContent { FileManagerTheme(darkTheme = true) { ServicesScreen(this, onBack = { finish() }) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServicesScreen(context: Context, onBack: () -> Unit) {
    val containers = remember { ContainerManager.listContainers(context).getOrDefault(emptyList()) }
    var selected by remember { mutableStateOf(containers.firstOrNull()?.name.orEmpty()) }
    var menu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var services by remember { mutableStateOf<List<ContainerServiceStatus>>(emptyList()) }
    var message by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("example.service") }
    var content by remember { mutableStateOf("[Unit]\nDescription=Example container service\nAfter=dbus.service\n\n[Service]\nType=simple\nExecStart=/bin/sh -c 'while true; do date; sleep 60; done'\nRestart=on-failure\nRestartSec=3\n\n[Install]\nWantedBy=multi-user.target\n") }
    val scope = rememberCoroutineScope()
    fun action(operation: String, unit: String? = null) {
        if (selected.isEmpty() || busy) return
        val container = selected
        busy = true
        scope.launch {
            try {
                val result = DaemonManager.containerServices(container).control(operation, listOfNotNull(unit), if (operation == "write-unit") content else "").getOrThrow()
                if (operation == "cat") { name = unit.orEmpty(); content = result.text; message = "" }
                else message = result.text
                services = DaemonManager.containerServices(container).control("list-units").getOrThrow().services
            } catch (error: Exception) { message = error.message.orEmpty() }
            finally { busy = false }
        }
    }
    LaunchedEffect(selected) {
        if (selected.isNotEmpty()) {
            busy = true
            val result = DaemonManager.containerServices(selected).control("list-units")
            result.onSuccess { services = it.services; message = "" }.onFailure { message = it.message.orEmpty() }
            busy = false
        }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.container_services_title)) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(stringResource(R.string.container_services_scope), style = MaterialTheme.typography.bodySmall) }
            item {
                if (containers.isEmpty()) Text(stringResource(R.string.container_services_empty))
                else Box {
                    OutlinedButton(onClick = { menu = true }, enabled = !busy) { Text(selected) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        containers.forEach { container -> DropdownMenuItem(text = { Text(container.name) }, onClick = { selected = container.name; menu = false; services = emptyList() }) }
                    }
                }
            }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { action("list-units") }, enabled = !busy) { Text(stringResource(R.string.container_services_refresh)) }
                    OutlinedButton(onClick = { action("daemon-reload") }, enabled = !busy) { Text(stringResource(R.string.container_services_reload)) }
                    OutlinedButton(onClick = {
                        busy = true
                        scope.launch {
                            try { ContainerServiceForegroundService.stopContainer(context, selected); services = emptyList(); message = "" }
                            catch (error: Exception) { message = error.message.orEmpty() }
                            finally { busy = false }
                        }
                    }, enabled = !busy && selected.isNotEmpty()) { Text(stringResource(R.string.container_services_stop_container)) }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (message.isNotEmpty()) item { SelectionContainer { Text(message, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) } }
            items(services, key = { it.name }) { service ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(service.name, style = MaterialTheme.typography.titleSmall)
                        Text("${service.state} · ${if (service.enabled) "enabled" else "disabled"} · PID ${service.pid}")
                        Text(service.description, style = MaterialTheme.typography.bodySmall)
                        if (service.detail.isNotEmpty()) Text(service.detail, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            val buttons = listOf("start" to R.string.container_services_start, "stop" to R.string.container_services_stop,
                                "restart" to R.string.container_services_restart, (if (service.enabled) "disable" else "enable") to (if (service.enabled) R.string.container_services_disable else R.string.container_services_enable),
                                "logs" to R.string.container_services_logs, "cat" to R.string.container_services_edit)
                            buttons.forEach { (command, label) -> TextButton(onClick = { action(command, service.name) }, enabled = !busy) { Text(stringResource(label)) } }
                        }
                    }
                }
            }
            item { OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.container_services_unit_name)) }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
            item { OutlinedTextField(content, { content = it }, label = { Text(stringResource(R.string.container_services_unit_content)) }, modifier = Modifier.fillMaxWidth(), minLines = 8, textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace)) }
            item { Button(onClick = { action("write-unit", name) }, enabled = !busy && selected.isNotEmpty()) { Text(stringResource(R.string.container_services_save)) } }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
