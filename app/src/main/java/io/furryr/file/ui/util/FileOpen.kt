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
import io.furryr.file.core.ContentInfo
import io.furryr.file.core.ContentTypes
import io.furryr.file.core.VFile
import io.furryr.file.core.model.FileEntry
import io.furryr.file.forward.ForwardFacts
import io.furryr.file.forward.ForwardFileProvider
import io.furryr.file.util.PrimaryStorage
import java.io.File

import android.widget.Toast
import java.util.ArrayList

/**
 * Hand [entry] to the platform viewer.
 *
 * When [file] is provided, the URI addresses the node's flat `ViewRef` instead
 * of the display path, so archive entries and container paths stay resolvable.
 */
fun openFile(
    context: Context,
    entry: FileEntry,
    file: VFile? = null,
    content: ContentInfo? = null,
) {
    val facts = ForwardFacts(
        name = content?.name ?: entry.name,
        size = content?.size ?: entry.size,
        mime = content?.mime,
    )
    val uri = if (file != null) {
        ForwardFileProvider.buildUri(file.ref(), facts)
    } else {
        shareableUri(context, entry.path, facts)
    }
    if (uri == null) {
        Toast.makeText(context, context.getString(R.string.toast_cannot_open, entry.name), Toast.LENGTH_SHORT).show()
        return
    }
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, content?.mime ?: ContentTypes.fromName(entry.name) ?: mimeType(File(entry.path)))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, entry.name)) }.onFailure {
        Toast.makeText(context, context.getString(R.string.toast_no_app, entry.name), Toast.LENGTH_SHORT).show()
    }
}

/**
 * Treat a remote resource like any other file: hand an "open with" chooser a
 * `content://` URI. [ForwardFileProvider] serves the URL through the same
 * [io.furryr.file.core.source.FileSource] path as a local file, using a proxy
 * descriptor when the server supports byte ranges and a pipe otherwise, so the
 * system never falls back to a browser for an http(s) URI.
 */
fun openRemoteFile(
    context: Context,
    url: String,
    content: ContentInfo?,
    name: String,
    referer: String?,
) {
    val display = content?.name?.takeIf { it.isNotBlank() } ?: name
    val mime = content?.mime ?: ContentTypes.fromName(display) ?: "*/*"
    val uri = ForwardFileProvider.buildUri(
        url,
        referer,
        ForwardFacts(name = display, size = content?.size ?: -1L, mime = mime),
    )
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, display)) }.onFailure {
        Toast.makeText(context, context.getString(R.string.toast_no_app, display), Toast.LENGTH_SHORT).show()
    }
}

fun shareEntries(context: Context, entries: List<FileEntry>) {
    val files = entries.filterNot { it.isDirectory }
    if (files.isEmpty()) return
    val uris = files.mapNotNull {
        shareableUri(context, it.path, ForwardFacts(name = it.name, size = it.size))
    }
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
private fun shareableUri(
    context: Context,
    path: String,
    facts: ForwardFacts? = null,
): Uri? {
    if (PrimaryStorage.contains(path)) {
        runCatching { FileProvider.getUriForFile(context, ProviderAuthority, File(path)) }
            .getOrNull()
            ?.let { return it }
    }
    return ForwardFileProvider.buildUri(path, facts = facts)
}
