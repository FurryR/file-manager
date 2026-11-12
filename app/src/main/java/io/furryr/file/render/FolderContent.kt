package io.furryr.file.render
import io.furryr.file.core.VFolder
import io.furryr.file.core.VNode
import io.furryr.file.core.model.FileEntry
import io.furryr.file.ui.theme.*
import io.furryr.file.ui.components.*
import io.furryr.file.ui.util.*
import io.furryr.file.model.*
import io.furryr.file.R

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.content.ClipData
import android.view.View
import io.furryr.file.forward.ForwardFacts
import io.furryr.file.forward.ForwardFileProvider
import io.furryr.file.clipboard.ClipboardStore
import kotlinx.coroutines.delay

/**
 * The directory list. Entries, selection, loading and scroll live in
 * [FolderListing] (the renderer session); this composable only projects that
 * state and raises navigation/open gestures.
 */
@Composable
fun FolderContent(
    listing: FolderListing,
    path: String,
    committedPath: String = path,
    /** When set, this node is listed instead of resolving [path] through Vfs. */
    folderOverride: VFolder? = null,
    onNavigationLoaded: (String) -> Unit = {},
    onNavigationCancelled: () -> Unit = {},
    scrollToPath: String? = null,
    highlightPath: String? = null,
    highlightDirectory: String? = null,
    onScrollConsumed: () -> Unit = {},
    onHighlightConsumed: () -> Unit = {},
    onOpenEntry: (FileEntry, VNode?) -> Unit,
    onLongPress: (FileEntry) -> Unit,
    /** A file open (archive indexing, remote probe) is in flight. */
    opening: Boolean = false,
    onCancelOpening: () -> Unit = {},
    onFileDragStart: () -> Unit = {},
    onFileDragEnd: () -> Unit = {},
    /** Renderer-owned policy for a failed load; the list only supplies retry. */
    errorView: RendererErrorView,
) {
    var showOpeningSpinner by remember { mutableStateOf(false) }
    val spinnerVisible = listing.showSpinner || showOpeningSpinner
    val loadingScrimAlpha by animateFloatAsState(
        targetValue = if (spinnerVisible) 0.32f else 0f,
        animationSpec = tween(durationMillis = 150),
        label = "loadingScrimAlpha"
    )
    val currentOnNavigationCancelled by rememberUpdatedState(onNavigationCancelled)
    val currentOnCancelOpening by rememberUpdatedState(onCancelOpening)

    val filteredEntries = remember(listing.entries, listing.filterText) {
        if (listing.filterText.isBlank()) listing.entries
        else listing.entries.filter { it.name.contains(listing.filterText, ignoreCase = true) }
    }

    LaunchedEffect(opening) {
        if (!opening) {
            showOpeningSpinner = false
            return@LaunchedEffect
        }
        delay(300)
        showOpeningSpinner = true
    }

    LaunchedEffect(listing, folderOverride, path) {
        // The session, not this composition, owns what has been loaded. A tab
        // switch rebuilds the composition but must not refetch the listing it
        // already has. A refresh is sent straight to the session instead.
        val loadedPath = listing.loadedPath
        val overrideChanged = listing.loadedOverride !== folderOverride
        if (loadedPath == path && !overrideChanged && listing.error == null) {
            return@LaunchedEffect
        }
        val animate = loadedPath == null || loadedPath != path
        listing.load(
            path = path,
            folderOverride = folderOverride,
            animate = animate,
            highlightPath = highlightPath,
            highlightDirectory = highlightDirectory,
            onNavigationLoaded = onNavigationLoaded,
            onHighlightConsumed = onHighlightConsumed,
        )
    }

    LaunchedEffect(listing.activeAnimationRun, listing.animatedIndices) {
        if (listing.activeAnimationRun > 0 && listing.animatedIndices.isNotEmpty()) {
            val maxDelay = listing.animatedIndices.maxOf {
                ((it - listing.animationStartIndex) * 7).coerceAtMost(56)
            }
            delay(maxDelay + 160L)
            listing.finishAnimation()
        }
    }

    LaunchedEffect(scrollToPath, filteredEntries, listing.loadedPath, listing.loadSequence, listing.isLoading) {
        if (scrollToPath != null && !listing.isLoading && listing.loadedPath == path) {
            val index = filteredEntries.indexOfFirst { it.path == scrollToPath }
            if (index >= 0) {
                listing.listState.animateScrollToItem(index, 0)
                onScrollConsumed()
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listing.listState,
            modifier = Modifier.fillMaxSize()
        ) {
            when {
                listing.error != null && listing.entries.isEmpty() -> item {
                    val failure = listing.error
                    if (failure != null) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillParentMaxSize()
                        ) {
                            errorView.Content(failure) {
                                listing.load(
                                    path = path,
                                    folderOverride = folderOverride,
                                    animate = false,
                                    highlightPath = null,
                                    highlightDirectory = null,
                                    onNavigationLoaded = onNavigationLoaded,
                                    onHighlightConsumed = {},
                                )
                            }
                        }
                    }
                }
                listing.entries.isEmpty() && listing.loadedPath == path && !listing.isLoading -> item {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillParentMaxSize()
                    ) {
                        Text(stringResource(R.string.empty_folder))
                    }
                }
                listing.entries.isNotEmpty() && filteredEntries.isEmpty() && listing.filterText.isNotBlank() -> item {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillParentMaxSize()
                    ) {
                        Text(stringResource(R.string.no_results))
                    }
                }
                else -> itemsIndexed(
                    filteredEntries,
                    key = { i, entry -> "${i}_${entry.path}" },
                    contentType = { _, _ -> "file" },
                ) { index, entry ->
                    val node = listing.nodesByName[entry.name]
                    val selectionMode = listing.selectedPaths.isNotEmpty() &&
                        !listing.selectionLocked && !ClipboardStore.state.inTransfer
                    val onClick = {
                        if (!listing.isLoading && path == committedPath) {
                            if (selectionMode) {
                                listing.toggleSelection(entry)
                            } else {
                                onOpenEntry(entry, node)
                            }
                        }
                    }
                    val onRowLongPress = {
                        if (!listing.isLoading && !listing.selectionLocked &&
                            !ClipboardStore.state.inTransfer && path == committedPath
                        ) {
                            onLongPress(entry)
                        }
                    }
                    if (listing.activeAnimationRun > 0 && index in listing.animatedIndices) {
                        key(listing.activeAnimationRun, entry.path) {
                            AnimatedFileRow(
                                entry = entry,
                                isInternal = listing.isInternal,
                                draggable = folderOverride == null,
                                selected = entry.path in listing.selectedPaths,
                                selectionMode = selectionMode,
                                onFileDragStart = onFileDragStart,
                                onFileDragEnd = onFileDragEnd,
                                index = index - listing.animationStartIndex,
                                animationSeed = listing.activeAnimationRun,
                                onClick = onClick,
                                onLongPress = onRowLongPress,
                                onToggleSelection = {
                                    if (!listing.isLoading && path == committedPath) listing.toggleSelection(entry)
                                },
                                onRangeSelect = {
                                    if (!listing.isLoading && path == committedPath) listing.rangeSelectTo(entry)
                                }
                            )
                        }
                    } else {
                        FileRow(
                            entry = entry,
                            isInternal = listing.isInternal,
                            draggable = folderOverride == null,
                            selected = entry.path in listing.selectedPaths,
                            selectionMode = selectionMode,
                            onFileDragStart = onFileDragStart,
                            onFileDragEnd = onFileDragEnd,
                            onClick = onClick,
                            onLongPress = onRowLongPress,
                            onToggleSelection = {
                                if (!listing.isLoading && path == committedPath) listing.toggleSelection(entry)
                            },
                            onRangeSelect = {
                                if (!listing.isLoading && path == committedPath) listing.rangeSelectTo(entry)
                            }
                        )
                    }
                }
            }
        }
        if (listing.isLoading || opening) {
            val scrimColor = MaterialTheme.colorScheme.scrim
            Box(
                Modifier.fillMaxSize()
                    .drawBehind {
                        if (spinnerVisible) drawRect(scrimColor.copy(alpha = loadingScrimAlpha))
                    }
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent().changes.forEach { it.consume() }
                            }
                        }
                    }
            )
        }
        if (spinnerVisible) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(modifier = Modifier.size(36.dp))
                    Spacer(Modifier.padding(vertical = 4.dp))
                    TextButton(onClick = {
                        if (opening) currentOnCancelOpening()
                        if (listing.isLoading) {
                            listing.cancelLoading()
                            currentOnNavigationCancelled()
                        }
                    }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            }
        }
    }
}

