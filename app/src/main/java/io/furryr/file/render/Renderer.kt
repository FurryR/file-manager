package io.furryr.file.render

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import io.furryr.file.R
import io.furryr.file.clipboard.ClipboardAction
import io.furryr.file.clipboard.ClipboardEntry
import io.furryr.file.core.ContentInfo
import io.furryr.file.core.DirectoryStats
import io.furryr.file.core.VFile
import io.furryr.file.core.VFolder
import io.furryr.file.core.VNode
import io.furryr.file.core.model.FileEntry
import io.furryr.file.home.HomeEntry
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

/**
 * What a tab is asking a renderer to show: a node, plus its facts when the
 * caller already described it.
 *
 * A page address is a node like any other — the provider that answered
 * `Vfs.node` decided what it is — so renderers never receive an address string
 * or a synthetic scheme.
 */
data class RenderTarget(
    val node: VNode,
    val content: ContentInfo? = null,
)

/** Capabilities surfaced to the shared toolbar. */
data class RenderCapabilities(
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val canGoParent: Boolean = false,
    val canRefresh: Boolean = false,
    val canSave: Boolean = false,
    val canUndo: Boolean = false,
    val canEdit: Boolean = false,
    /** Directory-only actions; a web/document view must keep these false. */
    val canCreate: Boolean = false,
    val canTerminal: Boolean = false,
)

/**
 * Host services owned by the tab/navigator. A renderer may request navigation
 * and title/capability updates but must not mutate history or format an
 * address itself.
 */
interface RenderHost {
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
    fun updateCapabilities(capabilities: RenderCapabilities)
    fun reportError(error: Throwable)
    fun requestClose()

    /** App-level actions a renderer menu may expose. */
    fun refresh() {}

    /**
     * Unified leave hook: a renderer that holds unsaved state asks the tab to
     * confirm before navigating away. The tab shows one dialog for every
     * renderer; [onLeave] runs only after confirmation, [onCancel] when the
     * user decides to stay. The default proceeds without a prompt.
     */
    fun confirmLeave(
        message: String?,
        canSave: Boolean = false,
        onSave: (suspend () -> Result<Unit>)? = null,
        onLeave: () -> Unit,
        onCancel: () -> Unit = {},
    ) {
        onLeave()
    }

    /**
     * Ask the tab to open [file] through the shared opener/renderer pipeline.
     * The browser renderer uses this for a link hit: the route table decides
     * whether the target is a page, an archive or a file, so a page can never
     * bypass the same pipeline an address-bar open takes.
     */
    fun openFile(file: VFile, content: ContentInfo? = null) {}

    /**
     * Start the tab's copy flow with [file] as the source. The user picks the
     * destination exactly like copying a listed file; the node is kept, not a
     * path, because a page link has no Vfs address.
     */
    fun copyFile(file: VFile, content: ContentInfo? = null) {}

    /** Share [file] itself (an on-demand stream), not its address. */
    fun shareFile(file: VFile, content: ContentInfo? = null) {}

    // ── Directory content ────────────────────────────────────────────────

    /** Open a listed entry, with its node when the listing already has it. */
    fun openEntry(entry: FileEntry, node: VNode?) {}
    fun requestShare(entries: List<FileEntry>) {}
    fun requestDelete(entries: List<FileEntry>) {}
    fun requestRename(entries: List<FileEntry>) {}
    fun requestProperties(entry: FileEntry) {}
    fun putOnClipboard(entries: List<ClipboardEntry>, action: ClipboardAction) {}
    fun onSelectionChanged(paths: Set<String>, lastIndex: Int, entries: List<ClipboardEntry>) {}
    fun onFolderLoaded(path: String) {}
    fun onFolderCancelled() {}
    fun onScrollConsumed() {}
    fun onHighlightConsumed() {}
    fun onFileDragStart() {}
    fun onFileDragEnd() {}

    /** The directory view the shell wants this tab to draw, read on demand. */
    fun folderView(): FolderRendererState? = null

    /** The Home projection the shell wants this tab to draw, read on demand. */
    fun homeView(): HomeRendererState? = null

    // ── Home content ─────────────────────────────────────────────────────

    fun openHome(entry: HomeEntry) {}
    fun toggleBookmark(entry: HomeEntry) {}
    fun removeRecent(key: String) {}
    fun removeBookmark(key: String) {}
    fun clearRecent() {}
    fun clearBookmarks() {}
    fun cancelOpening() {}

    /** Address this tab would bookmark, or null when it is not bookmarkable. */
    fun bookmarkEntry(): HomeEntry? = null
    fun isBookmarked(entry: HomeEntry): Boolean = false
}

/**
 * How long a renderer may outlive the history stop it was shown at.
 *
 * - [NONE]: release as soon as the stop is left (a WebView, an editor).
 * - [WARM]: keep the state but stop background work (a directory, Home).
 * - [LIVE]: must keep running even while inactive (unfinished work).
 */
enum class Retention { NONE, WARM, LIVE }

