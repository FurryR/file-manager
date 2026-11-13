package io.furryr.file.render
import io.furryr.file.model.PageKind

import io.furryr.file.core.VFile
import io.furryr.file.editor.EditorLanguages
import io.furryr.file.editor.EditorPageState
import io.furryr.file.editor.EditorRendererSession
import io.furryr.file.editor.EditorSessionView

class EditorSessionRenderer(
    private val file: VFile,
    private val pageAddress: String,
    initialState: EditorPageState? = null,
) : RendererBase<EditorPageState>(initialState) {
    override val state: EditorPageState =
        seedState ?: EditorPageState(EditorLanguages.id(file.name))
    override val pagePath: String get() = pageAddress
    override val pageKind: PageKind = PageKind.FILE

    override fun createSession(state: EditorPageState?): PageSession<EditorPageState> =
        EditorRendererSession(file, pageAddress, state)

    override suspend fun enter(host: RendererContext) {
        host.commit { h, dark, modifier ->
            EditorSessionView(session as EditorRendererSession).Content(h, dark, modifier)
        }
    }
}
