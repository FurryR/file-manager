package io.furryr.file.render
import io.furryr.file.model.PageKind

class WebSessionRenderer(
    private val document: HtmlDocument,
    initialState: WebPageState? = null,
) : RendererBase<WebPageState>(initialState) {
    override val state: WebPageState = seedState ?: WebPageState(
        address = document.remoteBase ?: document.origin.let(NodeAddress::format),
        title = null,
    )
    override val pagePath: String get() = state.address
    override val pageKind: PageKind = PageKind.PAGE

    override fun createSession(state: WebPageState?): PageSession<WebPageState> =
        WebRendererSession(document, state)

    override suspend fun enter(host: RendererContext) {
        host.commit { h, dark, modifier ->
            WebSessionView(session as WebRendererSession).Content(h, dark, modifier)
        }
    }
}
