package io.furryr.file.archive

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.furryr.file.AppLocale
import io.furryr.file.FileRepository
import io.furryr.file.R
import io.furryr.file.provider.installFileProviders
import io.furryr.file.ui.theme.FileManagerTheme
import io.furryr.file.util.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ArchiveToolsActivity : ComponentActivity() {
    companion object {
        const val EXTRA_PATHS = "archive_paths"
        const val EXTRA_MODE = "archive_mode"
        const val EXTRA_DESTINATION = "archive_destination"
        const val EXTRA_URI = "archive_uri"
    }
    override fun attachBaseContext(base: Context) { super.attachBaseContext(AppLocale.wrap(base)) }
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); installFileProviders(this)
        val paths = intent.getStringArrayListExtra(EXTRA_PATHS)?.toList().orEmpty()
        if (paths.isEmpty()) { finish(); return }
        val mode = intent.getStringExtra(EXTRA_MODE) ?: "open"
        val initialDestination = intent.getStringExtra(EXTRA_DESTINATION) ?: Path.parse(paths.first()).parent()?.toString().orEmpty()
        val dark = getSharedPreferences("settings", MODE_PRIVATE).getBoolean("dark_theme", true)
        setContent { FileManagerTheme(darkTheme = dark) {
            var password by remember { mutableStateOf("") }
            var destination by remember { mutableStateOf(if (mode == "create") Path.parse(initialDestination).child("archive.zip").toString() else initialDestination) }
            var busy by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf<String?>(null) }
            var progress by remember { mutableStateOf("") }
            var overwrite by remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()
            fun execute() {
                busy = true; error = null
                scope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching {
                        val key = password.takeIf { it.isNotEmpty() }
                        when (mode) {
                            "create" -> { ArchiveProvider.createZip(paths, destination, key); Path.parse(destination).parent()!!.toString() }
                            "extract" -> {
                                val root = ArchiveProvider.mount(paths.first(), key)
                                require(FileRepository.stat(destination).getOrThrow().isDirectory) { "Choose an existing destination directory" }
                                fun rejectDestinationLink(target: String) {
                                    val parsed = Path.parse(target)
                                    val parent = parsed.parent() ?: return
                                    if (FileRepository.stat(parent.toString()).getOrThrow().exists) {
                                        val entry = FileRepository.list(parent.toString()).getOrThrow().firstOrNull { it.name == parsed.name }
                                        require(entry?.isSymlink != true) { "Refusing to extract through a destination symbolic link: $target" }
                                    }
                                }
                                suspend fun check(source: String, target: String) {
                                    rejectDestinationLink(target)
                                    val info = FileRepository.stat(source).getOrThrow()
                                    val current = FileRepository.stat(target).getOrThrow()
                                    if (current.exists) require(info.isDirectory && current.isDirectory || overwrite && !info.isDirectory && !current.isDirectory) { "Destination exists: $target" }
                                    if (info.isDirectory) FileRepository.list(source).getOrThrow().forEach {
                                        check(it.path, Path.parse(target).child(it.name).toString())
                                    }
                                }
                                suspend fun extract(source: String, target: String) {
                                    rejectDestinationLink(target)
                                    val info = FileRepository.stat(source).getOrThrow()
                                    if (info.isDirectory) {
                                        if (!FileRepository.stat(target).getOrThrow().exists) FileRepository.createDirectory(Path.parse(target).parent()!!.toString(), Path.parse(target).name!!).getOrThrow()
                                        FileRepository.list(source).getOrThrow().forEach { extract(it.path, Path.parse(target).child(it.name).toString()) }
                                    } else FileRepository.copy(source, Path.parse(target).parent()!!.toString()).collect { update ->
                                        withContext(Dispatchers.Main) { progress = update.currentName }
                                    }
                                }
                                val top = FileRepository.list(root).getOrThrow()
                                top.forEach { check(it.path, Path.parse(destination).child(it.name).toString()) }
                                top.forEach { extract(it.path, Path.parse(destination).child(it.name).toString()) }
                                destination
                            }
                            else -> ArchiveProvider.mount(paths.first(), key)
                        }
                    } }
                    result.onSuccess { uri -> setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_URI, uri)); finish() }
                        .onFailure { error = if (it is ArchivePasswordRequired) getString(R.string.archive_password_needed) else it.message }
                    busy = false
                }
            }
            LaunchedEffect(Unit) { if (mode == "open") execute() }
            Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.archive_tools)) },
                navigationIcon = { IconButton(onClick = { finish() }, enabled = !busy) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } }) }) { padding ->
                Column(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(paths.joinToString("\n"))
                    if (mode != "open") OutlinedTextField(destination, { destination = it }, label = { Text(stringResource(R.string.archive_destination)) }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                    OutlinedTextField(password, { password = it }, label = { Text(stringResource(R.string.archive_password)) },
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), enabled = !busy, singleLine = true)
                    if (mode == "extract") Row { Checkbox(overwrite, { overwrite = it }, enabled = !busy); Text(stringResource(R.string.archive_overwrite)) }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(progress) }
                    Button(onClick = ::execute, enabled = !busy && (mode == "open" || destination.isNotBlank())) {
                        Text(stringResource(if (mode == "create") R.string.archive_create else if (mode == "extract") R.string.archive_extract else R.string.archive_browse))
                    }
                }
            }
        } }
    }
}
