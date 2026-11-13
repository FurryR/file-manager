package io.furryr.file.render

import io.furryr.file.model.PageKind

class HomeSessionRenderer(
    initialState: HomePageState? = null,
    private val homeTitle: String = "",
) : RendererBase<HomePageState>(initialState) {
    override val state: HomePageState = seedState ?: HomePageState
    override val pagePath: String = "home://"
    override val pageKind: PageKind = PageKind.LOCATION

    override fun createSession(state: HomePageState?): PageSession<HomePageState> =
        HomeRendererSession(state, homeTitle)

    override suspend fun enter(host: RendererContext) {
        (session as HomeRendererSession).prepareEnter()
        host.commit { h, dark, modifier ->
            HomeSessionView(session as HomeRendererSession).Content(h, dark, modifier)
        }
    }
}
