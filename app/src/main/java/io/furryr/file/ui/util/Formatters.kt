package io.furryr.file.ui.util
import io.furryr.file.ui.theme.*
import io.furryr.file.ui.screens.*
import io.furryr.file.ui.components.*
import io.furryr.file.ui.util.*
import io.furryr.file.daemon.*
import io.furryr.file.model.*
import io.furryr.file.ui.util.formatSize

import android.webkit.MimeTypeMap
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

fun formatModifiedTime(timeMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timeMillis.coerceAtLeast(0L)))

fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "${bytes}B"
    val units = listOf("KiB", "MiB", "GiB", "TiB")
    var value = bytes.toDouble()
    var unit = "B"
    for (u in units) {
        value /= 1024.0
        if (value < 1024.0 || u == units.last()) {
            unit = u
            break
        }
    }
    val stripped = "%.2f".format(Locale.ROOT, value).replace(Regex("\\.?0+$"), "")
    return "$stripped$unit"
}

fun abbreviateStart(path: String): String {
    if (path.length <= 42) return path
    return "..." + path.takeLast(39)
}

fun mimeType(file: File): String {
    val extension = file.extension.lowercase(Locale.ROOT)
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"
}

fun formatPermissions(mode: Int, isDirectory: Boolean): String {
    val typeChar = if (isDirectory) 'd' else '-'
    val owner = triplet(mode ushr 6)
    val group = triplet(mode ushr 3)
    val other = triplet(mode)
    return "$typeChar$owner$group$other"
}

private fun triplet(bits: Int): String {
    val r = if (bits and 4 != 0) 'r' else '-'
    val w = if (bits and 2 != 0) 'w' else '-'
    val x = if (bits and 1 != 0) 'x' else '-'
    return "$r$w$x"
}

fun isInternalStoragePath(realPath: String): Boolean {
    return realPath.startsWith("/storage/emulated/") || realPath.startsWith("/sdcard/")
}
