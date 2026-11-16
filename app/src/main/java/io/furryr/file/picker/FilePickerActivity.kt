package io.furryr.file.picker

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.furryr.file.AppLocale
import io.furryr.file.AppRuntime
import io.furryr.file.core.ContentTypes
import io.furryr.file.core.VFile
import io.furryr.file.core.VFolder
import io.furryr.file.daemon.DaemonManager
import io.furryr.file.ui.theme.FileManagerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * An exported picker that returns persistent document/tree URIs.
 *
 * It is a separate shell, not [io.furryr.file.FileManagerApp]: no tabs, no
 * terminal, no clipboard. It resolves through its own [AppRuntime.pickerWorld],
 * so a picker registration never changes the file manager's tables.
 */
class FilePickerActivity : ComponentActivity() {
    private var request: PickerRequest? = null
    private var pickerWorldId: String? = null
    private var appliedLanguage = AppLocale.SYSTEM

    override fun attachBaseContext(newBase: Context) {
        appliedLanguage = AppLocale.current(newBase)
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onResume() {
        super.onResume()
        if (AppLocale.current(this) != appliedLanguage) recreate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val parsed = request ?: PickerRequest.from(intent)
        if (parsed == null) {
            finishPicker(null)
            return
        }
        request = parsed
        val world = AppRuntime.pickerWorld(this)
        pickerWorldId = world.id
        runCatching { PickerTokens.install(File(filesDir, "picker-token.bin")) }
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { DaemonManager.warmUp() }
        }

        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        setContent {
            var darkTheme by remember { mutableStateOf(prefs.getBoolean("dark_theme", true)) }
            FileManagerTheme(darkTheme = darkTheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    FilePickerApp(
                        world = world,
                        request = parsed,
                        darkTheme = darkTheme,
                        onToggleTheme = {
                            darkTheme = !darkTheme
                            prefs.edit().putBoolean("dark_theme", darkTheme).apply()
                        },
                        onCancel = { finishPicker(null) },
                        onPickFiles = ::completeFiles,
                        onPickFolder = ::completeFolder,
                        onCreated = ::completeCreate,
                    )
                }
            }
        }
    }

    private fun completeFiles(files: List<VFile>) {
        if (files.isEmpty()) return finishPicker(null)
        val worldId = pickerWorldId ?: return finishPicker(null)
        val persistable = request?.persistable == true
        val accesses = files.map { if (persistable && it.writable) "rw" else "r" }
        val uris = files.mapIndexed { index, file ->
            PickerDocuments.documentUri(worldId, accesses[index], file)
        }
        val type = resultType(files)
        val result = Intent().setDataAndType(uris.first(), type)
        result.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (accesses.any { it == "rw" }) {
            result.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        if (persistable) {
            result.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        if (uris.size > 1) {
            val clip = ClipData.newUri(contentResolver, files.first().name, uris.first())
            for (uri in uris.drop(1)) clip.addItem(ClipData.Item(uri))
            result.clipData = clip
        }
        finishPicker(result)
    }

    private fun completeCreate(file: VFile) {
        val worldId = pickerWorldId ?: return finishPicker(null)
        val uri = PickerDocuments.documentUri(worldId, "rw", file)
        val type = intent?.type?.takeIf { it.isNotBlank() && it != "*/*" }
            ?: ContentTypes.fromName(file.name)
            ?: "*/*"
        val result = Intent().setDataAndType(uri, type)
        result.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        result.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        result.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        finishPicker(result)
    }

    private fun completeFolder(folder: VFolder) {
        val worldId = pickerWorldId ?: return finishPicker(null)
        lifecycleScope.launch {
            val writable = withContext(Dispatchers.IO) {
                val caps = runCatching { folder.capabilities() }.getOrNull()
                caps != null && (
                    caps.createFile != null || caps.createDirectory != null ||
                        caps.delete != null || caps.rename != null
                    )
            }
            val uri = PickerDocuments.treeUri(
                worldId,
                folder.path.toString(),
                access = if (writable) "rw" else "r",
            )
            val result = Intent().setData(uri)
            result.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (writable) {
                result.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            result.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            result.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            finishPicker(result)
        }
    }

    private fun resultType(files: List<VFile>): String {
        val declared = request?.mimeTypes?.singleOrNull()?.takeIf { it != "*/*" }
        if (declared != null) return declared
        if (files.size != 1) return "*/*"
        return ContentTypes.fromName(files.first().name) ?: "*/*"
    }

    private fun finishPicker(result: Intent?) {
        if (result == null) {
            setResult(Activity.RESULT_CANCELED)
        } else {
            setResult(Activity.RESULT_OK, result)
        }
        finish()
    }
}
