package io.furryr.file.render

import android.content.Context
import androidx.compose.runtime.Composable
import io.furryr.file.ui.icons.Icons
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import io.furryr.file.Opener
import io.furryr.file.R
import io.furryr.file.clipboard.ClipboardAction
import io.furryr.file.clipboard.ClipboardEntry
import io.furryr.file.core.DirectoryStats
import io.furryr.file.core.VFile
import io.furryr.file.core.VFolder
import io.furryr.file.core.VNode
import io.furryr.file.core.Vfs
import io.furryr.file.core.model.FileEntry
import io.furryr.file.core.model.ProviderStat
import io.furryr.file.home.Bookmarks
import io.furryr.file.home.HomeEntry
import io.furryr.file.model.PageIcon
import io.furryr.file.terminal.TerminalTab
import io.furryr.file.terminal.TermuxTerminalTab
import kotlinx.coroutines.CoroutineScope

/**
 * One item in the tab overflow menu. A non-empty [children] list turns the
 * item into a submenu; [checked] draws the menu's tick for toggle rows.
 * Renderers normally return only actionable items, but may keep a meaningful
 * action visible and disabled (for example Save in a read-only editor).
 */
data class RendererMenuItem(
    val title: String,
    val icon: ImageVector? = null,
    val enabled: Boolean = true,
    val checked: Boolean? = null,
    val children: List<RendererMenuItem>? = null,
    val onClick: () -> Unit = {},
)

/**
 * Top-bar path content owned by a renderer. The large line is the title, the
 * caption uses the old directory-summary slot, and [editText] is what a tap
 * puts into the inline editor (a page title is not a URL, and Home starts
 * empty so the user can type any location).
 */
data class RendererPath(
    val title: String,
    val caption: String? = null,
    val editText: String = title,
    val editable: Boolean = true,
)

/**
 * A renderer's own top-bar title content. Only the special modes (today the
 * filter field) provide one; null keeps the shell's default path title.
 */
typealias TitleSlot = @Composable (Modifier) -> Unit

/** The one Composable surface a renderer gives the shell at commit time. */
typealias RendererContent = @Composable (RendererContext, Boolean, Modifier) -> Unit

/**
 * Renderer-owned page actions. A null callback means that action is not
 * available here; the shell only projects the ones the page supplies.
 */
data class RenderCapabilities(
    /** Null when this page cannot create here. */
    val create: (() -> Unit)? = null,
    /** Null unless entries can be renamed in place. */
    val rename: ((List<FileEntry>) -> Unit)? = null,
    /** Null unless entries can be deleted in place. */
    val delete: ((List<FileEntry>) -> Unit)? = null,
    /** Null when this page has no folder to go up to. */
    val up: (() -> Unit)? = null,
    /** Null when this page cannot host a terminal. */
    val terminal: TerminalFactory? = null,
    /** Null when this page has no list to select from. */
    val select: SelectCapabilities? = null,
)

/**
 * Which tab owns the page [RendererContext.open] is given. The mode never
 * changes what the page is; it only decides where the stop is installed.
 *
 * Not [io.furryr.file.core.source.OpenMode]: that one says how bytes are
 * opened, this one where a renderer is installed.
 */
enum class OpenMode {
    /** The calling tab: a new stop above the page that is showing. */
    Current,

    /**
     * A tab of its own, activated immediately. The calling tab keeps what it
     * shows, and the new tab starts at the calling tab's location, so Back and
     * Up still have a folder to return to.
     */
    NewTab,
}

/**
 * Renderer-facing view of the shell. The real host is one shell object; this
 * is a page-bound façade. A renderer requests navigation and title updates
 * but must not mutate history or format an address itself.
 *
 * Bookmark reads and writes are deliberately absent: a renderer owns its own
 * location entry ([RendererSession.bookmarkEntry]) and talks to
 * [io.furryr.file.home.Bookmarks] directly. Home lists come straight from
 * [io.furryr.file.home.HomeStore]; this context only carries navigation,
 * page ownership, file actions and Home navigation.
 */
interface RendererContext {
    // ── Navigation and lifecycle ────────────────────────────────────────────

    val scope: CoroutineScope
    fun navigate(node: VNode, rendererId: String? = null)
    fun updateTitle(title: String?)

    /**
     * The live page moved itself to [address]: a link the WebView followed, a
     * redirect, or the page's own back/forward. History belongs to the tab, so
     * the host decides whether this is a new stop, a stop it already made, or
     * a move it should ignore.
     *
     * [replacePrevious] is set when this navigation superseded that address
     * before it ever committed a page (a redirect). The tab drops that
     * provisional stop instead of pushing it into history.
     */
    fun onPageVisited(address: String, replacePrevious: String? = null) {}
    fun reportError(error: Throwable)

