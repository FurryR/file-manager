package io.furryr.file.render

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.furryr.file.R
import io.furryr.file.home.Bookmarks
import io.furryr.file.home.HomeEntry
import io.furryr.file.home.HomeEntryKind
import io.furryr.file.home.HomeStore
import io.furryr.file.model.PageKind
import io.furryr.file.ui.components.OpeningOverlay
import io.furryr.file.ui.icons.Icons
import kotlinx.coroutines.launch

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

/** View half of the Home session. */
internal class HomeSessionView(
    private val session: HomeRendererSession,
) : SessionView {
    @Composable
    override fun Content(host: RendererContext, darkTheme: Boolean, modifier: Modifier) {
        session.useHost(host)
        val state = session.view()
        val context = LocalContext.current
        val snackbarHostState = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        val undoLabel = stringResource(R.string.undo)
        var clearBookmarksPrompt by remember { mutableStateOf(false) }
        var clearRecentPrompt by remember { mutableStateOf(false) }

        fun announceDeleted(count: Int, undo: () -> Unit) {
            if (count <= 0) return
            scope.launch {
                snackbarHostState.currentSnackbarData?.dismiss()
                val result = snackbarHostState.showSnackbar(
                    message = context.getString(R.string.home_deleted_count, count),
                    actionLabel = undoLabel,
                    duration = SnackbarDuration.Long,
                )
                if (result == SnackbarResult.ActionPerformed) undo()
            }
        }

        fun restoreRecent(entries: List<HomeEntry>) {
            entries.asReversed().forEach { entry ->
                HomeStore.record(entry.kind, entry.location, entry.title, entry.detail)
            }
        }

        fun restoreBookmarks(entries: List<HomeEntry>) {
            entries.asReversed().forEach { entry ->
                if (!HomeStore.isBookmarked(entry.kind, entry.location)) {
                    HomeStore.toggleBookmark(entry)
                }
            }
        }

        val query = session.filterText.trim()
        fun matches(entry: HomeEntry): Boolean =
            query.isEmpty() ||
                entry.title.contains(query, ignoreCase = true) ||
                entry.detail.contains(query, ignoreCase = true) ||
                entry.location.contains(query, ignoreCase = true)
        val visibleBookmarks = state.bookmarks.filter(::matches)
        val bookmarked = state.bookmarks.map { it.key }.toSet()
        // HomeStore already keeps files and locations in one visit order;
        // splitting them here would hide that order behind two headings.
        val recentEntries = state.recent.filter(::matches)
        val empty = visibleBookmarks.isEmpty() && recentEntries.isEmpty()

        Box(modifier = modifier.fillMaxSize()) {
            if (empty) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(
                            if (query.isEmpty()) R.string.home_empty else R.string.home_no_matches,
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (visibleBookmarks.isNotEmpty()) {
                        item(key = "bookmarks_header") {
                            SectionHeader(
                                title = stringResource(R.string.home_bookmarks),
                                action = stringResource(R.string.home_clear),
                                onAction = { clearBookmarksPrompt = true },
                            )
                        }
                        items(visibleBookmarks, key = { "bookmark:${it.key}" }) { entry ->
                            SwipeableHomeRow(
                                entry = entry,
                                bookmarked = true,
                                onOpen = { session.open(entry) },
                                onToggleBookmark = { Bookmarks.toggle(entry) },
                                onRemove = {
                                    Bookmarks.remove(entry)
                                    announceDeleted(1) { restoreBookmarks(listOf(entry)) }
                                },
                            )
                        }
                    }
                    if (recentEntries.isNotEmpty()) {
                        item(key = "recent_header") {
                            SectionHeader(
                                title = stringResource(R.string.home_recent),
                                action = stringResource(R.string.home_clear),
                                onAction = { clearRecentPrompt = true },
                            )
                        }
                        items(recentEntries, key = { "recent:${it.key}" }) { entry ->
                            SwipeableHomeRow(
                                entry = entry,
                                bookmarked = entry.key in bookmarked,
                                onOpen = { session.open(entry) },
                                onToggleBookmark = { Bookmarks.toggle(entry) },
                                onRemove = {
                                    Bookmarks.removeRecent(entry)
                                    announceDeleted(1) { restoreRecent(listOf(entry)) }
                                },
                            )
                        }
                    }
                }
            }
            OpeningOverlay(opening = state.opening, onCancel = session::cancelOpening)
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        if (clearBookmarksPrompt) {
            AlertDialog(
                onDismissRequest = { clearBookmarksPrompt = false },
                title = { Text(stringResource(R.string.home_clear_bookmarks_confirm)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            clearBookmarksPrompt = false
                            val removed = state.bookmarks
                            Bookmarks.clearBookmarks()
                            announceDeleted(removed.size) { restoreBookmarks(removed) }
                        },
                    ) {
                        Text(stringResource(R.string.home_clear))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { clearBookmarksPrompt = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }
        if (clearRecentPrompt) {
            AlertDialog(
                onDismissRequest = { clearRecentPrompt = false },
                title = { Text(stringResource(R.string.home_clear_recent_confirm)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            clearRecentPrompt = false
                            val removed = state.recent
                            Bookmarks.clearRecent()
                            announceDeleted(removed.size) { restoreRecent(removed) }
                        },
                    ) {
                        Text(stringResource(R.string.home_clear))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { clearRecentPrompt = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 4.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (action != null && onAction != null) {
            TextButton(onClick = onAction) {
                Icon(
                    imageVector = Icons.Default.ClearAll,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(action)
            }
        }
    }
}

@Composable
private fun SwipeableHomeRow(
    entry: HomeEntry,
    bookmarked: Boolean,
    onOpen: () -> Unit,
    onToggleBookmark: () -> Unit,
    onRemove: () -> Unit,
) {
    val density = LocalDensity.current
    val dismissThreshold = with(density) { 56.dp.toPx() }
    val dismissVelocity = with(density) { 125.dp.toPx() }
    var rowWidth by remember { mutableFloatStateOf(0f) }
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    // End to start only: the drag is clamped at 0, so a rightward swipe never
    // moves the row or reveals the delete background.
    val dragState = rememberDraggableState { delta ->
        scope.launch {
            offset.snapTo((offset.value + delta).coerceIn(-rowWidth, 0f))
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { rowWidth = it.width.toFloat() },
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(MaterialTheme.colorScheme.errorContainer)
                .padding(horizontal = 20.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = stringResource(R.string.delete),
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { translationX = offset.value }
                .draggable(
                    state = dragState,
                    orientation = Orientation.Horizontal,
                    onDragStopped = { velocity ->
                        if (offset.value <= -dismissThreshold || velocity <= -dismissVelocity) {
                            offset.animateTo(-rowWidth, tween(durationMillis = 160))
                            onRemove()
                        } else {
                            offset.animateTo(0f, spring())
                        }
                    },
                ),
        ) {
            HomeRow(
                entry = entry,
                bookmarked = bookmarked,
                onOpen = onOpen,
                onToggleBookmark = onToggleBookmark,
            )
        }
    }
}

@Composable
private fun HomeRow(
    entry: HomeEntry,
    bookmarked: Boolean,
    onOpen: () -> Unit,
    onToggleBookmark: () -> Unit,
) {
    // Home always shows the address under the title, even when the title is the
    // address itself: the row must say where it points, not only what it is.
    val path = entry.detail.ifEmpty { entry.location }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onOpen)
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = when (entry.kind) {
                    HomeEntryKind.FILE -> Icons.AutoMirrored.Filled.InsertDriveFile
                    HomeEntryKind.FOLDER -> Icons.Default.Folder
                    HomeEntryKind.WEB -> Icons.Default.Language
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
            )
            if (path.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = path,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onToggleBookmark) {
            Icon(
                imageVector = if (bookmarked) Icons.Default.Star else Icons.Default.StarBorder,
                contentDescription = stringResource(
                    if (bookmarked) R.string.bookmark_remove else R.string.bookmark_add,
                ),
                tint = if (bookmarked) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
