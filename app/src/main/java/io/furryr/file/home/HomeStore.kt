package io.furryr.file.home

import android.content.Context
import android.content.SharedPreferences
import io.furryr.file.HomePath
import io.furryr.file.core.Path
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** What a home entry points at. */
enum class HomeEntryKind { FILE, FOLDER, WEB }

/**
 * One row on the home page. [location] is a Vfs path or a web address; the
 * home screen never stores a provider object, so a stale entry can fail
 * gracefully when it is opened again.
 */
data class HomeEntry(
    val kind: HomeEntryKind,
    val location: String,
    val title: String,
    val detail: String = "",
    val visitedAt: Long = 0L,
) {
    val key: String get() = "${kind.name}:$location"
}

/**
 * Persistent recent locations/files and bookmarks behind the home page.
 *
 * The store is deliberately tiny: navigation code records a visit, the home
 * screen reads the two lists. Both are SharedPreferences JSON, so the process
 * can restart without losing the user's trail.
 */
object HomeStore {
    private const val RECENT_KEY = "recent"
    private const val BOOKMARK_KEY = "bookmarks"
    private const val MAX_RECENT = 80
    private const val MAX_BOOKMARKS = 200

    private lateinit var prefs: SharedPreferences

    @Synchronized
    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("home", Context.MODE_PRIVATE)
    }

    @Synchronized
    fun recent(): List<HomeEntry> = read(RECENT_KEY)

    @Synchronized
    fun bookmarks(): List<HomeEntry> = read(BOOKMARK_KEY)

    /** Record a visit, moving an existing entry to the front. */
    @Synchronized
    fun record(
        kind: HomeEntryKind,
        location: String,
        title: String,
        detail: String = "",
    ): Boolean {
        val clean = location.trim()
        if (clean.isEmpty() || clean == HomePath) return false
        val now = System.currentTimeMillis()
        val entry = HomeEntry(kind, clean, cleanTitle(kind, clean, title), detail, now)
        val recents = (listOf(entry) + read(RECENT_KEY).filterNot { it.key == entry.key })
            .take(MAX_RECENT)
        write(RECENT_KEY, recents)
        // Keep bookmark metadata fresh without reordering the bookmark list.
        val marks = read(BOOKMARK_KEY)
        if (marks.any { it.key == entry.key }) {
            write(
                BOOKMARK_KEY,
                marks.map {
                    if (it.key == entry.key) {
                        it.copy(title = entry.title, detail = entry.detail)
                    } else {
                        it
                    }
                },
            )
        }
        return true
    }

    /** Refresh the display title of a known location (e.g. a page title). */
    @Synchronized
    fun updateTitle(location: String, title: String) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        updateList(RECENT_KEY) { entry ->
            if (entry.location == location) entry.copy(title = trimmed) else entry
        }
        updateList(BOOKMARK_KEY) { entry ->
            if (entry.location == location) entry.copy(title = trimmed) else entry
        }
    }

    /** Add or remove a bookmark. Returns true when it is now bookmarked. */
    @Synchronized
    fun toggleBookmark(entry: HomeEntry): Boolean {
        val marks = read(BOOKMARK_KEY)
        val exists = marks.any { it.key == entry.key }
        if (exists) {
            write(BOOKMARK_KEY, marks.filterNot { it.key == entry.key })
            return false
        }
        val now = System.currentTimeMillis()
        val bookmark = entry.copy(
            title = cleanTitle(entry.kind, entry.location, entry.title),
            visitedAt = now,
        )
        write(BOOKMARK_KEY, (listOf(bookmark) + marks).take(MAX_BOOKMARKS))
        return true
    }

    @Synchronized
    fun isBookmarked(kind: HomeEntryKind, location: String): Boolean =
        read(BOOKMARK_KEY).any { it.kind == kind && it.location == location }

    @Synchronized
    fun removeRecent(key: String) {
        write(RECENT_KEY, read(RECENT_KEY).filterNot { it.key == key })
    }

    @Synchronized
    fun removeBookmark(key: String) {
        write(BOOKMARK_KEY, read(BOOKMARK_KEY).filterNot { it.key == key })
    }

    @Synchronized
    fun clearRecent() {
        write(RECENT_KEY, emptyList())
    }

    @Synchronized
    fun clearBookmarks() {
        write(BOOKMARK_KEY, emptyList())
    }

    private fun updateList(key: String, transform: (HomeEntry) -> HomeEntry) {
        val before = read(key)
        val after = before.map(transform)
        if (after != before) write(key, after)
    }

    private fun read(key: String): List<HomeEntry> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        return runCatching {
            val records = JSONArray(raw)
            (0 until records.length()).mapNotNull { index ->
                val row = records.optJSONObject(index) ?: return@mapNotNull null
                val kind = runCatching {
                    HomeEntryKind.valueOf(row.getString("kind"))
                }.getOrNull() ?: return@mapNotNull null
                HomeEntry(
                    kind = kind,
                    location = row.getString("location"),
                    title = row.optString("title"),
                    detail = row.optString("detail"),
                    visitedAt = row.optLong("visitedAt"),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun write(key: String, items: List<HomeEntry>) {
        val records = JSONArray()
        items.forEach { item ->
            records.put(
                JSONObject()
                    .put("kind", item.kind.name)
                    .put("location", item.location)
                    .put("title", item.title)
                    .put("detail", item.detail)
                    .put("visitedAt", item.visitedAt),
            )
        }
        // apply(): navigation records a visit on the UI path; a synchronous
        // commit would put disk I/O on the main thread. Losing one entry on a
        // storage failure is preferable to crashing the browser.
        prefs.edit().putString(key, records.toString()).apply()
    }

    private fun cleanTitle(kind: HomeEntryKind, location: String, title: String): String {
        title.trim().takeIf { it.isNotEmpty() }?.let { return it }
        return when (kind) {
            HomeEntryKind.WEB -> runCatching { URI(location).host }.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: location
            else -> Path.parse(location).name ?: location
        }
    }
}
