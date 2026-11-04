package io.furryr.file.ui.theme

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

// Process-wide terminal colour scheme. Independent of PaletteStore: the app
// theme no longer recolours the terminal. The revision state makes every
// composition that calls current() recompose when Settings picks a scheme,
// while SharedPreferences keeps the choice across process restarts.
object TerminalSchemeStore {
    private const val PREFS = "settings"
    private const val KEY = "terminal_color_scheme"
    private var revision by mutableIntStateOf(0)

    fun current(context: Context): TerminalScheme {
        // Reading the state registers the scheme as a composition dependency.
        val version = revision
        return TerminalScheme.fromId(prefs(context).getString(KEY, null))
    }

    fun select(context: Context, scheme: TerminalScheme) {
        prefs(context).edit().putString(KEY, scheme.id).apply()
        revision++
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
