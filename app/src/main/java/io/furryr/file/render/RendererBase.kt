package io.furryr.file.render

import android.content.Context
import io.furryr.file.clipboard.ClipboardSink
import io.furryr.file.core.DirectoryStats
import io.furryr.file.core.VFolder
import io.furryr.file.core.model.FileEntry
import io.furryr.file.model.PageKind

/**
 * Base for every session renderer. The optional state is the only durable
 * input; the concrete renderer builds its private session from it and binds
 * the view lazily on first render.
 */
abstract class RendererBase<S : PageState>(
    initialState: S? = null,
) : RendererSession {
    protected abstract fun createSession(state: S?): PageSession<S>

    /** Address and role this renderer contributes to its Page. */
    abstract val pagePath: String
    abstract val pageKind: PageKind

    private val sessionDelegate = lazy { createSession(initialState) }
    protected val session: PageSession<S> get() = sessionDelegate.value

    /** Directory operations are exposed through [RenderCapabilities.select]. */
    /**
     * Make this page current. On the first show it loads and commits the
     * pending page; on every later show it only rebuilds what the state says
     * is missing. Called on each activation, so it must be idempotent.
     * Cancellation aborts the commit and leaves the outgoing page untouched.
     */
    abstract suspend fun enter(host: RendererContext)

    override val capabilities: RenderCapabilities get() = session.capabilities
    override val isLocationRenderer: Boolean get() = session.isLocationRenderer
    override val address: String? get() = session.address
    override val webAddress: String? get() = session.webAddress
    override val title: String? get() = session.title
    override val locationInfo: DirectoryStats get() = session.locationInfo
    override fun titleSlot(): TitleSlot? = session.titleSlot()
    override val isLive: Boolean get() = session.isLive
    override val suppressBookmark: Boolean get() = session.suppressBookmark
    override val needsUnloadGuard: Boolean get() = session.needsUnloadGuard
    override fun adopt(address: String, next: RendererSession): Boolean =
        session.adopt(address, next)
    override fun retrace(address: String): Boolean = session.retrace(address)
    override fun beginConfirmation() = session.beginConfirmation()
    override fun settleConfirmation() = session.settleConfirmation()
    override fun matchesLocation(path: String): Boolean = session.matchesLocation(path)
    override val consumesEdgeGestures: Boolean get() = session.consumesEdgeGestures
    override suspend fun requestUnload(context: RendererContext): Boolean =
        session.requestUnload(context)
    override fun menuItems(context: Context): List<RendererMenuItem> = session.menuItems(context)
    override val clipboardSink: ClipboardSink? get() = session.clipboardSink
    override fun onRelease() = session.onRelease()
    override fun pathBar(context: Context): RendererPath? = session.pathBar(context)
    override suspend fun save(): Result<Unit> = session.save()
    override fun blur() = session.blur()

    /** Drop views; the private session keeps its model state. */
    override fun reset() {
        if (sessionDelegate.isInitialized()) session.reset()
    }

    /** Final close; the renderer is not reusable afterwards. */
    override fun close() {
        if (sessionDelegate.isInitialized()) session.close()
    }
}
