package io.furryr.file.render

import android.content.Context
import io.furryr.file.ui.icons.Icons
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.furryr.file.R
import io.furryr.file.home.HomeEntry
import io.furryr.file.home.HomeStore
import io.furryr.file.isHomePath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Everything the Home screen draws. The session reads [HomeStore] directly;
 * the shell only supplies navigation actions.
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
 * the tab owns Back/Forward like it does for a page, and [HomeRenderer] only
 * draws the projected state.
 */
internal class HomeRendererSession(
    initialState: HomePageState? = null,
    title: String = "",
) : PageSession<HomePageState>(initialState) {
    private var host: RendererContext? = null
    internal val state: HomePageState = seedState ?: HomePageState
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

    private var openJob: Job? = null
    var opening by mutableStateOf(false)
        private set

    /**
     * Resolve the row to a node, hand it to the opener and open the renderer
     * it returns. The caller never decides between a renderer, a chooser or a
     * platform hand-off.
     */
    fun open(entry: HomeEntry) {
        val currentHost = host ?: return
        openJob?.cancel()
        opening = true
        openJob = currentHost.scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    currentHost.vfs.node(NodeAddress.parse(entry.location))
                }
                val node = result.getOrNull()
                if (node == null) {
                    currentHost.open(
                        ErrorRenderer(
                            entry.location,
                            result.exceptionOrNull()
                                ?: IllegalStateException("Cannot open ${entry.location}"),
                        ),
                    )
                    return@launch
                }
                currentHost.open(currentHost.opener.route(node))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                currentHost.reportError(error)
            } finally {
                opening = false
                openJob = null
            }
        }
    }

    fun cancelOpening() {
        openJob?.cancel()
        openJob = null
        opening = false
    }

    /** Lists come straight from [HomeStore]; the shell is not a data source. */
    fun view(): HomeRendererState {
        HomeStore.revision()
        return HomeRendererState(
            recent = HomeStore.recent(),
            bookmarks = HomeStore.bookmarks(),
            opening = opening,
        )
    }

    override fun pathBar(context: Context): RendererPath =
        RendererPath(
            title = context.getString(R.string.home_title),
            editText = "",
        ).also { homeTitle = it.title }

    override fun menuItems(context: Context): List<RendererMenuItem> = listOf(
        RendererMenuItem(
            title = context.getString(R.string.filter_title),
            icon = Icons.Default.FilterAlt,
            onClick = ::toggleFilter,
        ),
    )

    override fun close() {
        openJob?.cancel()
        openJob = null
    }

    override fun reset() {
        host = null
        cancelOpening()
    }
}
