package io.furryr.file.ui.components
import io.furryr.file.ui.theme.*
import io.furryr.file.ui.screens.*
import io.furryr.file.ui.components.*
import io.furryr.file.ui.util.*
import io.furryr.file.model.*

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import io.furryr.file.R
import io.furryr.file.core.DirectoryStats
import io.furryr.file.ui.util.PathSuggestion
import io.furryr.file.ui.util.PathSuggestionSource
import io.furryr.file.ui.util.SuggestionSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Path block in the top bar. A tap turns the path itself into an editor —
 * submitting navigates, back or losing focus restores the current path, so the
 * old jump dialog is no longer needed. The block always fills the row so the
 * whole title area is the tap target, and long paths collapse from the front:
 * the tail is what identifies the directory.
 */
@Composable
fun TitleBlock(
    path: String,
    info: DirectoryStats,
    onNavigate: (String) -> Unit,
    onBounds: (Rect?) -> Unit = {},
    /** Human-readable address; may differ from the editable Vfs path. */
    displayPath: String = path,
    /** What a tap puts in the field; a title is not necessarily an address. */
    editText: String = displayPath,
    /** Container views show a breadcrumb but do not parse it as a path. */
    editable: Boolean = true,
    /** Replaces the directory summary line, e.g. an editor's encoding/cursor. */
    detail: String? = null,
    /** Bookmark/recent rows and remote completions for the text being typed. */
    suggestionSource: PathSuggestionSource,
    /** The top bar in window coordinates; the overlay spans it and starts below it. */
    suggestionAnchor: Rect? = null,
    /** A row was chosen; the editor has already closed. */
    onSuggestion: (PathSuggestion) -> Unit,
    /** Reports whether the address bar is in edit mode. */
    onEditingChange: (Boolean) -> Unit = {},
) {
    val summary = detail ?: info.summary(LocalContext.current)
    var editing by rememberSaveable(path, displayPath, editText) { mutableStateOf(false) }
    var field by rememberSaveable(path, displayPath, editText, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(editText))
    }
    // Only a focus loss after this edit actually took focus may cancel the
    // edit; otherwise the first unfocused callback would close it instantly.
    var focusedOnce by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    var remoteResult by remember { mutableStateOf<RemoteSuggestions?>(null) }

    fun selectAll() {
        field = field.copy(selection = TextRange(0, field.text.length))
    }

    fun submit() {
        val target = field.text.trim()
        editing = false
        // An empty submission is meaningful: it returns to the home page.
        if (target != editText) onNavigate(target)
    }

    val query = field.text.trim()
    // The current address is not a query: suggestions start only after the
    // user changes the text, so tapping the bar does not cover the content.
    val typing = editing && query.isNotEmpty() && query != editText.trim()
    val localRows = if (typing) suggestionSource.local(query) else emptyList()
    val remoteRows = remoteResult?.takeIf { it.query == query }?.rows.orEmpty()
    val rows = localRows + remoteRows

    LaunchedEffect(editing, query, editText) {
        remoteResult = null
        if (!editing || query.isEmpty() || query == editText.trim()) return@LaunchedEffect
        delay(180)
        val fetched = withContext(Dispatchers.IO) {
            runCatching { suggestionSource.remote(query) }.getOrDefault(emptyList())
        }
        remoteResult = RemoteSuggestions(query, fetched)
    }

    fun choose(suggestion: PathSuggestion) {
        editing = false
        onSuggestion(suggestion)
    }

    BackHandler(enabled = editing) { editing = false }
    DisposableEffect(Unit) {
        onDispose { onEditingChange(false) }
    }
    LaunchedEffect(editing) {
        onEditingChange(editing)
        if (editing) {
            focusRequester.requestFocus()
            // Focus is applied on the next frame; re-assert the select-all and
            // the end cursor afterwards so the IME cannot reset it.
            withFrameNanos { }
            selectAll()
        }
    }

    Column(
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .onGloballyPositioned { onBounds(it.boundsInWindow()) }
            .clickable(enabled = !editing && editable) {
                field = TextFieldValue(editText, selection = TextRange(0, editText.length))
                focusedOnce = false
                editing = true
            }
    ) {
        if (editing) {
            BasicTextField(
                value = field,
                onValueChange = { field = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.titleSmall.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { submit() }, onDone = { submit() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged { state ->
                        if (state.isFocused) {
                            focusedOnce = true
                        } else if (focusedOnce) {
                            editing = false
                        }
                    }
            )
        } else {
            val style = MaterialTheme.typography.titleSmall
            val measurer = rememberTextMeasurer()
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val width = constraints.maxWidth
                val text = remember(displayPath, width, style) {
                    tailEllipsized(measurer, displayPath, style, width)
                }
                Text(
                    text = text,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    style = style
                )
            }
            if (summary.isNotEmpty()) {
                Text(
                    text = summary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }

    val suggestionBar = suggestionAnchor
    if (typing && rows.isNotEmpty() && suggestionBar != null) {
        val density = LocalDensity.current
        val popupWidth = with(density) { suggestionBar.width.toDp() }
        Popup(
            popupPositionProvider = SuggestionPopupPosition(suggestionBar.bottom.toInt()),
            properties = PopupProperties(
                focusable = false,
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false,
            ),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.background,
                modifier = Modifier.width(popupWidth),
            ) {
                Box(modifier = Modifier.heightIn(max = 360.dp)) {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        rows.forEachIndexed { index, suggestion ->
                            if (index == 0 || rows[index - 1].source != suggestion.source) {
                                Text(
                                    text = when (suggestion.source) {
                                        SuggestionSource.BOOKMARK -> stringResource(R.string.home_bookmarks)
                                        SuggestionSource.RECENT -> stringResource(R.string.home_recent_locations)
                                        SuggestionSource.INTERNET -> stringResource(R.string.suggestion_internet)
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(
                                        start = 16.dp,
                                        end = 16.dp,
                                        top = 6.dp,
                                        bottom = 2.dp,
                                    ),
                                )
                            }
                            SuggestionRow(suggestion) { choose(suggestion) }
                        }
                    }
                    // Hairline stays pinned to the panel edge while rows scroll.
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                }
            }
        }
    }
}

/**
 * Full-width dropdown that starts exactly where the top bar ends: the tab row
 * is underneath it, while the address bar stays visible.
 */
private class SuggestionPopupPosition(
    private val top: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset(0, top)
}

@Composable
private fun SuggestionRow(
    suggestion: PathSuggestion,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Icon(
            imageVector = when (suggestion.source) {
                SuggestionSource.BOOKMARK -> Icons.Default.Star
                SuggestionSource.RECENT -> Icons.Default.History
                SuggestionSource.INTERNET -> Icons.Default.Search
            },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Text(
                text = suggestion.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (suggestion.subtitle.isNotEmpty()) {
                Text(
                    text = suggestion.subtitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private data class RemoteSuggestions(
    val query: String,
    val rows: List<PathSuggestion>,
)

/**
 * Compose has no start ellipsis. Keep the tail (the part that identifies the
 * directory) and collapse the front to the widest suffix that still fits.
 */
private fun tailEllipsized(
    measurer: TextMeasurer,
    path: String,
    style: TextStyle,
    maxWidth: Int,
): String {
    if (maxWidth <= 0) return path
    fun fits(candidate: String): Boolean {
        val result = measurer.measure(
            text = candidate,
            style = style,
            maxLines = 1,
            constraints = Constraints(maxWidth = maxWidth),
        )
        return !result.hasVisualOverflow && result.size.width <= maxWidth
    }
    if (fits(path)) return path
    var low = 1
    var high = path.length
    while (low < high) {
        val mid = (low + high + 1) / 2
        if (fits("…" + path.takeLast(mid))) low = mid else high = mid - 1
    }
    return "…" + path.takeLast(low)
}
