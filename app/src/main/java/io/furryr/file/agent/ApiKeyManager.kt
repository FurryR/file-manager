@file:Suppress("DEPRECATION")
package io.furryr.file.agent

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * Singleton manager for API keys backed by [EncryptedSharedPreferences].
 *
 * Keys are stored encrypted at rest using AES-256 GCM for values and
 * AES-256 SIV for keys. Provider names used here: "openai", "anthropic", "gemini".
 *
 * Must call [init] before any other method (typically from Application.onCreate
 * or the first Activity).
 */
object ApiKeyManager {

    private const val PREFS_NAME = "api_key_prefs"

    @Volatile
    private lateinit var encryptedPrefs: SharedPreferences

    /**
     * Initialise the encrypted prefs backing store. Safe to call multiple times —
     * subsequent calls are no-ops.
     */
    fun init(context: Context) {
        if (::encryptedPrefs.isInitialized) return
        encryptedPrefs = EncryptedSharedPreferences.create(
            PREFS_NAME,
            MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /**
     * Store an API key for [provider]. Previous value is overwritten.
     */
    fun saveKey(provider: String, key: String) {
        encryptedPrefs.edit().putString(provider, key).apply()
    }

    /**
     * Retrieve a stored API key for [provider], or `null` if not set.
     */
    fun getKey(provider: String): String? {
        return encryptedPrefs.getString(provider, null)
    }

    /**
     * Returns `true` if a key exists for [provider].
     */
    fun hasKey(provider: String): Boolean {
        return encryptedPrefs.contains(provider)
    }

    /**
     * Delete the stored key for [provider]. Safe to call when no key exists.
     */
    fun deleteKey(provider: String) {
        encryptedPrefs.edit().remove(provider).apply()
    }

    /**
     * Return all provider names that currently have a stored key.
     */
    fun listProviders(): List<String> {
        return encryptedPrefs.all.keys.toList()
    }
}
