package io.furryr.file.editor

import android.content.Context
import android.graphics.Typeface
import java.io.File

/**
 * App-owned editor display preferences. The settings screen writes these keys;
 * every open editor re-reads them so a change applies without closing tabs.
 */
object EditorPreferences {
    const val FONT_SIZE_KEY = "editor_font_size"
    const val FONT_KEY = "editor_font"
    const val DEFAULT_FONT_SIZE = 14
    const val MIN_FONT_SIZE = 8
    const val MAX_FONT_SIZE = 48

    fun fontSize(context: Context): Int =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getInt(FONT_SIZE_KEY, DEFAULT_FONT_SIZE)
            .coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)

    fun fontPath(context: Context): String =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getString(FONT_KEY, "") ?: ""

    /** Monospace unless the user picked a readable font file. */
    fun typeface(context: Context): Typeface {
        val path = fontPath(context)
        if (path.isBlank()) return Typeface.MONOSPACE
        return runCatching {
            if (File(path).isFile) Typeface.createFromFile(path) else Typeface.MONOSPACE
        }.getOrDefault(Typeface.MONOSPACE)
    }
}
