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
import io.furryr.file.forward.ForwardFileProvider
import io.furryr.file.util.PrimaryStorage
import java.io.File

import android.widget.Toast
import java.util.ArrayList

fun openFile(context: Context, entry: FileEntry) {
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
