package io.furryr.file.lsp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One user-defined language override. Empty fields inherit the built-in entry. */
internal data class LspLanguageRule(
    val id: String,
    val label: String = "",
    val extensions: Set<String> = emptySet(),
    val exactNames: Set<String> = emptySet(),
    val markers: Set<String> = emptySet(),
    val command: List<String> = emptyList(),
    val args: List<String> = emptyList(),
    val workspace: String = "exec",
    val rootMode: String = "nearest",
    val enabled: Boolean = true,
    val lspLanguageIds: Map<String, String> = emptyMap(),
    val env: Map<String, String> = emptyMap(),
    val initializationOptions: JSONObject? = null,
    val settings: JSONObject? = null,
)

/** One user-defined VFS-prefix override. Empty fields inherit the language/server. */
internal data class LspOverrideRule(
    val prefix: String,
    val language: String = "",
    val markers: Set<String> = emptySet(),
    val command: List<String> = emptyList(),
    val args: List<String> = emptyList(),
    val workspace: String = "",
    val rootMode: String = "",
    val enabled: Boolean = true,
    val env: Map<String, String> = emptyMap(),
    val initializationOptions: JSONObject? = null,
    val settings: JSONObject? = null,
)

internal data class LspUserConfig(
    val languages: List<LspLanguageRule> = emptyList(),
    val overrides: List<LspOverrideRule> = emptyList(),
)

/**
 * LSP user configuration in the same SharedPreferences family as the rest of
 * the app settings. The stored representation is internal; the user edits it
 * through [io.furryr.file.ui.screens.LspSettingsActivity].
 */
internal object LspConfigStore {
    private const val PREFS = "lsp_settings"
    private const val CONFIG = "config"
    private const val GENERATION = "generation"
    private const val LEGACY_PREFS = "editor_lsp"
    private const val LEGACY_SUFFIX = ".command"

    fun load(context: Context): LspUserConfig {
        val raw = prefs(context).getString(CONFIG, null) ?: return LspUserConfig()
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return LspUserConfig()
        val languages = mutableListOf<LspLanguageRule>()
        root.optJSONObject("languages")?.let { objectValue ->
            for (id in objectValue.keys()) {
                val value = objectValue.optJSONObject(id) ?: continue
                languages += languageRule(id, value)
            }
        }
        val overrides = mutableListOf<LspOverrideRule>()
        root.optJSONArray("overrides")?.let { array ->
            for (index in 0 until array.length()) {
                val value = array.optJSONObject(index) ?: continue
                val prefix = value.optString("prefix")
                if (prefix.isNotBlank()) overrides += overrideRule(prefix, value)
            }
        }
        return LspUserConfig(languages, overrides)
    }

    fun save(context: Context, config: LspUserConfig) {
        val prefs = prefs(context)
        prefs.edit()
            .putString(CONFIG, toJson(config).toString())
            .putInt(GENERATION, prefs.getInt(GENERATION, 0) + 1)
            .apply()
    }

    fun generation(context: Context): Int = prefs(context).getInt(GENERATION, 0)

    /** JSON view used by the resolver; null when the user has no overrides. */
    fun json(context: Context): JSONObject? {
        val config = load(context)
        if (config.languages.isEmpty() && config.overrides.isEmpty()) return null
        return toJson(config)
    }

    /**
     * Bridge the old `editor_lsp` per-language commands. Container/root are
     * not copied: the runtime derives both from the file's VFS node.
     */
    fun migrateLegacy(context: Context) {
        val legacy = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        if (legacy.all.isEmpty()) return
        if (prefs(context).contains(CONFIG)) {
            legacy.edit().clear().apply()
            return
        }
        val languages = legacy.all.mapNotNull { (key, value) ->
            if (!key.endsWith(LEGACY_SUFFIX)) return@mapNotNull null
            val id = key.removeSuffix(LEGACY_SUFFIX)
            val command = (value as? String)?.trim().orEmpty()
            if (id.isBlank() || command.isBlank()) return@mapNotNull null
            LspLanguageRule(
                id = id,
                label = id,
                command = listOf("/bin/sh"),
                args = listOf("-c", "exec $command"),
            )
        }
        if (languages.isNotEmpty()) save(context, LspUserConfig(languages = languages))
        legacy.edit().clear().apply()
    }

