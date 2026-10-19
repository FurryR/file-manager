package io.furryr.file.ui.util
import io.furryr.file.ui.theme.*
import io.furryr.file.ui.screens.*
import io.furryr.file.ui.components.*
import io.furryr.file.ui.util.*
import io.furryr.file.model.*
import io.furryr.file.R

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import io.furryr.file.ProviderAuthority
import io.furryr.file.core.model.FileEntry
import io.furryr.file.provider.ForwardFileProvider
import io.furryr.file.util.PrimaryStorage
import java.io.File

import android.widget.Toast
import java.util.ArrayList
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import io.furryr.file.FileRepository
import io.furryr.file.core.source.OpenMode
import io.furryr.file.editor.EditorActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale

/** The archive browser can claim supported files before ordinary text/system routing. */
object FileOpenHooks {
    @Volatile var archiveOpener: ((Context, FileEntry) -> Boolean)? = null
}

private val fileOpenScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

fun openFile(context: Context, entry: FileEntry) {
    if (FileOpenHooks.archiveOpener?.invoke(context, entry) == true) return
    val type = mimeType(File(entry.name))
    if (isTextDocument(entry.name, type)) {
        EditorActivity.open(context, entry.path)
        return
    }
    if (type == "*/*" || type == "application/octet-stream") {
        val scope = (context as? LifecycleOwner)?.lifecycleScope ?: fileOpenScope
        scope.launch {
            val text = try {
                withContext(Dispatchers.IO) {
                    val resolved = FileRepository.resolve(entry.path).getOrThrow()
                    resolved.provider.open(resolved.innerPath, OpenMode.READ).getOrThrow().use { source ->
                        val bytes = source.readAt(0, 8192)
                        if (bytes.isEmpty()) true
                        else if (bytes.take(2) in listOf(listOf(0xFF.toByte(), 0xFE.toByte()), listOf(0xFE.toByte(), 0xFF.toByte()))) true
                        else if (bytes.any { it == 0.toByte() || (it.toInt() and 255) in 1..8 || (it.toInt() and 255) in 14..31 }) false
                        else {
                            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                            // The sample may end in the middle of a UTF-8 code point.
                            !decoder.decode(ByteBuffer.wrap(bytes), CharBuffer.allocate(bytes.size), false).isError
                        }
                    }
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                false
            }
            if (text) EditorActivity.open(context, entry.path) else openFileWithSystem(context, entry)
        }
    } else openFileWithSystem(context, entry)
}

fun isTextDocument(name: String, type: String = mimeType(File(name))): Boolean {
    val file = name.lowercase(Locale.ROOT)
    return type.startsWith("text/") || type in setOf("application/json", "application/xml", "application/javascript", "application/x-sh", "application/x-yaml") ||
        file in setOf("dockerfile", "makefile", "cmakelists.txt", "license", "readme", ".gitignore", ".gitattributes", ".editorconfig", ".env", ".bashrc", ".zshrc") ||
        file.substringAfterLast('.', "") in setOf("txt", "md", "markdown", "log", "json", "jsonc", "yaml", "yml", "toml", "ini", "cfg", "conf", "properties", "xml", "svg", "html", "htm", "css", "scss", "sass", "less", "js", "jsx", "mjs", "cjs", "ts", "tsx", "vue", "svelte", "py", "pyw", "java", "kt", "kts", "c", "h", "cpp", "cc", "hpp", "cxx", "rs", "go", "rb", "php", "sh", "bash", "zsh", "fish", "sql", "lua", "dart", "swift", "cs", "gradle", "cmake", "bat", "cmd", "ps1", "patch", "diff")
}

/** Explicit system "Open with" action used by the file's long-press menu. */
fun openFileWithSystem(context: Context, entry: FileEntry) {
    val uri = shareableUri(context, entry.path)
    if (uri == null) {
        Toast.makeText(context, context.getString(R.string.toast_cannot_open, entry.name), Toast.LENGTH_SHORT).show()
        return
    }
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeType(File(entry.path)))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, entry.name)) }.onFailure {
        Toast.makeText(context, context.getString(R.string.toast_no_app, entry.name), Toast.LENGTH_SHORT).show()
    }
}

fun shareEntries(context: Context, entries: List<FileEntry>) {
    val files = entries.filterNot { it.isDirectory }
    if (files.isEmpty()) return
    val uris = files.mapNotNull { shareableUri(context, it.path) }
    if (uris.isEmpty()) {
        Toast.makeText(context, context.getString(R.string.toast_failed), Toast.LENGTH_SHORT).show()
        return
    }
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).apply {
            type = mimeType(File(files.first().path))
            putExtra(Intent.EXTRA_STREAM, uris.first())
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    runCatching { context.startActivity(Intent.createChooser(intent, context.getString(R.string.share))) }
        .onFailure { Toast.makeText(context, context.getString(R.string.toast_failed), Toast.LENGTH_SHORT).show() }
}

/**
 * Resolve a shareable `content://` URI for [path].
 *
 * Paths under the primary shared storage are handed to the platform
 * FileProvider; anything else (root-only locations, other volumes, …) is
 * forwarded through [ForwardFileProvider], which streams the bytes via the
 * privileged daemon so the receiving app can read them.
 */
private fun shareableUri(context: Context, path: String): Uri? {
    if (PrimaryStorage.contains(path)) {
        runCatching { FileProvider.getUriForFile(context, ProviderAuthority, File(path)) }
            .getOrNull()
            ?.let { return it }
    }
    return ForwardFileProvider.buildUri(path)
}
