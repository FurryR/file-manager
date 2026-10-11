package io.furryr.file.util

import android.os.Environment
import java.io.File

/**
 * Locates the primary shared ("external") storage without hardcoding an
 * Android user id or the `/sdcard` alias.
 *
 * [Environment.getExternalStorageDirectory] already returns the current
 * user's primary volume (typically `/storage/emulated/0`), and `/sdcard` is
 * merely a symlink to it. Comparing canonical paths therefore covers both
 * spellings — and every other alias — with a single source of truth.
 */
object PrimaryStorage {

    /** Canonical path of the primary shared storage volume. */
    private val root: String by lazy {
        canonical(Environment.getExternalStorageDirectory().absolutePath)
    }

    /** True when [path] is the primary shared storage or lives under it. */
    fun contains(path: String): Boolean {
        if (path.isBlank()) return false
        val resolved = canonical(path)
        return resolved == root || resolved.startsWith("$root/")
    }

    private fun canonical(path: String): String =
        runCatching { File(path).canonicalPath }.getOrDefault(path)
}
