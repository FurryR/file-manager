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

/**
 * Home as a normal renderer. It has no loading, refresh or history of its own:
 * the tab owns Back/Forward like it does for a page, and [HomeScreen] only
 * draws the projected state.
 */
class HomeRendererSession(
    private val host: RenderHost,
) : RendererSession {
    override val capabilities = RenderCapabilities()

    override val isLocationRenderer: Boolean get() = true
    override val retention: Retention get() = Retention.WARM

    private var filterText by mutableStateOf("")
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

    @Composable
    override fun Content(darkTheme: Boolean, modifier: Modifier) {
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
            filter = filterText,
            modifier = modifier,
        )
    }

    override fun pathBar(context: Context): RendererPath = HomeRenderer.pathBar(context)

    override fun menuItems(context: Context): List<RendererMenuItem> =
        HomeRenderer.menuItems(context, ::toggleFilter)

    override fun close() = Unit
}
