package io.furryr.file

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

object AppLocale {
    const val SYSTEM = "system"
    const val ENGLISH = "en"
    const val CHINESE = "zh"
    private const val PREFS = "settings"
    private const val KEY = "language"

    fun current(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, SYSTEM) ?: SYSTEM

    fun set(context: Context, language: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, language).apply()
    }

    fun wrap(context: Context): Context {
        val language = current(context)
        if (language == SYSTEM) return context
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(language))
        return context.createConfigurationContext(configuration)
    }
}