    private fun languageRule(id: String, value: JSONObject): LspLanguageRule {
        val ids = linkedMapOf<String, String>()
        value.optJSONObject("lspLanguageIds")?.let { objectValue ->
            for (key in objectValue.keys()) ids[key] = objectValue.optString(key)
        }
        return LspLanguageRule(
            id = id,
            label = value.optString("label"),
            extensions = strings(value.optJSONArray("extensions")).toSet(),
            exactNames = strings(value.optJSONArray("exactNames")).toSet(),
            markers = strings(value.optJSONArray("markers")).toSet(),
            command = strings(value.optJSONArray("command")),
            args = strings(value.optJSONArray("args")),
            workspace = value.optString("workspace", "exec"),
            rootMode = value.optString("rootMode", "nearest"),
            enabled = value.optBoolean("enabled", true),
            lspLanguageIds = ids,
            env = stringMap(value.optJSONObject("env")),
            initializationOptions = value.optJSONObject("initializationOptions"),
            settings = value.optJSONObject("settings"),
        )
    }

    private fun overrideRule(prefix: String, value: JSONObject): LspOverrideRule = LspOverrideRule(
        prefix = prefix,
        language = value.optString("language"),
        markers = strings(value.optJSONArray("markers")).toSet(),
        command = strings(value.optJSONArray("command")),
        args = strings(value.optJSONArray("args")),
        workspace = value.optString("workspace"),
        rootMode = value.optString("rootMode"),
        enabled = value.optBoolean("enabled", true),
        env = stringMap(value.optJSONObject("env")),
        initializationOptions = value.optJSONObject("initializationOptions"),
        settings = value.optJSONObject("settings"),
    )

    private fun toJson(config: LspUserConfig): JSONObject = JSONObject().apply {
        put("version", 1)
        put("languages", JSONObject().apply {
            for (language in config.languages) {
                if (language.id.isBlank()) continue
                put(language.id, JSONObject().apply {
                    if (language.label.isNotBlank()) put("label", language.label)
                    if (language.extensions.isNotEmpty()) put("extensions", JSONArray(language.extensions))
                    if (language.exactNames.isNotEmpty()) put("exactNames", JSONArray(language.exactNames))
                    if (language.markers.isNotEmpty()) put("markers", JSONArray(language.markers))
                    if (language.command.isNotEmpty()) put("command", JSONArray(language.command))
                    if (language.args.isNotEmpty()) put("args", JSONArray(language.args))
                    if (language.workspace.isNotBlank()) put("workspace", language.workspace)
                    if (language.rootMode.isNotBlank()) put("rootMode", language.rootMode)
                    if (!language.enabled) put("enabled", false)
                    if (language.lspLanguageIds.isNotEmpty()) put("lspLanguageIds", JSONObject().apply {
                        for ((extension, id) in language.lspLanguageIds) put(extension, id)
                    })
                    if (language.env.isNotEmpty()) put("env", JSONObject().apply {
                        for ((key, value) in language.env) put(key, value)
                    })
                    language.initializationOptions?.let { put("initializationOptions", it) }
                    language.settings?.let { put("settings", it) }
                })
            }
        })
        put("overrides", JSONArray().apply {
            for (override in config.overrides) {
                if (override.prefix.isBlank()) continue
                put(JSONObject().apply {
                    put("prefix", override.prefix)
                    if (override.language.isNotBlank()) put("language", override.language)
                    if (override.markers.isNotEmpty()) put("markers", JSONArray(override.markers))
                    if (override.command.isNotEmpty()) put("command", JSONArray(override.command))
                    if (override.args.isNotEmpty()) put("args", JSONArray(override.args))
                    if (override.workspace.isNotBlank()) put("workspace", override.workspace)
                    if (override.rootMode.isNotBlank()) put("rootMode", override.rootMode)
                    if (!override.enabled) put("enabled", false)
                    if (override.env.isNotEmpty()) put("env", JSONObject().apply {
                        for ((key, value) in override.env) put(key, value)
                    })
                    override.initializationOptions?.let { put("initializationOptions", it) }
                    override.settings?.let { put("settings", it) }
                })
            }
        })
    }

    private fun stringMap(value: JSONObject?): Map<String, String> {
        if (value == null) return emptyMap()
        return buildMap {
            for (key in value.keys()) put(key, value.optString(key))
        }
    }

    private fun strings(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull {
            array.optString(it).takeIf(String::isNotBlank)
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