/** One live renderer instance, owned by a history stop. */
interface RendererSession : AutoCloseable {
    val capabilities: RenderCapabilities

    /** A directory/Home session is the tab location itself, not an overlay. */
    val isLocationRenderer: Boolean get() = false

    /** How this session survives leaving its stop. */
    val retention: Retention get() = Retention.NONE

    /** Rough memory budget used when evicting warm sessions. */
    val cacheCost: Int get() = 1

    /** The stop became current again. */
    fun onActivate() {}

    /** The stop stopped being current; release is a separate step. */
    fun onDeactivate() {}

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

    /** A renderer drawn over the tab location: Back dismisses it first. */
    val isOverlay: Boolean get() = false

    /** Folder that owns [address], for Up and editor terminals. */
    val parent: VFolder? get() = null

    /** Directory a terminal should start in, when it differs from the tab. */
    val terminalPath: String? get() = null

    /** The location itself is not offered as a Home bookmark. */
    val suppressBookmark: Boolean get() = false

    /** Navigation must ask this renderer before it lets go. */
    val needsLeaveGuard: Boolean get() = false

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

    /** True while the renderer is still loading its document/page. */
    val loading: Boolean get() = false

    /** Load progress in the 0..100 range; only meaningful while [loading]. */
    val progress: Int get() = 0

    /** The content consumes horizontal gestures; the drawer must not. */
    val consumesEdgeGestures: Boolean get() = false

    /** Back dismisses this renderer as an overlay before tab history moves. */
    val canDismiss: Boolean get() = false

    /** The renderer is showing its own modal correction/confirmation UI. */
    val modalActive: Boolean get() = false

    /** Draw this renderer's tab content. */
    @Composable
    fun Content(darkTheme: Boolean, modifier: Modifier = Modifier)

    /**
     * Refresh the view. Returns false when this renderer has no refresh action,
     * so the tab falls back to its directory refresh.
     */
    fun refresh(): Boolean = false

    /** Return false to veto tab close, e.g. unsaved editor content. */
    fun canClose(): Boolean = true

    /**
     * Close through the renderer's own leave hook. A renderer that does not
     * register one still closes: the tab never depends on every renderer
     * implementing the hook.
     */
    fun requestClose(action: () -> Unit) {
        close()
        action()
    }

    /** Menu items this renderer contributes while it owns the tab. */
    fun menuItems(context: Context): List<RendererMenuItem> = emptyList()

    /**
     * The renderer's own creation UI for the fixed "+" slot. The shell only
     * projects the action and never opens a dialog on the renderer's behalf.
     */
    fun requestCreate() {}

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
 * The one bookmark row a renderer appends to its own menu. Persistence lives
 * in the host; whether the row exists and what it says is the renderer's view
 * of its own location.
 */
fun RenderHost.bookmarkMenuItem(context: Context): RendererMenuItem? {
    val entry = bookmarkEntry() ?: return null
    val bookmarked = isBookmarked(entry)
    return RendererMenuItem(
        title = context.getString(
            if (bookmarked) R.string.bookmark_remove else R.string.bookmark_add,
        ),
        icon = if (bookmarked) Icons.Default.Star else Icons.Default.StarBorder,
        onClick = { toggleBookmark(entry) },
    )
}

/**
 * Content-selection capability. The shell uses this instead of knowing the
 * concrete directory renderer, exactly like [ClipboardSink] for paste targets.
 */
interface ListingSession : RendererSession {
    val entries: List<FileEntry>
    val selectedEntries: List<FileEntry>
    val selectedPaths: Set<String>
    fun selectAll()
    fun invertSelection()
    fun selectSameType()
    fun setSelection(paths: Set<String>, index: Int)
    fun clearSelection()
    fun clearDisplayedSelection()

    /** Re-list the current directory in this session, bypassing composition. */
    fun refreshListing(
        animate: Boolean = true,
        highlightPath: String? = null,
        highlightDirectory: String? = null,
    )
}

interface RendererFactory {
    val id: String

    /** Cheap check: node kind, content info and capabilities only. */
    fun supports(target: RenderTarget): Boolean

    suspend fun create(
        target: RenderTarget,
        host: RenderHost,
    ): Result<RendererSession>
}

/**
 * Renderer registry, ordered by priority. Explicit user choice is applied by
 * the tab before this fallback order is consulted.
 */
object Renderers {
    private val entries = mutableListOf<Pair<Int, RendererFactory>>()

    @Synchronized
    fun register(priority: Int, factory: RendererFactory) {
        entries.removeAll { it.second.id == factory.id }
        entries += priority to factory
        entries.sortByDescending { it.first }
    }

    @Synchronized
    fun unregister(id: String) {
        entries.removeAll { it.second.id == id }
    }

    @Synchronized
    fun candidates(target: RenderTarget): List<RendererFactory> =
        entries.asSequence()
            .map { it.second }
            .filter { it.supports(target) }
            .toList()

    fun resolve(target: RenderTarget): RendererFactory? = candidates(target).firstOrNull()
}