    /** Report page load progress; null clears the indicator. */
    fun setProgress(progress: Int?) {}
    fun dismissPending()

    /** The directory view the shell wants this tab to draw, read on demand. */
    fun folderView(): FolderRendererState? = null

    // ── Page ownership ──────────────────────────────────────────────────────

    /** The page this context currently serves; shell callbacks gate on it. */
    fun bindPage(pageId: String?) {}

    /**
     * Bring this page to the front: select its tab, move to it in history and
     * cancel a pending close. A renderer calls it only when its own UI (for
     * example a save-conflict dialog) must become visible.
     */
    fun focus() {}

    /** Cache the renderer's path-bar projection on its page. */
    fun submitPath(path: RendererPath?) {}

    /** Cache the renderer's tab title on its page. */
    fun submitTitle(title: String?) {}

    /** Replace the page icon after loading reported a better image. */
    fun updatePageIcon(icon: PageIcon) {}

    /** Android context for renderers that hand off outside the app. */
    val context: Context? get() = null

    /** The world this page resolves through. Never a process-global router. */
    val vfs: Vfs

    /** Stable id of [vfs]'s world; forwarded URIs carry it. */
    val worldId: String

    /** The one open-with table of this world. Routing never needs a second table. */
    val opener: Opener

    /**
     * Append and activate a page for an already-created renderer. The renderer
     * is created cheaply; its [RendererBase.enter] does the loading behind the
     * pending cover. [pushPage] false replaces the current stop in place; it
     * has no meaning for [OpenMode.NewTab], which always gets a fresh tab.
     */
    fun open(
        renderer: RendererBase<*>,
        mode: OpenMode = OpenMode.Current,
        pushPage: Boolean = true,
    ) {}

    /**
     * Replace the current page with [renderer] instead of appending a stop.
     * Used when a page turns out to be a different format and the tab must
     * stay on the same address.
     */
    fun replace(renderer: RendererBase<*>) {
        open(renderer, pushPage = false)
    }

    /**
     * Show [content] as the one global popup and suspend until it calls
     * `resolve(result)`. This is a one-shot promise: the popup settles once,
     * and returning null means no result (another popup owns the slot, the
     * page is gone, or the caller was cancelled).
     */
    suspend fun <T> popup(
        content: @Composable (resolve: (T) -> Unit) -> Unit,
    ): T? = null

    /**
     * Promote the pending renderer bound to this host into the tab's pages
     * and store [content] for the composition layer to execute. This is the
     * only step that replaces the visible page, its title and its path.
     */
    fun commit(content: RendererContent)

    // ── File actions ────────────────────────────────────────────────────────

    fun openFile(file: VFile, stat: ProviderStat? = null)
    fun copyFile(file: VFile, stat: ProviderStat? = null)
    fun shareFile(file: VFile, stat: ProviderStat? = null)

    /** Open a listed entry, with its node when the listing already has it. */
    fun openEntry(entry: FileEntry, node: VNode?) {}
    fun requestShare(entries: List<ClipboardEntry>) {}
    fun requestProperties(entry: FileEntry) {}
    fun putOnClipboard(entries: List<ClipboardEntry>, action: ClipboardAction) {}
    fun onSelectionChanged(paths: Set<String>, lastIndex: Int, entries: List<ClipboardEntry>) {}
    fun onFolderLoaded(path: String) {}
    fun onFolderCancelled() {}
    fun onScrollConsumed() {}
    fun onHighlightConsumed() {}
    fun onFileDragStart() {}
    fun onFileDragEnd() {}

    // ── Home ────────────────────────────────────────────────────────────────

    fun cancelOpening() {}
}

/** Creates the body of a new terminal tab; null when the page cannot host one. */
fun interface TerminalFactory {
    fun create(context: Context): TerminalTab?
}

/** One live session instance, owned privately by a [RendererBase]. */
abstract class PageSession<S : PageState>(
    initialState: S? = null,
) : RendererSession {
    /** The optional state supplied by the renderer, or null for a new page. */
    protected val seedState: S? = initialState
}

interface RendererSession : AutoCloseable {
    /** Callback handles the shared toolbar projects; null means unavailable. */
    val capabilities: RenderCapabilities get() = RenderCapabilities()

    /** A directory/Home session is the tab location itself, not an overlay. */
    val isLocationRenderer: Boolean get() = false

    /** The page stopped being current; the session decides what to keep. */
    fun blur() {}

