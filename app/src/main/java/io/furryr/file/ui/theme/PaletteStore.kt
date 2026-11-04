package io.furryr.file.ui.theme

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

// Process-wide theme selection. The revision state makes every composition
// that calls current()/amoled() recompose when Settings changes a value, while
// SharedPreferences keeps the choice across process restarts.
object PaletteStore {
    private const val PREFS = "settings"
    private const val KEY = "color_scheme"
    private const val AMOLED_KEY = "amoled_black"
    private var revision by mutableIntStateOf(0)

    fun current(context: Context): ColorPalette {
        // Reading the state registers the palette as a composition dependency.
        val version = revision
        return ColorPalette.fromId(prefs(context).getString(KEY, null))
    }

    // Pure black is the default canvas, matching the AMOLED-first palettes.
    fun amoled(context: Context): Boolean {
        val version = revision
        return prefs(context).getBoolean(AMOLED_KEY, true)
    }

    fun select(context: Context, palette: ColorPalette) {
        prefs(context).edit().putString(KEY, palette.id).apply()
        revision++
    }

    fun setAmoled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(AMOLED_KEY, enabled).apply()
        revision++
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
