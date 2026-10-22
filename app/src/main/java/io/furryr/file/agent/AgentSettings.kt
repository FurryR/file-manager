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
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

/** User configuration is read on each hub request; credentials never enter shell environments. */
object AgentSettings {
    private const val PREFS = "agent_settings"
    private const val KEY_ALIAS = "io.furryr.file.agent.api_key"
    private const val PROVIDERS = "providers"
    private const val ACTIVE_PROVIDER = "active_provider"
    private const val LAST_MODEL = "last_model"
    private const val LAST_EFFORT = "last_effort"
    private const val DEFAULT_EFFORT = "high"

    data class Provider(
        val id: String,
        val name: String,
        val baseUrl: String,
        val apiKey: String,
    )

    fun install(context: Context) {
        val appContext = context.applicationContext
        HubActions.aiConfigProvider = {
            val provider = activeProvider(appContext)
            check(provider != null && validEndpoint(provider.baseUrl)) {
                appContext.getString(R.string.agent_config_required)
            }
            HubAiConfig(
                baseUrl = provider.baseUrl,
                apiKey = provider.apiKey,
                model = lastModel(appContext),
                effort = lastEffort(appContext),
                provider = provider.name,
            )
        }
        HubActions.aiSelectionSaver = { model, effort ->
            saveSelection(appContext, model, effort)
        }
    }

    fun providers(context: Context): List<Provider> {
        migrate(context)
        val prefs = prefs(context)
        val raw = prefs.getString(PROVIDERS, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id").ifBlank { UUID.randomUUID().toString() }
            Provider(
                id = id,
                name = item.optString("name"),
                baseUrl = item.optString("base_url"),
                apiKey = item.optString("api_key").takeIf { it.isNotEmpty() }
                    ?.let { runCatching { decrypt(it) }.getOrDefault("") }.orEmpty(),
            )
        }
    }

    fun activeProvider(context: Context): Provider? {
        val providers = providers(context)
        if (providers.isEmpty()) return null
        val active = prefs(context).getString(ACTIVE_PROVIDER, null)
        return providers.firstOrNull { it.id == active } ?: providers.first()
    }

    fun saveProvider(context: Context, provider: Provider) {
        require(provider.name.isNotBlank()) { "provider name is required" }
        require(validEndpoint(provider.baseUrl)) { "invalid provider endpoint" }
        val id = provider.id.ifBlank { UUID.randomUUID().toString() }
        val providers = providers(context).toMutableList()
        val index = providers.indexOfFirst { it.id == id }
        val saved = provider.copy(
            id = id,
            name = provider.name.trim(),
            baseUrl = provider.baseUrl.trim().trimEnd('/'),
        )
        if (index >= 0) providers[index] = saved else providers.add(saved)
        writeProviders(context, providers)
        if (prefs(context).getString(ACTIVE_PROVIDER, null).isNullOrBlank()) {
            setActiveProvider(context, id)
        }
    }

    fun deleteProvider(context: Context, id: String) {
        val providers = providers(context).filterNot { it.id == id }
        writeProviders(context, providers)
        if (prefs(context).getString(ACTIVE_PROVIDER, null) == id) {
            val next = providers.firstOrNull()?.id
            prefs(context).edit().apply {
                if (next == null) remove(ACTIVE_PROVIDER) else putString(ACTIVE_PROVIDER, next)
            }.apply()
        }
    }

    fun setActiveProvider(context: Context, id: String) {
        if (providers(context).none { it.id == id }) return
        prefs(context).edit().putString(ACTIVE_PROVIDER, id).apply()
    }

    fun saveSelection(context: Context, model: String, effort: String) {
        prefs(context).edit().apply {
            if (model.isNotBlank()) putString(LAST_MODEL, model.trim())
            if (effort.isNotBlank()) putString(LAST_EFFORT, effort.trim())
        }.apply()
    }

    fun lastModel(context: Context): String =
        prefs(context).getString(LAST_MODEL, "").orEmpty()

    fun lastEffort(context: Context): String =
        prefs(context).getString(LAST_EFFORT, "").orEmpty().ifBlank { DEFAULT_EFFORT }

    fun validEndpoint(value: String): Boolean = runCatching {
        val uri = URI(value.trim())
        uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null
    }.getOrDefault(false)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun writeProviders(context: Context, providers: List<Provider>) {
        val array = JSONArray()
        for (provider in providers) {
            array.put(
                JSONObject()
                    .put("id", provider.id)
                    .put("name", provider.name)
                    .put("base_url", provider.baseUrl)
                    .put(
                        "api_key",
                        provider.apiKey.takeIf { it.isNotEmpty() }?.let(::encrypt).orEmpty(),
                    ),
            )
        }
        prefs(context).edit().putString(PROVIDERS, array.toString()).apply()
    }

    private fun migrate(context: Context) {
        val prefs = prefs(context)
        if (prefs.contains(PROVIDERS)) return
        val baseUrl = prefs.getString("base_url", "").orEmpty().trim()
        val model = prefs.getString("model", "").orEmpty().trim()
        if (baseUrl.isNotEmpty()) {
            val provider = Provider(
                id = UUID.randomUUID().toString(),
                name = "Default",
                baseUrl = baseUrl,
                apiKey = prefs.getString("api_key_encrypted", null)?.let {
                    runCatching { decrypt(it) }.getOrDefault("")
                }.orEmpty(),
            )
            writeProviders(context, listOf(provider))
            prefs.edit().putString(ACTIVE_PROVIDER, provider.id).apply()
            if (model.isNotEmpty()) prefs.edit().putString(LAST_MODEL, model).apply()
        }
        prefs.edit().remove("base_url").remove("api_key_encrypted").remove("model").apply()
    }

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