    /**
     * Drop views, host references and view-bound jobs; keep model state.
     * The next render rebuilds the view. Idempotent.
     */
    fun reset() {}

    /**
     * Address this renderer represents as a tab location, when it is a stop
     * of its own. A directory or Home session reports null.
     */
    val address: String? get() = null

    /** Page URL while this renderer is a web page; null for every other kind. */
    val webAddress: String? get() = null

    /** Last title this renderer reported for [address]. */
    val title: String? get() = null

    /** Directory summary this renderer's location reports, when it has one. */
    val locationInfo: DirectoryStats get() = DirectoryStats()

    /**
     * The renderer's own top-bar title, or null for the shell's generic path
     * fallback. A filter field or another title mode lives here, never in the
     * shell.
     */
    fun titleSlot(): TitleSlot? = null

    /** True when [address] is a live/remote resource rather than a stored file. */
    val isLive: Boolean get() = false

    /** The location itself is not offered as a Home bookmark. */
    val suppressBookmark: Boolean get() = false

    /**
     * The Home bookmark this session's current location represents. The
     * renderer owns the entry; bookmark reads and edits go through
     * [io.furryr.file.home.Bookmarks], never through the shell context.
     */
    fun bookmarkEntry(): HomeEntry? = null

    /** Navigation must ask this renderer before it lets go. */
    val needsUnloadGuard: Boolean get() = false

    /**
     * Reuse this live session for a new open of the same kind. [next] is the
     * freshly created session; the receiver closes it when it takes over.
     */
    fun adopt(address: String, next: RendererSession): Boolean = false

    /** Move to [address] when it is already in this renderer's own cache. */
    fun retrace(address: String): Boolean = false

    /** Hold the content cover while [address] is confirmed to be a page. */
    fun beginConfirmation() {}

    /** Confirmed as a page: the content cover may get out of the way. */
    fun settleConfirmation() {}

    /** True when this renderer is the right view for [path] without a rebuild. */
    fun matchesLocation(path: String): Boolean = false

    /** The content consumes horizontal gestures; the drawer must not. */
    val consumesEdgeGestures: Boolean get() = false

    /**
     * Ask this page to close. The renderer shows any prompt it needs and
     * returns true when the shell may release it, false to keep the page.
     */
    suspend fun requestUnload(context: RendererContext): Boolean = true

    /** Menu items this renderer contributes while it owns the tab. */
    fun menuItems(context: Context): List<RendererMenuItem> = emptyList()

    /**
     * Clipboard receiver. A renderer that returns non-null can be a paste
     * target; the shell still owns the payload and the transfer.
     */
    val clipboardSink: io.furryr.file.clipboard.ClipboardSink? get() = null

    /**
     * Called exactly once before [close], when the tab is about to drop this
     * renderer (close, location replacement or activity dispose). Renderers
     * release listeners and cancel background work here; host callbacks are
     * ignored after this begins.
     */
    fun onRelease() {}

    /** Top-bar path content; null keeps the shell's directory fallback. */
    fun pathBar(context: Context): RendererPath? = null

    suspend fun save(): Result<Unit> = Result.success(Unit)
}

/**
 * The one bookmark row a renderer appends to its own menu. The renderer
 * supplies the entry from its own session; persistence is [Bookmarks].
 */
fun bookmarkMenuItem(context: Context, entry: HomeEntry?): RendererMenuItem? {
    entry ?: return null
    val bookmarked = Bookmarks.isBookmarked(entry)
    return RendererMenuItem(
        title = context.getString(
            if (bookmarked) R.string.bookmark_remove else R.string.bookmark_add,
        ),
        icon = if (bookmarked) Icons.Default.Star else Icons.Default.StarBorder,
        onClick = { Bookmarks.toggle(entry) },
    )
}

/**
 * The view half of a page: how its private session is drawn. RendererBase is
 * the only caller; a session never exposes this through the shell contract.
 */
interface SessionView {
    @Composable
    fun Content(host: RendererContext, darkTheme: Boolean, modifier: Modifier = Modifier)
}

/**
 * The selection/listing operations a renderer offers through
 * [RenderCapabilities.select]. The shell never sees the session that backs it.
 */
interface SelectCapabilities {
    val entries: List<FileEntry>
    val selectedEntries: List<FileEntry>
    val selectedPaths: Set<String>
    fun selectAll()
    fun invertSelection()
    fun selectSameType()
    fun setSelection(paths: Set<String>, index: Int)
    fun clearSelection()
    fun clearDisplayedSelection()
    fun refreshListing(
        animate: Boolean = true,
        highlightPath: String? = null,
        highlightDirectory: String? = null,
    )
}
