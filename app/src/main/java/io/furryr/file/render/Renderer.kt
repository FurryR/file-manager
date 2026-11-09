package io.furryr.file.render

import android.content.Context
import androidx.compose.ui.graphics.vector.ImageVector
import io.furryr.file.core.ContentInfo
import io.furryr.file.core.VFile
import io.furryr.file.core.ViewRef
import io.furryr.file.core.VNode
import kotlinx.coroutines.CoroutineScope

/**
 * One item in the tab overflow menu. Renderers return only items that are
 * currently actionable, so the menu never shows a disabled placeholder.
 */
data class RendererMenuItem(
    val title: String,
    val icon: ImageVector? = null,
    val onClick: () -> Unit,
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
 * What a tab is asking a renderer to show. Renderers receive node objects or
 * an explicit URL; they never receive a provider path or a synthetic scheme.
 */
sealed interface RenderTarget {
    data class Node(val node: VNode, val content: ContentInfo? = null) : RenderTarget
    data class Url(val url: String) : RenderTarget
}

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
    fun confirmLeave(message: String?, onLeave: () -> Unit, onCancel: () -> Unit = {}) {
        onLeave()
    }

    /**
     * Ask the tab to open [file] through the shared opener/renderer pipeline.
     * A web page uses this for links whose response is not HTML, so archive,
     * external and document handling stay in one place.
     */
    fun openFile(file: VFile, content: ContentInfo? = null) {}
}

/** One live renderer instance, owned by a tab entry. */
interface RendererSession : AutoCloseable {
    val capabilities: RenderCapabilities

    /** Return true when the renderer consumed the back gesture. */
    fun onBack(): Boolean = false

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

/** Optional helper shared by renderers that need a stable node identity. */
fun VNode.viewRef(): ViewRef = ref()
