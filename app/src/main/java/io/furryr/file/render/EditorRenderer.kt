package io.furryr.file.render
import io.furryr.file.model.PageKind

import io.furryr.file.core.VFile
import io.furryr.file.core.Vfs
import io.furryr.file.editor.EditorLanguages
import io.furryr.file.editor.EditorPageState
import io.furryr.file.editor.EditorRendererSession
import io.furryr.file.editor.EditorSessionView

class EditorRenderer(
    private val vfs: Vfs,
    private val file: VFile,
    private val pageAddress: String,
    initialState: EditorPageState? = null,
) : RendererBase<EditorPageState>(initialState) {
    val state: EditorPageState get() = editorSession.state
    override val pagePath: String get() = pageAddress
    override val pageKind: PageKind = PageKind.FILE

    private val editorSession: EditorRendererSession get() = session as EditorRendererSession

    override fun createSession(state: EditorPageState?): PageSession<EditorPageState> =
        EditorRendererSession(vfs, file, pageAddress, state)

    override suspend fun enter(host: RendererContext) {
        val editor = session as EditorRendererSession
        // The factory returns a cheap renderer; the actual file read happens
        // behind the pending cover, so a slow remote open reaches the 300 ms
        // spinner instead of freezing the outgoing page.
        if (editor.loading) editor.load()
        host.commit { h, dark, modifier ->
            EditorSessionView(editor).Content(h, dark, modifier)
        }
    }
}
