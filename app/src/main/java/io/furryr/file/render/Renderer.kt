package io.furryr.file.render

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import io.furryr.file.core.ContentInfo
import io.furryr.file.core.VFile
import io.furryr.file.core.VNode
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
     */
    fun onPageVisited(address: String) {}
    fun updateCapabilities(capabilities: RenderCapabilities)
    fun reportError(error: Throwable)
    fun requestClose()

    /** App-level actions a renderer menu may expose. */
    fun refresh() {}
    fun toggleFilter() {}
    fun openSort() {}

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
}

/** One live renderer instance, owned by a tab entry. */
interface RendererSession : AutoCloseable {
    val capabilities: RenderCapabilities

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

    /** Top-bar path content; null keeps the shell's directory fallback. */
    fun pathBar(context: Context): RendererPath? = null

    suspend fun save(): Result<Unit> = Result.success(Unit)
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
