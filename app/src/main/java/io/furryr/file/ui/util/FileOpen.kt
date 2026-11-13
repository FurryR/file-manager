package io.furryr.file.ui.util
import io.furryr.file.ui.theme.*
import io.furryr.file.ui.screens.*
import io.furryr.file.ui.components.*
import io.furryr.file.ui.util.*
import io.furryr.file.model.*
import io.furryr.file.R

import android.content.ClipData
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
 * When [file] is provided, the URI addresses the node's own `Path` (view levels included) instead
 * of the display path, so archive entries and container paths stay resolvable. A live remote file
 * is a node like any other: the URI streams the same bytes on demand, carrying the page it came
 * from, so the chooser never receives an `http(s)://` address to hand to a browser.
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
        ForwardFileProvider.buildUri(file, facts)
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
 * Share the file itself — a stream the receiver reads on demand — not its
 * address. [file] may be a page link or any node; the forward provider serves
 * both, keeping the page as referer so hotlink-protected hosts still answer the
 * range requests.
 */
fun shareFile(context: Context, file: VFile, content: ContentInfo? = null) {
    val display = content?.name?.takeIf { it.isNotBlank() } ?: file.name
    val mime = content?.mime ?: ContentTypes.fromName(display) ?: "*/*"
    val uri = ForwardFileProvider.buildUri(
        file,
        ForwardFacts(name = display, size = content?.size ?: -1L, mime = mime),
    )
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // The clip label is the second place a ROM looks for the file name;
        // without it some share sheets fall back to the URI's last segment.
        runCatching { clipData = ClipData.newUri(context.contentResolver, display, uri) }
    }
    runCatching { context.startActivity(Intent.createChooser(intent, display)) }.onFailure {
        Toast.makeText(context, context.getString(R.string.toast_failed), Toast.LENGTH_SHORT).show()
    }
}

suspend fun shareEntries(context: Context, entries: List<FileEntry>) {
    val files = entries.filterNot { it.isDirectory }
    if (files.isEmpty()) return
    val resolved = files.mapNotNull { entry -> io.furryr.file.core.Vfs.file(entry.path).getOrNull() }
    if (resolved.isEmpty()) {
        Toast.makeText(context, context.getString(R.string.toast_failed), Toast.LENGTH_SHORT).show()
        return
    }
    val uris = resolved.map { file ->
        ForwardFileProvider.buildUri(
            file,
            ForwardFacts(name = file.name, size = file.stat().getOrNull()?.size ?: -1L),
        )
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
            runCatching {
                clipData = ClipData.newUri(
                    context.contentResolver,
                    files.first().name,
                    uris.first(),
                )
            }
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
