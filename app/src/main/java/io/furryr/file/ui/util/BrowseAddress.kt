package io.furryr.file.ui.util

import android.content.Context
import java.net.URLEncoder

/**
 * Decides whether typed address-bar text is a web address and turns it into a
 * full URL. Bare hosts (`github.com`, `localhost:8080`) get HTTPS; explicit
 * non-web schemes stay with Vfs.
 */
object BrowseAddress {
    private val hostPattern = Regex(
        """^(?:[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?\.)+[A-Za-z]{2,}(?::\d{1,5})?(?:[/?#].*)?$""",
    )
    private val ipPattern = Regex("""^\d{1,3}(?:\.\d{1,3}){3}(?::\d{1,5})?(?:[/?#].*)?$""")

    fun normalize(input: String): String? {
        val text = input.trim()
        if (text.isEmpty() || text.any { it.isWhitespace() }) return null
        val lower = text.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://")) return text
        // A real Vfs scheme must not be rewritten into a web URL.
        if (text.contains("://")) return null
        return when {
            hostPattern.matches(text) -> "https://$text"
            ipPattern.matches(text) -> "http://$text"
            lower == "localhost" || lower.startsWith("localhost:") -> "http://$text"
            else -> null
        }
    }

    fun isWebUrl(text: String): Boolean {
        val lower = text.lowercase()
        return lower.startsWith("http://") || lower.startsWith("https://")
    }

    /**
     * Human name of a web address before the page reports its own title: the
     * last path segment (`.../CMakeLists.txt` → `CMakeLists.txt`), decoded by
     * [java.net.URI], or the host when the address names no file (`https://github.com/`).
     * A page opened from such an address must not be labelled by its domain
     * while it loads, and a text file never gets a WebView title at all.
     */
    fun displayName(address: String): String? = runCatching {
        val uri = java.net.URI(address)
        uri.path.orEmpty().trimEnd('/').substringAfterLast('/')
            .takeIf { it.isNotBlank() }
            ?: uri.host?.takeIf { it.isNotBlank() }
    }.getOrNull()
}

enum class SearchEngine(val label: String, val template: String) {
    GOOGLE("Google", "https://www.google.com/search?q=%s"),
    BING("Bing", "https://www.bing.com/search?q=%s"),
    DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com/?q=%s"),
    BAIDU("百度", "https://www.baidu.com/s?wd=%s"),
}

object SearchSettings {
    private const val PREFS = "settings"
    private const val KEY = "search_engine"
    private const val SUGGESTIONS_KEY = "search_suggestions"

    fun current(context: Context): SearchEngine {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        return SearchEngine.entries.firstOrNull { it.name == raw } ?: SearchEngine.GOOGLE
    }

    fun select(context: Context, engine: SearchEngine) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, engine.name).apply()
    }

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
        return current(context).template.replace("%s", encoded)
    }
}
