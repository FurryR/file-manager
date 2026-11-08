package io.furryr.file.settings

import android.content.Context
import android.content.SharedPreferences
import io.furryr.file.pulse.PulseManager

/**
 * App-owned PulseAudio settings.
 *
 * The `:pulse` module never reads preferences: this store persists the values
 * and hands the current configuration to [PulseManager].
 */
object PulseSettings {
    private const val PREFS_NAME = "settings"
    private const val PREF_ENABLED = "pulse_enabled"
    private const val PREF_MIC = "pulse_mic_enabled"
    private const val PREF_BUFFER = "pulse_buffer_ms"

    private fun preferences(context: Context): SharedPreferences {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // Keep existing choices when upgrading from the replaced backend.
        val editor = prefs.edit()
        for (key in listOf(PREF_ENABLED, PREF_MIC, PREF_BUFFER)) {
            val legacy = key.replaceFirst("pulse_", "pipewire_")
            if (!prefs.contains(key) && prefs.contains(legacy)) {
                if (key == PREF_BUFFER) editor.putInt(key, prefs.getInt(legacy, PulseManager.DEFAULT_BUFFER_MS))
                else editor.putBoolean(key, prefs.getBoolean(legacy, key == PREF_ENABLED))
            }
        }
        editor.apply()
        return prefs
    }

    fun isEnabled(context: Context): Boolean =
        preferences(context).getBoolean(PREF_ENABLED, true)

    fun isMicEnabled(context: Context): Boolean =
        preferences(context).getBoolean(PREF_MIC, false)

    /** Sink latency in milliseconds: lower is faster, too low underruns. */
    fun bufferMs(context: Context): Int =
        preferences(context)
            .getInt(PREF_BUFFER, PulseManager.DEFAULT_BUFFER_MS)
            .coerceIn(PulseManager.MIN_BUFFER_MS, PulseManager.MAX_BUFFER_MS)

    /** Persist the preference and apply it: bind the app listener, or stop audio. */
    fun setEnabled(context: Context, value: Boolean) {
        preferences(context).edit().putBoolean(PREF_ENABLED, value).apply()
        PulseManager.setEnabled(context, value)
    }

    /**
     * Persist the microphone preference. The core decides at creation time
     * whether to expose a source, so a running core is restarted to apply it —
     * in place, without touching the listener or the socket.
     */
    fun setMicEnabled(context: Context, value: Boolean) {
        preferences(context).edit().putBoolean(PREF_MIC, value).apply()
        PulseManager.setMicEnabled(value)
    }

    /**
     * Persist the sink latency. The core reads it when the sink is created,
     * so a running core is restarted to apply it — in place, without touching
     * the listener or the socket.
     */
    fun setBufferMs(context: Context, value: Int) {
        val clamped = value.coerceIn(PulseManager.MIN_BUFFER_MS, PulseManager.MAX_BUFFER_MS)
        preferences(context).edit().putInt(PREF_BUFFER, clamped).apply()
        PulseManager.setBufferMs(clamped)
    }

    /** Load the persisted values and hand them to the module. */
    fun install(context: Context) {
        PulseManager.configure(isEnabled(context), isMicEnabled(context), bufferMs(context))
    }
}
