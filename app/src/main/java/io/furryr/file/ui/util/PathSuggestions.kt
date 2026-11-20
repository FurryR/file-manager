package io.furryr.file.ui.util

import android.content.Context
import io.furryr.file.core.FileWorld
import io.furryr.file.core.Path
import io.furryr.file.core.VFolder
import io.furryr.file.core.VNode
import io.furryr.file.home.HomeEntry
import io.furryr.file.home.HomeEntryKind
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** Where a path-bar suggestion came from. */
enum class SuggestionSource { PATH, BOOKMARK, RECENT, INTERNET }

/**
 * One row in the address-bar overlay. [target] is what selecting the row
 * navigates to: an address under the typed folder, a stored location for a
 * bookmark/recent entry, or the search term for an internet suggestion.
 */
data class PathSuggestion(
    val source: SuggestionSource,
    val title: String,
    val subtitle: String = "",
    val target: String = title,
    /** Path rows use this to pick a folder or file icon. */
    val isDirectory: Boolean = false,
)

/**
 * Where the address bar gets rows while the user types: saved/visited
 * locations are synchronous, entries under the typed path and internet
 * completions suspend. One source keeps every half behind the same call site.
 */
interface PathSuggestionSource {
    fun local(query: String): List<PathSuggestion>
    suspend fun remote(query: String): List<PathSuggestion>

    /** Entries in the folder the typed address names, best match first. */
    suspend fun listing(query: String): List<PathSuggestion> = emptyList()
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
    private const val MAX_PATH = 8
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

    /**
     * Entries in the folder the typed address points at.
     *
     * `/storage/emulated/0/Doc` lists `/storage/emulated/0` and keeps names
     * starting with `Doc`; a trailing slash lists every child. The folder is
     * resolved through [FileWorld], so local, SAF, FTP, SFTP, remote,
     * container and archive view paths all suggest their own entries. Rows
     * are directories first, then by name, so completion beats bookmark and
     * search rows.
     */
    suspend fun listing(
        files: FileWorld,
        query: String,
        includeFiles: Boolean = true,
    ): List<PathSuggestion> {
        val text = query.trim()
        if (!isListingAddress(text)) return emptyList()
        val (parent, prefix) = splitListingAddress(text) ?: return emptyList()
        val parentPath = runCatching { Path.parse(parent) }.getOrNull() ?: return emptyList()
        val folder = files.vfs.folder(parentPath).getOrElse { return emptyList() }
        val nodes = folder.listNodes().getOrElse { return emptyList() }
        val needle = prefix.trim()
        return nodes.asSequence()
            .filter { includeFiles || it is VFolder }
            .filter { needle.isEmpty() || it.name.startsWith(needle, ignoreCase = true) }
            .sortedWith(
                compareByDescending<VNode> { it is VFolder }
                    .thenBy { it.name.lowercase(Locale.ROOT) },
            )
            .take(MAX_PATH)
            .map { node ->
                val address = node.path.toString()
                PathSuggestion(
                    source = SuggestionSource.PATH,
                    title = node.name,
                    subtitle = address,
                    target = address,
                    isDirectory = node is VFolder,
                )
            }
            .toList()
    }

    /**
     * Only Vfs-addressed text lists its folder. Web addresses (including a
     * bare host) stay with the browser and internet suggestions.
     */
    private fun isListingAddress(text: String): Boolean {
        if (text.isEmpty() || BrowseAddress.isWebUrl(text)) return false
        return text.startsWith('/') || text.contains("://")
    }

    /**
     * Split typed text into the folder to list and the name being completed.
     * A trailing slash means "list this folder"; a bare authority has no
     * folder to list yet.
     */
    private fun splitListingAddress(text: String): Pair<String, String>? {
        val authority = text.indexOf("://")
        val searchFrom = if (authority >= 0) authority + 3 else 0
        val slash = text.lastIndexOf('/')
        if (slash < searchFrom) return null
        return if (slash == text.lastIndex) {
            text to ""
        } else {
            text.substring(0, slash + 1) to text.substring(slash + 1)
        }
    }

    suspend fun remote(context: Context, query: String): List<PathSuggestion> {
        val text = query.trim()
        if (text.isEmpty()) return emptyList()
        if (!SearchSettings.suggestionsEnabled(context)) return emptyList()
        // A legal address keeps the overlay local, exactly like a browser.
        if (isAddress(text)) return emptyList()
        val engine = SearchEngines.current(context)
        val url = SearchEngines.suggestionUrl(context, text) ?: return emptyList()
        val body = fetch(url) ?: return emptyList()
        return parse(body)
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

    /**
     * Accept the suggestion shapes real engines return:
     * OpenSearch `["query", ["row", ...]]`, DuckDuckGo `[{"phrase": "..."}]`,
     * Baidu `{"g":[{"q": "..."}]}` and a plain string array. User engines may
     * use any of them.
     */
    private fun parse(body: String): List<String> = runCatching {
        when (val root = JSONTokener(body).nextValue()) {
            is JSONObject -> objectSuggestions(root)
            is JSONArray -> arraySuggestions(root)
            else -> emptyList()
        }
    }.getOrDefault(emptyList())

    private fun objectSuggestions(value: JSONObject): List<String> =
        value.optJSONArray("g")?.let(::rowSuggestions)
            ?: value.optJSONArray("suggestions")?.let(::rowSuggestions)
            ?: emptyList()

    private fun arraySuggestions(value: JSONArray): List<String> {
        value.optJSONArray(1)?.let { return rowSuggestions(it) }
        return (0 until value.length()).mapNotNull { index ->
            suggestionText(value.opt(index))
        }
    }

    private fun rowSuggestions(rows: JSONArray): List<String> =
        (0 until rows.length()).mapNotNull { index -> suggestionText(rows.opt(index)) }

    private fun suggestionText(value: Any?): String? = when (value) {
        is String -> value.takeIf { it.isNotEmpty() }
        is JSONObject -> value.optString("phrase").takeIf { it.isNotEmpty() }
            ?: value.optString("q").takeIf { it.isNotEmpty() }
        is JSONArray -> value.optString(0).takeIf { it.isNotEmpty() }
        else -> null
    }
}
