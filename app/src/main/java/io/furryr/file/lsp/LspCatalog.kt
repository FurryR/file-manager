package io.furryr.file.lsp

import android.content.Context
import android.util.Log
import io.furryr.file.core.VFile
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Built-in language directory plus one optional user overlay.
 *
 * The catalog is data only. It never installs a server, never starts one and
 * never keeps runtime state. A bad user entry is skipped; the built-ins keep
 * working.
 */
internal class LspCatalog private constructor(
    private val servers: Map<String, LspServerSpec>,
    private val languages: Map<String, LspLanguageSpec>,
    private val overrides: List<LspOverride>,
) {

    fun languageFor(file: VFile, editorLanguageId: String?): LspLanguageSpec? {
        val name = file.name.lowercase(Locale.ROOT)
        val extension = name.substringAfterLast('.', "")
        return languages.values.firstOrNull { name in it.exactNames }
            ?: languages.values.firstOrNull { extension.isNotEmpty() && extension in it.extensions }
            ?: editorLanguageId?.let { languages[it] }
    }

    fun languageId(language: LspLanguageSpec, file: VFile): String {
        val name = file.name.lowercase(Locale.ROOT)
        val extension = name.substringAfterLast('.', "")
        return language.lspLanguageIds[extension] ?: language.id
    }

    fun language(id: String): LspLanguageSpec? = languages[id]

    fun markers(language: LspLanguageSpec?, server: LspServerSpec, override: LspOverride?): Set<String> =
        (override?.markers ?: language?.markers ?: emptySet()) + server.rootMarkers + ROOT_MARKER

    /** Longest matching VFS address prefix, if any. */
    fun overrideFor(file: VFile): LspOverride? {
        val address = file.path.toString()
        return overrides.asSequence()
            .filter { matches(it.prefix, address) }
            .maxByOrNull { it.prefix.length }
    }

    /** Build a server from an override that supplies its own command. */
    fun overrideServer(override: LspOverride): LspServerSpec? {
        val command = override.command ?: return null
        return LspServerSpec(
            id = "override:${override.prefix}",
            command = command,
            args = override.args.orEmpty(),
            env = override.env.orEmpty(),
            rootMarkers = override.markers.orEmpty(),
            rootMode = override.rootMode ?: LspRootMode.NEAREST,
            workspace = override.workspace ?: LspWorkspaceMode.EXEC,
            initializationOptions = override.initializationOptions,
            settings = override.settings,
            enabled = override.enabled,
        )
    }

    /** Apply the non-null override fields to an inherited server. */
    fun applyOverride(server: LspServerSpec, override: LspOverride): LspServerSpec = server.copy(
        args = override.args ?: server.args,
        env = override.env ?: server.env,
        rootMarkers = override.markers ?: server.rootMarkers,
        rootMode = override.rootMode ?: server.rootMode,
        workspace = override.workspace ?: server.workspace,
        initializationOptions = override.initializationOptions ?: server.initializationOptions,
        settings = override.settings ?: server.settings,
        enabled = override.enabled && server.enabled,
    )

    fun candidates(language: LspLanguageSpec): List<LspServerSpec> =
        language.servers.mapNotNull { servers[it] }
            .filter { it.enabled && it.command.isNotEmpty() }

    /** Languages a user added through settings, for the editor's language picker. */
    fun editorLanguages(): List<LspLanguageSpec> = languages.values.toList()

    companion object {
        /** A user-created marker file is the explicit, stateless workspace root. */
        const val ROOT_MARKER = ".lsp-root"

        private const val TAG = "Lsp"
        private const val BUILTIN = "lsp/catalog.json"

        fun load(context: Context): LspCatalog {
            val builtin = runCatching {
                context.assets.open(BUILTIN).bufferedReader().use { JSONObject(it.readText()) }
            }.getOrElse {
                Log.d(TAG, "Built-in language catalog unavailable", it)
                JSONObject()
            }
            val user = LspConfigStore.json(context)
            val merged = if (user == null) builtin else merge(builtin, user)
            return runCatching { parse(merged) }.getOrElse {
                Log.d(TAG, "Ignoring invalid language server catalog", it)
                LspCatalog(emptyMap(), emptyMap(), emptyList())
            }
        }

        private fun parse(root: JSONObject): LspCatalog {
            val servers = linkedMapOf<String, LspServerSpec>()
            val languages = linkedMapOf<String, LspLanguageSpec>()
            val languageObject = root.optJSONObject("languages") ?: JSONObject()
            for (id in languageObject.keys().asSequence().sorted()) {
                val value = languageObject.optJSONObject(id) ?: continue
                val language = runCatching { parseLanguage(id, value) }.getOrNull() ?: continue
                val inline = runCatching { parseInlineServer(id, value) }.getOrNull()
                if (inline != null) {
                    servers[inline.id] = inline
                    languages[id] = language.copy(servers = listOf(inline.id))
                } else {
                    languages[id] = language
                }
            }
            val overrides = runCatching { parseOverrides(root.optJSONArray("overrides")) }
                .getOrDefault(emptyList())
            return LspCatalog(servers, languages, overrides)
        }

        private fun matches(prefix: String, address: String): Boolean {
            if (prefix.isBlank()) return false
            if (address == prefix) return true
            return address.startsWith(prefix.trimEnd('/') + "/")
        }

        private fun parseLanguage(id: String, value: JSONObject): LspLanguageSpec {
            val extensions = strings(value.optJSONArray("extensions"))
                .map { it.trimStart('.').lowercase(Locale.ROOT) }
                .filter { it.isNotEmpty() }
                .toSet()
            val exactNames = strings(value.optJSONArray("exactNames"))
                .map { it.lowercase(Locale.ROOT) }
                .toSet()
            val languageIds = linkedMapOf<String, String>()
            val ids = value.optJSONObject("lspLanguageIds") ?: JSONObject()
            for (key in ids.keys()) languageIds[key.lowercase(Locale.ROOT)] = ids.optString(key, id)
            return LspLanguageSpec(
                id = id,
                label = value.optString("label").takeIf { it.isNotBlank() },
                extensions = extensions,
                exactNames = exactNames,
                lspLanguageIds = languageIds,
                markers = strings(value.optJSONArray("markers")).toSet(),
                servers = emptyList(),
            )
        }

        /**
         * A language entry may own one command directly. Older catalogs keep
         * the reusable `servers` map; both shapes are accepted.
         */
        private fun parseInlineServer(languageId: String, value: JSONObject): LspServerSpec? {
            val command = strings(value.optJSONArray("command"))
            if (command.isEmpty()) return null
            return LspServerSpec(
                id = languageId,
                command = command,
                args = strings(value.optJSONArray("args")),
                env = stringMap(value.optJSONObject("env")),
                rootMarkers = strings(value.optJSONArray("rootMarkers")).toSet(),
                rootMode = parseRootMode(value.optString("rootMode")),
                workspace = parseWorkspace(value.optString("workspace")) ?: LspWorkspaceMode.EXEC,
                initializationOptions = value.optJSONObject("initializationOptions"),
                settings = value.optJSONObject("settings"),
                enabled = value.optBoolean("enabled", true),
            )
        }

        private fun parseOverrides(array: JSONArray?): List<LspOverride> {
            if (array == null) return emptyList()
            val result = mutableListOf<LspOverride>()
            for (index in 0 until array.length()) {
                val value = array.optJSONObject(index) ?: continue
                val prefix = value.optString("prefix")
                if (prefix.isNotBlank()) result += parseOverride(prefix, value)
            }
            return result
        }

        private fun parseOverride(prefix: String, value: JSONObject): LspOverride = LspOverride(
            prefix = prefix,
            enabled = value.optBoolean("enabled", true),
            language = value.optString("language").takeIf { it.isNotBlank() },
            command = strings(value.optJSONArray("command")).takeIf { it.isNotEmpty() },
            args = value.optJSONArray("args")?.let(::strings),
            env = value.optJSONObject("env")?.let(::stringMap),
            markers = strings(value.optJSONArray("markers")).takeIf { it.isNotEmpty() }?.toSet(),
            workspace = parseWorkspace(value.optString("workspace")),
            rootMode = value.optString("rootMode").takeIf { it.isNotBlank() }?.let(::parseRootMode),
            initializationOptions = value.optJSONObject("initializationOptions"),
            settings = value.optJSONObject("settings"),
        )

        private fun parseRootMode(value: String): LspRootMode =
            if (value.equals("outermost", true)) LspRootMode.OUTERMOST else LspRootMode.NEAREST

        private fun parseWorkspace(value: String): LspWorkspaceMode? = when (value.lowercase(Locale.ROOT)) {
            "exec" -> LspWorkspaceMode.EXEC
            "mirror" -> LspWorkspaceMode.MIRROR
            "virtual" -> LspWorkspaceMode.VIRTUAL
            else -> null
        }

        private fun strings(array: JSONArray?): List<String> {
            if (array == null) return emptyList()
            return (0 until array.length()).mapNotNull { index ->
                array.optString(index).takeIf { it.isNotBlank() }
            }
        }

        private fun stringMap(objectValue: JSONObject?): Map<String, String> {
            if (objectValue == null) return emptyMap()
            val result = linkedMapOf<String, String>()
            for (key in objectValue.keys()) result[key] = objectValue.optString(key)
            return result
        }

        /** Deep merge: objects merge, arrays/scalars replace, null removes. */
        private fun merge(base: JSONObject, overlay: JSONObject): JSONObject {
            val result = JSONObject(base.toString())
            for (key in overlay.keys()) {
                val value = overlay.opt(key)
                if (value == null || value === JSONObject.NULL) {
                    result.remove(key)
                    continue
                }
                val existing = result.opt(key)
                if (value is JSONObject && existing is JSONObject) {
                    result.put(key, merge(existing, value))
                } else {
                    result.put(key, value)
                }
            }
            return result
        }
    }
}


