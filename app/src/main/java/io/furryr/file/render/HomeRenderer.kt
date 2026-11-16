package io.furryr.file.render

import io.furryr.file.model.PageKind

class HomeRenderer(
    initialState: HomePageState? = null,
    private val homeTitle: String = "",
) : RendererBase<HomePageState>(initialState) {
    val state: HomePageState get() = homeSession.state
    override val pagePath: String = "home://"
    override val pageKind: PageKind = PageKind.LOCATION

    private val homeSession: HomeRendererSession get() = session as HomeRendererSession

    override fun createSession(state: HomePageState?): PageSession<HomePageState> =
        HomeRendererSession(state, homeTitle)

    override suspend fun enter(host: RendererContext) {
        (session as HomeRendererSession).prepareEnter()
        host.commit { h, dark, modifier ->
            HomeSessionView(session as HomeRendererSession).Content(h, dark, modifier)
        }
    }
}