@Composable
private fun AnimatedFileRow(
    entry: FileEntry,
    isInternal: Boolean,
    draggable: Boolean,
    selected: Boolean,
    selectionMode: Boolean,
    onFileDragStart: () -> Unit,
    onFileDragEnd: () -> Unit,
    index: Int,
    animationSeed: Int,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onToggleSelection: () -> Unit,
    onRangeSelect: () -> Unit
) {
    val itemDelay = (index * 7).coerceAtMost(56)
    var visible by remember(animationSeed, entry.path) { mutableStateOf(false) }
    LaunchedEffect(animationSeed, entry.path) { visible = true }
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(
            durationMillis = 120,
            delayMillis = itemDelay,
            easing = FastOutSlowInEasing
        ),
        label = "itemAlpha"
    )
    val offsetY by animateFloatAsState(
        targetValue = if (visible) 0f else 10f,
        animationSpec = tween(
            durationMillis = 140,
            delayMillis = itemDelay,
            easing = FastOutSlowInEasing
        ),
        label = "itemOffsetY"
    )
    Box(
        modifier = Modifier.graphicsLayer {
            this.alpha = alpha
            this.translationY = offsetY
        }
    ) {
        FileRow(
            entry = entry,
            isInternal = isInternal,
            draggable = draggable,
            selected = selected,
            selectionMode = selectionMode,
            onFileDragStart = onFileDragStart,
            onFileDragEnd = onFileDragEnd,
            onClick = onClick,
            onLongPress = onLongPress,
            onToggleSelection = onToggleSelection,
            onRangeSelect = onRangeSelect
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    entry: FileEntry,
    isInternal: Boolean,
    draggable: Boolean,
    selected: Boolean,
    selectionMode: Boolean,
    onFileDragStart: () -> Unit,
    onFileDragEnd: () -> Unit,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onToggleSelection: () -> Unit,
    onRangeSelect: () -> Unit
) {
    val isDir = entry.isDirectory
    val context = LocalContext.current
    val modifiedText = remember(entry.modifiedAt) { formatModifiedTime(entry.modifiedAt) }
    val detailText = remember(isDir, entry.mode, entry.size) {
        if (isDir) formatPermissions(entry.mode, true)
        else "${formatPermissions(entry.mode, false)}  ${formatSize(entry.size)}"
    }
    val currentOnFileDragStart by rememberUpdatedState(onFileDragStart)
    val currentOnFileDragEnd by rememberUpdatedState(onFileDragEnd)
    var rowDragOffset by remember { mutableStateOf(0f) }
    val swipeOffset by animateFloatAsState(
        targetValue = rowDragOffset,
        animationSpec = tween(durationMillis = 120, easing = FastOutSlowInEasing),
        label = "swipeOffset"
    )
    val backgroundColor = if (selected) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.background
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { translationX = swipeOffset }
            .background(backgroundColor)
            .combinedClickable(
                onClick = { if (selectionMode) onToggleSelection() else onClick() },
                onLongClick = onLongPress
            )
            .pointerInput(entry.path, selectionMode) {
                var totalDrag = 0f
                detectHorizontalDragGestures(
                    onDragStart = {
                        totalDrag = 0f
                        rowDragOffset = 0f
                    },
                    onHorizontalDrag = { _, dragAmount ->
                        totalDrag += dragAmount
                        rowDragOffset = totalDrag.coerceIn(-48f, 48f)
                    },
                    onDragEnd = {
                        if (kotlin.math.abs(totalDrag) > 32f) onRangeSelect()
                        rowDragOffset = 0f
                    },
                    onDragCancel = {
                        rowDragOffset = 0f
                    }
                )
            }
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Icon(
            imageVector = iconForFile(entry.name, isDir),
            contentDescription = null,
            tint = fileIconTint(entry.name, isDir),
            modifier = if (selectionMode) {
                Modifier
            } else Modifier.dragAndDropSource {
                detectDragGestures(
                    onDragStart = {
                        // Files carry a tokenized content URI so other apps
                        // (e.g., ColorOS Content Portal) can read the bytes.
                        val clip = if (isDir) {
                            ClipData.newPlainText(entry.name, entry.path)
                        } else {
                            ClipData.newUri(
                                context.contentResolver,
                                entry.name,
                                ForwardFileProvider.buildUri(
                                    entry.path,
                                    facts = ForwardFacts(entry.name, entry.size),
                                ),
                            )
                        }
                        startTransfer(DragAndDropTransferData(
                            clipData = clip,
                            localState = entry,
                            flags = View.DRAG_FLAG_GLOBAL or View.DRAG_FLAG_GLOBAL_URI_READ,
                        ))
                        currentOnFileDragStart()
                    },
                    onDrag = { change, _ -> change.consume() },
                    onDragEnd = { currentOnFileDragEnd() },
                )
            },
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = entry.name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontWeight = if (isDir) FontWeight.Medium else FontWeight.Normal,
            modifier = Modifier.weight(1f)
        )
        if (entry.isSymlink) {
            Text(
                text = " ->",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = modifiedText,
                maxLines = 1,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = detailText,
                maxLines = 1,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
