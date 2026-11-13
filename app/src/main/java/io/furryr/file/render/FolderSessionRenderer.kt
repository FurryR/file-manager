package io.furryr.file.render

import io.furryr.file.clipboard.ClipboardStore
import io.furryr.file.model.PageKind

class FolderSessionRenderer(
    private val initialSelection: Set<String> = emptySet(),
    private val initialSelectedIndex: Int = -1,
    initialState: FolderPageState? = null,
) : RendererBase<FolderPageState>(initialState) {
    override val state: FolderPageState = seedState ?: FolderPageState("")
    override val pagePath: String get() = state.path
    override val pageKind: PageKind = PageKind.LOCATION

    override fun createSession(state: FolderPageState?): PageSession<FolderPageState> =
        FolderRendererSession(initialSelection, initialSelectedIndex, state)

    override suspend fun enter(host: RendererContext) {
        val folder = session as FolderRendererSession
        // A filter and its row animation belong to the frame that showed them.
        // Reset both before the new body commits, so no stale overlay or
        // entrance animation survives the transition.
        folder.prepareEnter()
        folder.listing.lockSelection(ClipboardStore.state.inTransfer)
        host.commit { h, dark, modifier ->
            FolderSessionView(session as FolderRendererSession).Content(h, dark, modifier)
        }
    }
}
