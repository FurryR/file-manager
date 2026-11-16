package io.furryr.file.home

/**
 * Renderer-facing bookmark manager.
 *
 * Bookmark reads and writes belong to the location, so the renderer that owns
 * the location builds its [HomeEntry] and calls this object directly. The
 * shell's RendererContext only supplies Home navigation, never bookmark data.
 */
object Bookmarks {
    fun isBookmarked(entry: HomeEntry): Boolean =
        HomeStore.isBookmarked(entry.kind, entry.location)

    /** Add or remove [entry]; returns true when it is now bookmarked. */
    fun toggle(entry: HomeEntry): Boolean = HomeStore.toggleBookmark(entry)

    fun remove(entry: HomeEntry) {
        HomeStore.removeBookmark(entry.key)
    }

    fun removeRecent(entry: HomeEntry) {
        HomeStore.removeRecent(entry.key)
    }

    fun clearRecent() {
        HomeStore.clearRecent()
    }

    fun clearBookmarks() {
        HomeStore.clearBookmarks()
    }
}
