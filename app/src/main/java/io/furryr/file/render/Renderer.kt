package io.furryr.file.render

import io.furryr.file.core.ContentInfo
import io.furryr.file.core.ViewRef
import io.furryr.file.core.VNode
import kotlinx.coroutines.CoroutineScope

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

/**
 * An opener turns a file into a different navigable node (archive -> nested
 * folder, package -> ...). It is not a renderer: it does not draw anything and
 * does not own history.
 */
interface Opener {
    val id: String
    fun supports(node: VNode, content: ContentInfo): Boolean
    suspend fun open(node: VNode): Result<VNode>
}

object Openers {
    private val entries = mutableListOf<Pair<Int, Opener>>()

    @Synchronized
    fun register(priority: Int, opener: Opener) {
        entries.removeAll { it.second.id == opener.id }
        entries += priority to opener
        entries.sortByDescending { it.first }
    }

    @Synchronized
    fun candidates(node: VNode, content: ContentInfo): List<Opener> =
        entries.asSequence()
            .map { it.second }
            .filter { it.supports(node, content) }
            .toList()

    fun resolve(node: VNode, content: ContentInfo): Opener? =
        candidates(node, content).firstOrNull()
}

/** Optional helper shared by renderers that need a stable node identity. */
fun VNode.viewRef(): ViewRef = ref()
