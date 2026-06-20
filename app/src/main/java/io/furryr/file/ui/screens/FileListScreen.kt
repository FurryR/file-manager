package io.furryr.file.ui.screens
import io.furryr.file.FileRepository
import io.furryr.file.ui.theme.*
import io.furryr.file.ui.screens.*
import io.furryr.file.ui.components.*
import io.furryr.file.ui.util.*
import io.furryr.file.daemon.*
import io.furryr.file.model.*
import io.furryr.file.R
import io.furryr.file.model.ErrorState

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.furryr.file.daemon.PermissionDeniedException
import io.furryr.file.model.DirectoryInfo
import io.furryr.file.model.FileEntry
import io.furryr.file.ui.util.formatSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import android.util.Log
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class SortMode { NAME, DATE, SIZE, TYPE }

@Composable
fun FileListScreen(
    path: String,
    refreshToken: Int,
    animationToken: Int,
    sortMode: SortMode,
    sortReverse: Boolean,
    filterText: String = "",
    scrollToPath: String? = null,
    onScrollConsumed: () -> Unit = {},
    onInfoChange: (DirectoryInfo) -> Unit,
    onEntriesChange: (List<FileEntry>) -> Unit = {},
    onDirectoryClick: (String) -> Unit,
    onFileClick: (FileEntry) -> Unit,
    onLongPress: (FileEntry) -> Unit,
    selectionMode: Boolean = false,
    selectedPaths: Set<String> = emptySet(),
    onToggleSelection: (FileEntry) -> Unit = {},
    onRangeSelect: (FileEntry) -> Unit = {}
) {
    var entries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var isRefreshing by remember { mutableStateOf(false) }
    var showSpinner by remember { mutableStateOf(false) }
    var spinnerDelayJob by remember { mutableStateOf<Job?>(null) }
    var loadJob by remember { mutableStateOf<Job?>(null) }
    var loadGeneration by remember { mutableIntStateOf(0) }
    var isInternal by remember { mutableStateOf(false) }
    var loadSequence by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val currentPath by rememberUpdatedState(path)
    val listState = rememberLazyListState()
    val filteredEntries = remember(entries, filterText) {
        if (filterText.isBlank()) entries
        else entries.filter { it.name.contains(filterText, ignoreCase = true) }
    }
    var lastPath by remember { mutableStateOf(path) }
    var lastRefreshToken by remember { mutableIntStateOf(refreshToken) }
    var lastAnimationToken by remember { mutableIntStateOf(animationToken) }
    var animateNextLoad by remember { mutableStateOf(true) }
    var animationRun by remember { mutableIntStateOf(0) }
    var activeAnimationRun by remember { mutableIntStateOf(0) }
    var animatedIndices by remember { mutableStateOf<Set<Int>>(emptySet()) }

    fun sort(raw: List<FileEntry>): List<FileEntry> {
        val sorted = when (sortMode) {
            SortMode.NAME -> raw.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
            SortMode.DATE -> raw.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenByDescending { it.modifiedAt })
            SortMode.SIZE -> raw.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenByDescending { it.size })
            SortMode.TYPE -> raw.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenBy { it.name.substringAfterLast('.', "") }.thenBy { it.name.lowercase() })
        }
        return if (sortReverse) sorted.reversed() else sorted
    }

    fun loadEntries() {
        val requestedPath = path
        val generation = ++loadGeneration
        Log.d("FileListScreen", "loadEntries: path=$requestedPath, isRefresh=$isRefreshing")
        loadJob?.cancel()
        spinnerDelayJob?.cancel()
        error = null
        loadJob = scope.launch {
            try {
                spinnerDelayJob = launch {
                    delay(300)
                    if (generation == loadGeneration && requestedPath == currentPath) {
                        showSpinner = true
                        Log.d("FileListScreen", "loadEntries: spinner shown after 300ms delay")
                    }
                }
                val result = withContext(Dispatchers.IO) { FileRepository.list(requestedPath) }
                if (generation != loadGeneration || requestedPath != currentPath) return@launch
                val raw = result.getOrDefault(emptyList())
                val newEntries = sort(raw)
                isInternal = newEntries.firstOrNull()?.realPath?.let { isInternalStoragePath(it) } ?: false
                val shouldAnimate = animateNextLoad
                if (shouldAnimate) {
                    val firstVisible = listState.firstVisibleItemIndex
                    animatedIndices = (firstVisible until (firstVisible + 24).coerceAtMost(newEntries.size)).toSet()
                    animationRun++
                    activeAnimationRun = animationRun
                } else {
                    animatedIndices = emptySet()
                    activeAnimationRun = 0
                }
                entries = newEntries
                onEntriesChange(newEntries)
                loadSequence++
                animateNextLoad = false
                error = result.exceptionOrNull()
                onInfoChange(FileRepository.info(requestedPath, raw))
                Log.d("FileListScreen", "loadEntries: done, entries=${newEntries.size}, error=${result.exceptionOrNull()}, isRefreshing=$isRefreshing")
            } finally {
                if (generation == loadGeneration) {
                    spinnerDelayJob?.cancel()
                    showSpinner = false
                    isRefreshing = false
                }
            }
        }
    }

    LaunchedEffect(path, refreshToken, animationToken, sortMode, sortReverse) {
        Log.d("FileListScreen", "LaunchedEffect: path=$path, refreshToken=$refreshToken, animationToken=$animationToken")
        val pathChanged = lastPath != path
        val refreshChanged = lastRefreshToken != refreshToken
        val animationChanged = lastAnimationToken != animationToken
        animateNextLoad = loadGeneration == 0 || animationChanged || (pathChanged && !refreshChanged)
        lastPath = path
        lastRefreshToken = refreshToken
        lastAnimationToken = animationToken
        if (refreshChanged) {
            isRefreshing = true
        }
        loadEntries()
    }

    LaunchedEffect(activeAnimationRun, animatedIndices) {
        if (activeAnimationRun > 0 && animatedIndices.isNotEmpty()) {
            val maxDelay = animatedIndices.maxOf { (it * 7).coerceAtMost(56) }
            delay(maxDelay + 160L)
            activeAnimationRun = 0
            animatedIndices = emptySet()
        }
    }

    var consumedScrollPath by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(scrollToPath, entries) {
        if (scrollToPath != null && scrollToPath != consumedScrollPath && entries.isNotEmpty()) {
            val index = entries.indexOfFirst { it.path == scrollToPath }
            if (index >= 0) {
                listState.animateScrollToItem(index, 0)
                consumedScrollPath = scrollToPath
                onScrollConsumed()
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize()
        ) {
            when {
                error != null && entries.isEmpty() -> item {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillParentMaxSize()
                    ) {
                        ErrorState(message = errorMessage(error))
                    }
                }
                entries.isEmpty() && !showSpinner -> item {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillParentMaxSize()
                    ) {
                        Text(stringResource(R.string.empty_folder))
                    }
                }
                entries.isNotEmpty() && filteredEntries.isEmpty() && filterText.isNotBlank() -> item {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillParentMaxSize()
                    ) {
                        Text(stringResource(R.string.no_results))
                    }
                }
                else -> itemsIndexed(filteredEntries, key = { i, entry -> "${i}_${entry.path}" }) { index, entry ->
                    val onClick = {
                        if (entry.isDirectory) onDirectoryClick(entry.path)
                        else onFileClick(entry)
                    }
                    val onLongPress = { onLongPress(entry) }
                    if (activeAnimationRun > 0 && index in animatedIndices) {
                        key(activeAnimationRun, entry.path) {
                            AnimatedFileRow(
                                entry = entry,
                                isInternal = isInternal,
                                selected = entry.path in selectedPaths,
                                selectionMode = selectionMode,
                                index = index,
                                animationSeed = activeAnimationRun,
                                onClick = onClick,
                                onLongPress = onLongPress,
                                onToggleSelection = { onToggleSelection(entry) },
                                onRangeSelect = { onRangeSelect(entry) }
                            )
                        }
                    } else {
                        FileRow(
                            entry = entry,
                            isInternal = isInternal,
                            selected = entry.path in selectedPaths,
                            selectionMode = selectionMode,
                            onClick = onClick,
                            onLongPress = onLongPress,
                            onToggleSelection = { onToggleSelection(entry) },
                            onRangeSelect = { onRangeSelect(entry) }
                        )
                    }
                }
            }
        }
        if (showSpinner) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(modifier = Modifier.size(36.dp))
                    Spacer(Modifier.padding(vertical = 4.dp))
                    TextButton(onClick = {
                        spinnerDelayJob?.cancel()
                        showSpinner = false
                    }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            }
        }
    }
}

@Composable
private fun errorMessage(error: Throwable?): String = when (error) {
    is PermissionDeniedException -> stringResource(R.string.permission_denied)
    else -> error?.localizedMessage ?: stringResource(R.string.permission_denied)
}

@Composable
private fun AnimatedFileRow(
    entry: FileEntry,
    isInternal: Boolean,
    selected: Boolean,
    selectionMode: Boolean,
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
            selected = selected,
            selectionMode = selectionMode,
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
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onToggleSelection: () -> Unit,
    onRangeSelect: () -> Unit
) {
    val isDir = entry.isDirectory
    var rowDragOffset by remember { mutableStateOf(0f) }
    val swipeOffset by animateFloatAsState(
        targetValue = rowDragOffset,
        animationSpec = tween(durationMillis = 120, easing = FastOutSlowInEasing),
        label = "swipeOffset"
    )
    val backgroundColor = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
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
            tint = if (isDir) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
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
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = formatModifiedTime(entry.modifiedAt),
                maxLines = 1,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = if (isDir) formatPermissions(entry.mode, true)
                    else "${formatPermissions(entry.mode, false)}  ${formatSize(entry.size)}",
                maxLines = 1,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
