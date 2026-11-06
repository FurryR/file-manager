package io.furryr.file.ui.components
import io.furryr.file.ui.theme.*
import io.furryr.file.ui.screens.*
import io.furryr.file.ui.components.*
import io.furryr.file.ui.util.*
import io.furryr.file.model.*

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import io.furryr.file.core.DirectoryStats

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
    /** Container views show a breadcrumb but do not parse it as a path. */
    editable: Boolean = true,
) {
    val summary = info.summary(LocalContext.current)
    var editing by rememberSaveable(path, displayPath) { mutableStateOf(false) }
    var field by rememberSaveable(path, displayPath, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(displayPath))
    }
    // Only a focus loss after this edit actually took focus may cancel the
    // edit; otherwise the first unfocused callback would close it instantly.
    var focusedOnce by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    fun selectAll() {
        field = field.copy(selection = TextRange(0, field.text.length))
    }

    fun submit() {
        val target = field.text.trim()
        editing = false
        if (target.isNotEmpty() && target != displayPath) onNavigate(target)
    }

    BackHandler(enabled = editing) { editing = false }
    LaunchedEffect(editing) {
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
                field = TextFieldValue(displayPath, selection = TextRange(0, displayPath.length))
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
}

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
