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
    val path: String,
    /** The view behind a folder page, when it is not a plain provider path. */
    val folder: VFolder? = null,
    val kind: PageKind = PageKind.LOCATION,
    title: String? = null,
    pathInfo: io.furryr.file.render.RendererPath? = null,
) {
    /** Last title a renderer reported; kept after the session is gone. */
    var title: String? by mutableStateOf(title)

    /** Last path-bar projection reported by the renderer. */
    var pathInfo: io.furryr.file.render.RendererPath? by mutableStateOf(pathInfo)

    /** True while this page's renderer is being rebuilt. */
    var restoring: Boolean = false

    /** The renderer that owns this page's session; null until first opened. */
    var renderer: io.furryr.file.render.RendererBase<*>? = null

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

    /** Copy the address fields, keeping the identity and leaving the renderer. */
    fun copy(
        path: String = this.path,
        folder: VFolder? = this.folder,
        kind: PageKind = this.kind,
        title: String? = this.title,
    ): Page = Page(id, path, folder, kind, title)
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
    /** Non-null while this tab is inside a container (zip, ...). */
    val folder: VFolder? = null,
    /**
     * Every page of this tab, oldest first. The current one is at
     * [pageIndex]; Back and Forward only move that index.
     */
    val pages: List<Page> = listOf(Page(path = path)),
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
            ?: Page(id = "tab-$id", path = path)

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
            // folder it was opened from underneath.
            path = if (page.kind == PageKind.LOCATION) page.path else path,
            folder = if (page.kind == PageKind.LOCATION) page.folder else folder,
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
            path = if (page.kind == PageKind.LOCATION) page.path else path,
            folder = if (page.kind == PageKind.LOCATION) page.folder else folder,
        )
    }

    /** Make page [index] current, deriving the tab's location fields. */
    fun movedTo(index: Int): FileTab {
        if (pages.isEmpty()) return this
        val targetIndex = index.coerceIn(0, pages.lastIndex)
        val target = pages[targetIndex]
        val base = if (target.kind == PageKind.LOCATION) target
        else pages.subList(0, targetIndex).lastOrNull { it.kind == PageKind.LOCATION }
        return copy(
            pageIndex = targetIndex,
            path = base?.path ?: path,
            folder = when {
                target.kind == PageKind.LOCATION -> target.folder
                base != null -> base.folder
                else -> folder
            },
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
