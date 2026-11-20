package io.furryr.file.ui.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID

/**
 * One address-bar search engine.
 *
 * [template] is the search URL; every `%s` is replaced with the encoded query.
 * [suggestion] is an optional OpenSearch-compatible JSON endpoint with the same
 * placeholder. Built-in engines ship with both, user engines may omit the
 * suggestion endpoint.
 */
data class SearchEngine(
    val id: String,
    val label: String,
    val template: String,
    val suggestion: String? = null,
)

/**
 * Built-in plus user-defined search engines.
 *
 * The stored representation is one JSON object in
 * `SharedPreferences("search_settings")`, mirroring
 * [io.furryr.file.lsp.LspConfigStore]: `{"version":1,"selected":"...","engines":[...]}`.
 * The user edits it through
 * [io.furryr.file.ui.screens.SearchEngineSettingsActivity], not as a file.
 * DuckDuckGo is the default when nothing has been chosen yet.
 */
object SearchEngines {
    const val DEFAULT_ID = "duckduckgo"
    const val TEMPLATE_TOKEN = "%s"

    private const val PREFS = "search_settings"
    private const val CONFIG = "config"

    /** Legacy preference from the fixed-engine version. */
    private const val LEGACY_PREFS = "settings"
    private const val LEGACY_KEY = "search_engine"

    /** DuckDuckGo leads the list because it is the default engine. */
    val builtIns: List<SearchEngine> = listOf(
        SearchEngine(
            id = "duckduckgo",
            label = "DuckDuckGo",
            template = "https://duckduckgo.com/?q=%s",
            suggestion = "https://duckduckgo.com/ac/?type=list&q=%s",
        ),
        SearchEngine(
            id = "google",
            label = "Google",
            template = "https://www.google.com/search?q=%s",
            suggestion = "https://suggestqueries.google.com/complete/search?client=firefox&q=%s",
        ),
        SearchEngine(
            id = "bing",
            label = "Bing",
            template = "https://www.bing.com/search?q=%s",
            suggestion = "https://api.bing.com/osjson.aspx?query=%s",
        ),
        SearchEngine(
            id = "baidu",
            label = "百度",
            template = "https://www.baidu.com/s?wd=%s",
            suggestion = "https://www.baidu.com/sugrec?json=1&prod=pc&wd=%s",
        ),
    )

    fun isBuiltIn(id: String): Boolean = builtIns.any { it.id == id }

    fun newId(): String = "custom-${UUID.randomUUID()}"

    fun custom(context: Context): List<SearchEngine> = load(context).engines

    fun all(context: Context): List<SearchEngine> = builtIns + custom(context)

    fun current(context: Context): SearchEngine {
        val config = load(context)
        return (builtIns + config.engines).firstOrNull { it.id == config.selected }
            ?: builtIns.first { it.id == DEFAULT_ID }
    }

    fun select(context: Context, engine: SearchEngine) {
        val config = load(context)
        save(context, config.copy(selected = engine.id))
    }

    /** Insert or replace one user engine. Built-in ids are ignored. */
    fun save(context: Context, engine: SearchEngine) {
        if (isBuiltIn(engine.id)) return
        val config = load(context)
        val engines = config.engines.filterNot { it.id == engine.id } + engine
        save(context, config.copy(engines = engines))
    }

    fun delete(context: Context, id: String) {
        if (isBuiltIn(id)) return
        val config = load(context)
        val selected = if (config.selected == id) DEFAULT_ID else config.selected
        save(context, config.copy(selected = selected, engines = config.engines.filterNot { it.id == id }))
    }

    /** A search template must be an http(s) URL with a query placeholder. */
    fun isValidTemplate(value: String): Boolean {
        val text = value.trim()
        if (!text.contains(TEMPLATE_TOKEN)) return false
        return text.startsWith("http://", ignoreCase = true) ||
            text.startsWith("https://", ignoreCase = true)
    }

    /** Suggestion URL for [query], or null when the engine has no endpoint. */
    fun suggestionUrl(context: Context, query: String): String? {
        val template = current(context).suggestion ?: return null
        return template.replace(TEMPLATE_TOKEN, encode(query))
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private data class Config(
        val selected: String,
        val engines: List<SearchEngine>,
    )

    private fun load(context: Context): Config {
        val raw = prefs(context).getString(CONFIG, null)
        val root = raw?.let { runCatching { JSONObject(it) }.getOrNull() }
        val engines = mutableListOf<SearchEngine>()
        root?.optJSONArray("engines")?.let { rows ->
            for (index in 0 until rows.length()) {
                val value = rows.optJSONObject(index) ?: continue
                val engine = engine(value) ?: continue
                if (!isBuiltIn(engine.id)) engines += engine
            }
        }
        val selected = root?.optString("selected").orEmpty()
            .ifBlank { legacySelection(context) ?: DEFAULT_ID }
        return Config(selected, engines)
    }

    private fun save(context: Context, config: Config) {
        val root = JSONObject().apply {
            put("version", 1)
            put("selected", config.selected)
            put("engines", JSONArray().apply {
                for (engine in config.engines) {
                    if (engine.id.isBlank() || isBuiltIn(engine.id)) continue
                    put(JSONObject().apply {
                        put("id", engine.id)
                        put("label", engine.label)
                        put("template", engine.template)
                        engine.suggestion?.takeIf { it.isNotBlank() }?.let { put("suggestion", it) }
                    })
                }
            })
        }
        prefs(context).edit().putString(CONFIG, root.toString()).apply()
    }

    private fun engine(value: JSONObject): SearchEngine? {
        val id = value.optString("id").takeIf { it.isNotBlank() } ?: return null
        val label = value.optString("label").takeIf { it.isNotBlank() } ?: return null
        val template = value.optString("template").takeIf { it.isNotBlank() } ?: return null
        val suggestion = value.optString("suggestion").takeIf { it.isNotBlank() }
        return SearchEngine(id, label, template, suggestion)
    }

    /** Old builds stored the enum name (`GOOGLE`, `DUCKDUCKGO`, ...). */
    private fun legacySelection(context: Context): String? {
        val raw = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
            .getString(LEGACY_KEY, null)
            ?: return null
        return builtIns.firstOrNull { it.id.equals(raw, ignoreCase = true) }?.id
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * Query URL and internet-suggestion preference. Suggestions remain in the
 * general `settings` preferences because they are a browse toggle, not an
 * engine definition.
 */
object SearchSettings {
    private const val PREFS = "settings"
    private const val SUGGESTIONS_KEY = "search_suggestions"

    /** Internet suggestions are on unless the user turns them off. */
    fun suggestionsEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(SUGGESTIONS_KEY, true)

    fun setSuggestionsEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(SUGGESTIONS_KEY, enabled).apply()
    }

    fun searchUrl(context: Context, query: String): String {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return SearchEngines.current(context).template
            .replace(SearchEngines.TEMPLATE_TOKEN, encoded)
    }
}
