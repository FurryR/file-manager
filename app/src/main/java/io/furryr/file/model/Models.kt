package io.furryr.file.model
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.furryr.file.R
import io.furryr.file.isHomePath
import io.furryr.file.ui.util.BrowseAddress
import io.furryr.file.ui.util.formatSize
import io.furryr.file.core.DirectoryStats
import io.furryr.file.core.Path
import io.furryr.file.core.VFolder

/** What a page points at. */
enum class PageKind {
    /** A directory or Home: the tab's own location. */
    LOCATION,

    /** A document drawn over its folder (the editor). */
    FILE,

    /** A page drawn over its folder (a live URL or a stored HTML file). */
    PAGE,
}

/**
 * One page of a tab: an address plus the renderer that owns its live session.
 *
 * The page is the stable identity; the renderer (and the session inside it)
 * may survive tab switches. A new Activity resets the renderer's session to
 * drop views, and the next render rebuilds them.
 */
class Page(
    val id: String = java.util.UUID.randomUUID().toString(),
    /** Vfs address or page URL this page shows. */
    path: String,
    /** Icon kind only; never a navigation decision. */
    kind: PageKind = PageKind.LOCATION,
    /** Structural stop: the tab's location, not an overlay page. */
    location: Boolean = false,
    title: String? = null,
    pathInfo: io.furryr.file.render.RendererPath? = null,
) {
    /**
     * Address fields are mutable so a page keeps its identity when the page it
     * shows moves (a redirect, a same-document URL rewrite, a stop that turns
     * out to be a folder). Rebuilding the Page would drop its renderer.
     */
    var path: String by mutableStateOf(path)

    var kind: PageKind by mutableStateOf(kind)

    var location: Boolean by mutableStateOf(location)

    /** Last title a renderer reported; kept after the session is gone. */
    var title: String? by mutableStateOf(title)

    /** Last path-bar projection reported by the renderer. */
    var pathInfo: io.furryr.file.render.RendererPath? by mutableStateOf(pathInfo)

    /** True while this page's renderer is being rebuilt. */
    var restoring: Boolean = false

    /** The renderer that owns this page's session; null until first opened. */
    var renderer: io.furryr.file.render.RendererBase<*>? by mutableStateOf(null)

    /** Drop views; the next render rebuilds them from the session. */
    fun reset() {
        renderer?.reset()
    }

    /** Final close: release the renderer and its session. */
    fun close() {
        val current = renderer ?: return
        renderer = null
        runCatching { current.onRelease() }
        current.close()
    }
}

/** Newest pages a tab keeps; the current location always stays in the list. */
private const val MAX_PAGES = 64

data class FileTab(
    /**
     * Stable identity. Tab order changes when a tab is closed, so renderers
     * and pages are keyed by this value instead of an index.
     */
    val id: String = java.util.UUID.randomUUID().toString(),
    val path: String,
    /**
     * Every page of this tab, oldest first. The current one is at
     * [pageIndex]; Back and Forward only move that index.
     */
    val pages: List<Page> = listOf(Page(path = path, location = true)),
    val pageIndex: Int = 0,
    val selectedPaths: Set<String> = emptySet(),
    val lastSelectedIndex: Int = -1,
) {
    /** The tab label: always the title the current page's renderer cached. */
    val title: String
        get() = currentPage.title.orEmpty()

    /** The page this tab is showing. Pages are never empty in practice. */
    val currentPage: Page
        get() = pages.getOrNull(pageIndex)
            ?: Page(id = "tab-$id", path = path, location = true)

    val canGoBack: Boolean get() = pageIndex > 0
    val canGoForward: Boolean get() = pageIndex < pages.lastIndex

    /** Truncate the forward pages and make [page] the current one. */
    fun appended(page: Page): FileTab {
        val at = if (pages.isEmpty()) -1 else pageIndex.coerceIn(0, pages.lastIndex)
        val kept = if (at >= 0) pages.take(at + 1) else emptyList()
        var next = kept + page
        var index = next.lastIndex
        if (next.size > MAX_PAGES) {
            val drop = next.size - MAX_PAGES
            next = next.drop(drop)
            index -= drop
        }
        return copy(
            pages = next,
            pageIndex = index,
            // A location is the tab's own address; an overlay keeps the
            // location it was opened from underneath.
            path = if (page.location) page.path else path,
            selectedPaths = emptySet(),
            lastSelectedIndex = -1,
        )
    }

    /** Update the current page in place (title, kind, redirect target). */
    fun replacedCurrent(page: Page): FileTab {
        if (pages.isEmpty()) return appended(page)
        val index = pageIndex.coerceIn(0, pages.lastIndex)
        val next = pages.toMutableList()
        next[index] = page
        return copy(
            pages = next,
            path = if (page.location) page.path else path,
        )
    }

    /** Make page [index] current, deriving the tab's location fields. */
    fun movedTo(index: Int): FileTab {
        if (pages.isEmpty()) return this
        val targetIndex = index.coerceIn(0, pages.lastIndex)
        val target = pages[targetIndex]
        val base = if (target.location) target
        else pages.subList(0, targetIndex).lastOrNull { it.location }
        return copy(
            pageIndex = targetIndex,
            path = base?.path ?: path,
            selectedPaths = emptySet(),
            lastSelectedIndex = -1,
        )
    }
}

// Only the volume usage is a useful directory summary; folder/file counts are
// already visible in the listing. Matches the drawer: used = total - usable.
fun DirectoryStats.summary(context: Context): String =
    if (totalBytes > 0) context.getString(
        R.string.directory_summary,
        formatSize(totalBytes - usableBytes),
        formatSize(totalBytes),
    ) else ""

/**
 * One pending transfer is now a clipboard payload (`:app` clipboard package);
 * the source is always a [VNode] and the destination is another node
 * (`VFolder.destination`) handed to `NodeOps.copyTo`.
 */
data class OperationProgress(
    val totalBytes: Long,
    val copiedBytes: Long,
    val currentName: String,
    val isCopy: Boolean,
    val speedBytesPerSec: Long = 0L
)
