package io.furryr.file.ui.util
import io.furryr.file.ui.theme.*
import io.furryr.file.ui.screens.*
import io.furryr.file.ui.components.*
import io.furryr.file.ui.util.*
import io.furryr.file.daemon.*
import io.furryr.file.model.*
import io.furryr.file.R

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import io.furryr.file.ProviderAuthority
import io.furryr.file.model.FileEntry
import java.io.File

import android.widget.Toast
import java.util.ArrayList

fun openFile(context: Context, entry: FileEntry) {
    val file = File(entry.path)
    val uri = runCatching { FileProvider.getUriForFile(context, ProviderAuthority, file) }.getOrNull()
    if (uri == null) {
        Toast.makeText(context, context.getString(R.string.toast_cannot_open, entry.name), Toast.LENGTH_SHORT).show()
        return
    }
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeType(file))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, entry.name)) }.onFailure {
        Toast.makeText(context, context.getString(R.string.toast_no_app, entry.name), Toast.LENGTH_SHORT).show()
    }
}

fun shareEntries(context: Context, entries: List<FileEntry>) {
    val files = entries.filterNot { it.isDirectory }.map { File(it.path) }
    if (files.isEmpty()) return
    val uris = files.mapNotNull { file ->
        runCatching { FileProvider.getUriForFile(context, ProviderAuthority, file) }.getOrNull()
    }
    if (uris.isEmpty()) {
        Toast.makeText(context, context.getString(R.string.toast_failed), Toast.LENGTH_SHORT).show()
        return
    }
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).apply {
            type = mimeType(files.first())
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
