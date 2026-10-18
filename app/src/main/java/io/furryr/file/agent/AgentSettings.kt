package io.furryr.file.agent

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.furryr.file.R
import io.furryr.file.core.HubActions
import io.furryr.file.core.HubAiConfig
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** User configuration is read on each hub request; credentials never enter shell environments. */
object AgentSettings {
    private const val PREFS = "agent_settings"
    private const val KEY_ALIAS = "io.furryr.file.agent.api_key"

    fun install(context: Context) {
        val appContext = context.applicationContext
        HubActions.aiConfigProvider = {
            val config = load(appContext)
            check(validEndpoint(config.baseUrl) && config.model.isNotBlank()) {
                appContext.getString(R.string.agent_config_required)
            }
            config
        }
    }

    fun load(context: Context): HubAiConfig {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return HubAiConfig(
            baseUrl = prefs.getString("base_url", "").orEmpty(),
            apiKey = prefs.getString("api_key_encrypted", null)?.let(::decrypt).orEmpty(),
            model = prefs.getString("model", "").orEmpty(),
        )
    }

    fun save(context: Context, baseUrl: String, apiKey: String, model: String) {
        require(validEndpoint(baseUrl)) { context.getString(R.string.agent_invalid_endpoint) }
        require(model.isNotBlank()) { context.getString(R.string.agent_model) }
        val encryptedKey = apiKey.trim().takeIf { it.isNotEmpty() }?.let(::encrypt)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("base_url", baseUrl.trim().trimEnd('/'))
            .putString("api_key_encrypted", encryptedKey)
            .putString("model", model.trim())
            .apply()
    }

    fun validEndpoint(value: String): Boolean = runCatching {
        val uri = URI(value.trim())
        uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null
    }.getOrDefault(false)

    @Synchronized
    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val encrypted = Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        return "$iv:$encrypted"
    }

    private fun decrypt(value: String): String {
        val (iv, encrypted) = value.split(':', limit = 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        return String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8)
    }
}
