package io.furryr.file.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.furryr.file.R
import io.furryr.file.home.HomeEntry
import io.furryr.file.home.HomeEntryKind
import io.furryr.file.ui.components.OpeningOverlay

/**
 * Landing view: recent files, recent locations and bookmarks. It draws only
 * entries, so opening one is the same node dispatch a directory row uses.
 */
@Composable
fun HomeScreen(
    recent: List<HomeEntry>,
    bookmarks: List<HomeEntry>,
    onOpen: (HomeEntry) -> Unit,
    onToggleBookmark: (HomeEntry) -> Unit,
    onRemoveRecent: (HomeEntry) -> Unit,
    onRemoveBookmark: (HomeEntry) -> Unit,
    onClearRecent: () -> Unit,
    onClearBookmarks: () -> Unit,
    opening: Boolean = false,
    onCancelOpening: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val bookmarked = bookmarks.map { it.key }.toSet()
    val recentFiles = recent.filter { it.kind == HomeEntryKind.FILE }
    val recentLocations = recent.filter { it.kind != HomeEntryKind.FILE }

    Box(modifier = modifier.fillMaxSize()) {
        if (recent.isEmpty() && bookmarks.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.home_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (bookmarks.isNotEmpty()) {
                    item(key = "bookmarks_header") {
                        SectionHeader(
                            title = stringResource(R.string.home_bookmarks),
                            action = stringResource(R.string.home_clear),
                            onAction = onClearBookmarks,
                        )
                    }
                    items(bookmarks, key = { "bookmark:${it.key}" }) { entry ->
                        SwipeableHomeRow(
                            entry = entry,
                            bookmarked = true,
                            onOpen = { onOpen(entry) },
                            onToggleBookmark = { onToggleBookmark(entry) },
                            onRemove = { onRemoveBookmark(entry) },
                        )
                    }
                }
                if (recentFiles.isNotEmpty()) {
                    item(key = "recent_files_header") {
                        SectionHeader(
                            title = stringResource(R.string.home_recent_files),
                            action = stringResource(R.string.home_clear),
                            onAction = onClearRecent,
                        )
                    }
                    items(recentFiles, key = { "file:${it.key}" }) { entry ->
                        SwipeableHomeRow(
                            entry = entry,
                            bookmarked = entry.key in bookmarked,
                            onOpen = { onOpen(entry) },
                            onToggleBookmark = { onToggleBookmark(entry) },
                            onRemove = { onRemoveRecent(entry) },
                        )
                    }
                }
                if (recentLocations.isNotEmpty()) {
                    item(key = "recent_locations_header") {
                        SectionHeader(
                            title = stringResource(R.string.home_recent_locations),
                            action = stringResource(R.string.home_clear),
                            onAction = onClearRecent,
                        )
                    }
                    items(recentLocations, key = { "location:${it.key}" }) { entry ->
                        SwipeableHomeRow(
                            entry = entry,
                            bookmarked = entry.key in bookmarked,
                            onOpen = { onOpen(entry) },
                            onToggleBookmark = { onToggleBookmark(entry) },
                            onRemove = { onRemoveRecent(entry) },
                        )
                    }
                }
            }
        }
        OpeningOverlay(opening = opening, onCancel = onCancelOpening)
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
    val state = rememberSwipeToDismissBoxState()
    // Let the dismissal animation finish before the list drops the row.
    LaunchedEffect(state.currentValue) {
        if (state.currentValue == SwipeToDismissBoxValue.EndToStart) onRemove()
    }
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
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
        },
    ) {
        HomeRow(
            entry = entry,
            bookmarked = bookmarked,
            onOpen = onOpen,
            onToggleBookmark = onToggleBookmark,
        )
    }
}

@Composable
private fun HomeRow(
    entry: HomeEntry,
    bookmarked: Boolean,
    onOpen: () -> Unit,
    onToggleBookmark: () -> Unit,
) {
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
                    HomeEntryKind.FILE -> Icons.Default.InsertDriveFile
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
            if (entry.detail.isNotEmpty() && entry.detail != entry.title) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = entry.detail,
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
