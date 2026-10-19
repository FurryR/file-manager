package io.furryr.file.tools

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.furryr.file.AppLocale
import io.furryr.file.DefaultPath
import io.furryr.file.R
import io.furryr.file.ui.theme.FileManagerTheme
import java.net.NetworkInterface
import java.security.SecureRandom

class ToolboxActivity : ComponentActivity() {
    override fun attachBaseContext(base: Context) { super.attachBaseContext(AppLocale.wrap(base)) }
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dark = getSharedPreferences("settings", MODE_PRIVATE).getBoolean("dark_theme", true)
        setContent { FileManagerTheme(darkTheme = dark) {
            val state by LocalFtpService.state.collectAsState()
            var root by remember { mutableStateOf(intent.getStringExtra("path") ?: DefaultPath) }
            var port by remember { mutableStateOf("2121") }
            var user by remember { mutableStateOf("file") }
            var password by remember { mutableStateOf(android.util.Base64.encodeToString(ByteArray(12).also { SecureRandom().nextBytes(it) }, android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE)) }
            var loopback by remember { mutableStateOf(false) }
            var showPassword by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(state.running, state.root, state.port, state.username, state.loopback) {
                if (state.running) {
                    root = state.root; port = state.port.toString(); user = state.username; loopback = state.loopback
                    password = LocalFtpService.runningPassword().orEmpty()
                }
            }
            val addresses = remember(state.running) { runCatching { NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }.filter { !it.isLoopbackAddress && it is java.net.Inet4Address }.mapNotNull { it.hostAddress } }.getOrDefault(emptyList()) }
            fun open(activity: String) { startActivity(Intent().setClassName(packageName, "$packageName.$activity")) }
            Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.tools_title)) }, navigationIcon = {
                IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
            }) }) { padding -> Column(Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.tools_ftp), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.tools_ftp_description), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(root, { root = it }, label = { Text(stringResource(R.string.tools_shared_root)) }, enabled = !state.running, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(port, { port = it }, label = { Text(stringResource(R.string.tools_port)) }, enabled = !state.running,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                    OutlinedTextField(user, { user = it }, label = { Text(stringResource(R.string.tools_username)) }, enabled = !state.running, modifier = Modifier.weight(1f))
                }
                OutlinedTextField(password, { password = it }, label = { Text(stringResource(R.string.tools_password)) }, enabled = !state.running,
                    visualTransformation = if (showPassword) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Row { Checkbox(showPassword, { showPassword = it }); Text(stringResource(R.string.tools_show_password)) }
                Row { Checkbox(loopback, { loopback = it }, enabled = !state.running); Text(stringResource(R.string.tools_loopback)) }
                if (state.running) {
                    Text((if (state.loopback) listOf("127.0.0.1") else addresses).joinToString("\n") { "ftp://$it:${state.port}/" })
                    Text(state.root)
                    Button(onClick = { LocalFtpService.stop(this@ToolboxActivity) }) { Text(stringResource(R.string.tools_stop)) }
                } else Button(onClick = {
                    runCatching { LocalFtpService.start(this@ToolboxActivity, root, port.toInt(), user, password, loopback) }
                        .onFailure { error = it.message }
                }, enabled = root.isNotBlank() && user.isNotBlank() && password.isNotBlank() && port.toIntOrNull() in 1024..65535) { Text(stringResource(R.string.tools_start)) }
                (error ?: state.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                HorizontalDivider()
                Button(onClick = { open("agent.ContainerServicesActivity") }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.tools_services)) }
                Button(onClick = { open("desktop.DesktopActivity") }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.tools_desktop)) }
                Button(onClick = { open("graphics.GraphicsActivity") }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.tools_graphics)) }
            } }
        } }
    }
}
