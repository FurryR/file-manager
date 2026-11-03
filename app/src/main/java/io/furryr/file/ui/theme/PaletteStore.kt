package io.furryr.file.ui.theme

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

// Process-wide palette selection. The revision state makes every composition
// that calls current() recompose when Settings picks a new palette, while
// SharedPreferences keeps the choice across process restarts.
object PaletteStore {
    private const val PREFS = "settings"
    private const val KEY = "color_scheme"
    private var revision by mutableIntStateOf(0)

    fun current(context: Context): ColorPalette {
        // Reading the state registers the palette as a composition dependency.
        val version = revision
        return ColorPalette.fromId(prefs(context).getString(KEY, null))
    }

    fun select(context: Context, palette: ColorPalette) {
        prefs(context).edit().putString(KEY, palette.id).apply()
        revision++
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
