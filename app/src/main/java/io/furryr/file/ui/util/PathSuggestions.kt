package io.furryr.file.ui.util

import android.content.Context
import io.furryr.file.home.HomeEntry
import io.furryr.file.home.HomeEntryKind
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Where a path-bar suggestion came from. */
enum class SuggestionSource { BOOKMARK, RECENT, INTERNET }

/**
 * One row in the address-bar overlay. [target] is what selecting the row
 * navigates to: a stored location for a bookmark/recent entry, or the search
 * term for an internet suggestion.
 */
data class PathSuggestion(
    val source: SuggestionSource,
    val title: String,
    val subtitle: String = "",
    val target: String = title,
)

/**
 * Where the address bar gets rows while the user types: saved/visited
 * locations are synchronous, internet completions suspend. One source keeps
 * both halves behind the same call site.
 */
interface PathSuggestionSource {
    fun local(query: String): List<PathSuggestion>
    suspend fun remote(query: String): List<PathSuggestion>
}

/**
 * Local and remote address-bar suggestions.
 *
 * Bookmarks and recent locations are filtered from the same lists the home
 * page shows, so nothing here owns persistence. Internet suggestions use the
 * selected search engine's completion endpoint; an address-like query never
 * leaves the device, matching a browser's behaviour.
 */
object PathSuggestions {
    private const val MAX_LOCAL = 8
    private const val MAX_REMOTE = 8
    private const val TIMEOUT_MS = 1500

    fun local(
        query: String,
        bookmarks: List<HomeEntry>,
        recent: List<HomeEntry>,
    ): List<PathSuggestion> {
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()
        fun matches(entry: HomeEntry): Boolean =
            entry.title.contains(needle, ignoreCase = true) ||
                entry.location.contains(needle, ignoreCase = true) ||
                entry.detail.contains(needle, ignoreCase = true)

        fun HomeEntry.toSuggestion(source: SuggestionSource): PathSuggestion {
            val displayTitle = title.ifBlank { location }
            return PathSuggestion(
                source = source,
                title = displayTitle,
                subtitle = when {
                    location != displayTitle -> location
                    detail.isNotBlank() && detail != displayTitle -> detail
                    else -> ""
                },
                target = location,
            )
        }

        val saved = bookmarks.filter(::matches)
            .map { it.toSuggestion(SuggestionSource.BOOKMARK) }
        val savedLocations = saved.mapTo(HashSet()) { it.target }
        val visited = recent
            .filter { it.kind != HomeEntryKind.FILE }
            .filter(::matches)
            .filterNot { it.location in savedLocations }
            .map { it.toSuggestion(SuggestionSource.RECENT) }
        return (saved + visited).take(MAX_LOCAL)
    }

    suspend fun remote(context: Context, query: String): List<PathSuggestion> {
        val text = query.trim()
        if (text.isEmpty()) return emptyList()
        if (!SearchSettings.suggestionsEnabled(context)) return emptyList()
        // A legal address keeps the overlay local, exactly like a browser.
        if (isAddress(text)) return emptyList()
        val engine = SearchSettings.current(context)
        val body = fetch(suggestUrl(engine, text)) ?: return emptyList()
        return parse(engine, body)
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != text }
            .distinct()
            .take(MAX_REMOTE)
            .map {
                PathSuggestion(
                    source = SuggestionSource.INTERNET,
                    title = it,
                    subtitle = engine.label,
                    target = it,
                )
            }
    }

    private fun isAddress(text: String): Boolean =
        text.startsWith('/') || text.contains("://") || BrowseAddress.normalize(text) != null

    private fun suggestUrl(engine: SearchEngine, query: String): String {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return when (engine) {
            SearchEngine.GOOGLE ->
                "https://suggestqueries.google.com/complete/search?client=firefox&q=$encoded"
            SearchEngine.BING ->
                "https://api.bing.com/osjson.aspx?query=$encoded"
            SearchEngine.DUCKDUCKGO ->
                "https://duckduckgo.com/ac/?type=list&q=$encoded"
            SearchEngine.BAIDU ->
                "https://www.baidu.com/sugrec?json=1&prod=pc&wd=$encoded"
        }
    }

    private fun fetch(url: String): String? = runCatching {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json, text/javascript, */*")
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) FileManager")
            if (connection.responseCode !in 200..299) return@runCatching null
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    private fun parse(engine: SearchEngine, body: String): List<String> = runCatching {
        when (engine) {
            SearchEngine.DUCKDUCKGO -> {
                val rows = JSONArray(body)
                (0 until rows.length()).mapNotNull { index ->
                    rows.optJSONObject(index)?.optString("phrase")
                }
            }
            SearchEngine.BAIDU -> {
                val rows = JSONObject(body).optJSONArray("g") ?: JSONArray()
                (0 until rows.length()).mapNotNull { index ->
                    rows.optJSONObject(index)?.optString("q")
                }
            }
            // Google and Bing share the OpenSearch suggestion shape:
            // ["query", ["suggestion", ...]].
            else -> {
                val rows = JSONArray(body).optJSONArray(1) ?: JSONArray()
                (0 until rows.length()).mapNotNull { index ->
                    rows.optString(index).takeIf { it.isNotEmpty() }
                }
            }
        }
    }.getOrDefault(emptyList())
}
