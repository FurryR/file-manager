package io.furryr.file.render

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.furryr.file.home.HomeEntry
import io.furryr.file.isHomePath
import io.furryr.file.ui.screens.HomeScreen

/**
 * Everything the Home screen draws. The shell still owns the HomeStore, but
 * the session owns the projection just like every other renderer.
 */
data class HomeRendererState(
    val recent: List<HomeEntry> = emptyList(),
    val bookmarks: List<HomeEntry> = emptyList(),
    val opening: Boolean = false,
)

/** Home has no durable data beyond the tab itself. */
object HomePageState : PageState()

/**
 * Home as a normal renderer. It has no loading, refresh or history of its own:
 * the tab owns Back/Forward like it does for a page, and [HomeScreen] only
 * draws the projected state.
 */
class HomeRendererSession(
    initialState: HomePageState? = null,
    title: String = "",
) : PageSession<HomePageState>(initialState) {
    private var host: RendererContext = DetachedRendererContext
    override val state: HomePageState = seedState ?: HomePageState
    override val capabilities = RenderCapabilities()

    override val isLocationRenderer: Boolean get() = true

    private var homeTitle: String = title

    override val title: String? get() = homeTitle.takeIf { it.isNotBlank() }

    internal var filterText by mutableStateOf("")
    private var filterOpen by mutableStateOf(false)

    private fun setFilter(text: String) {
        filterText = text
    }

    private fun toggleFilter() {
        filterOpen = !filterOpen
        if (!filterOpen) filterText = ""
    }

    private fun closeFilter() {
        filterOpen = false
        filterText = ""
    }

    /** Drop the filter overlay before the home page commits. */
    internal fun prepareEnter() {
        closeFilter()
    }

    override fun titleSlot(): TitleSlot? {
        if (!filterOpen) return null
        return { modifier ->
            FilterTitle(
                text = filterText,
                onTextChange = ::setFilter,
                onClose = ::closeFilter,
                modifier = modifier,
            )
        }
    }

    override fun matchesLocation(path: String): Boolean = isHomePath(path)

    internal fun useHost(host: RendererContext) {
        this.host = host
    }

    override fun pathBar(context: Context): RendererPath =
        HomeRenderer.pathBar(context).also { homeTitle = it.title }

    override fun menuItems(context: Context): List<RendererMenuItem> =
        HomeRenderer.menuItems(context, ::toggleFilter)

    override fun close() = Unit

    override fun reset() {
        host = DetachedRendererContext
    }
}

/** View half of the Home session. */
internal class HomeSessionView(
    private val session: HomeRendererSession,
) : SessionView {
    @Composable
    override fun Content(host: RendererContext, darkTheme: Boolean, modifier: Modifier) {
        session.useHost(host)
        val state = host.homeView() ?: return
        HomeScreen(
            recent = state.recent,
            bookmarks = state.bookmarks,
            onOpen = host::openHome,
            onToggleBookmark = host::toggleBookmark,
            onRemoveRecent = { entry -> host.removeRecent(entry.key) },
            onRemoveBookmark = { entry -> host.removeBookmark(entry.key) },
            onClearRecent = host::clearRecent,
            onClearBookmarks = host::clearBookmarks,
            opening = state.opening,
            onCancelOpening = host::cancelOpening,
            filter = session.filterText,
            modifier = modifier,
        )
    }
}
