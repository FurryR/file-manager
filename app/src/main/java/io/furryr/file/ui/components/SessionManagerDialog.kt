package io.furryr.file.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.furryr.file.R
import io.furryr.file.terminal.TerminalProfiles
import io.furryr.file.core.ContainerUris
import io.furryr.file.model.FileTab
import io.furryr.file.model.PageKind

/** Assumed two-line row pitch, used to give the list a bounded height. */
private val RowHeight = 54.dp
private val MaxListHeight = 340.dp

/** Leading icon for a tab, mirroring what kind of location it shows. */
private fun sessionIcon(tab: FileTab): ImageVector = when {
    tab.currentPage.kind == PageKind.PAGE -> Icons.Default.Language
    ContainerUris.isContainer(tab.path) -> Icons.Default.DesktopWindows
    TerminalProfiles.isSsh(tab.path) -> Icons.Default.Cloud
    else -> Icons.Default.Folder
}

/** Secondary line: where the tab actually points. */
private fun sessionDetail(tab: FileTab): String = tab.currentPage.path

/**
 * Tab ("session") manager: switch, reorder and close tabs without hunting for
 * the close icon on a narrow tab. The whole row switches tabs and dismisses the
 * dialog; close stays a separate button so a slip cannot kill a session.
 *
 * Rows reorder with a brief hold (same gesture as the tab strip), so plain
 * drags still scroll the list. The dragged row tracks the finger and the rows
 * it displaces animate to their new slots.
 */
@Composable
fun SessionManagerDialog(
    tabs: List<FileTab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onClose: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val latestTabs by rememberUpdatedState(tabs)
    val haptics = LocalHapticFeedback.current
    var draggedId by remember { mutableStateOf<String?>(null) }
    var draggedOffset by remember { mutableFloatStateOf(0f) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.session_manager)) },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height((RowHeight * tabs.size).coerceAtMost(MaxListHeight)),
            ) {
                itemsIndexed(tabs, key = { _, tab -> tab.id }) { index, tab ->
                    val selected = index == selectedIndex
                    val dragging = tab.id == draggedId
                    val latestId by rememberUpdatedState(tab.id)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            // The dragged row follows the finger, so it must not
                            // also animate its own placement.
                            .then(if (dragging) Modifier else Modifier.animateItem())
                            .zIndex(if (dragging) 1f else 0f)
                            .graphicsLayer {
                                translationY = if (dragging) draggedOffset else 0f
                            }
                            .padding(vertical = 2.dp)
                            .background(
                                if (selected) MaterialTheme.colorScheme.secondaryContainer
                                else Color.Transparent,
                                RoundedCornerShape(8.dp),
                            )
                            .clickable { onSelect(index) }
                            .pointerInput(Unit) {
                                var dragIndex = -1
                                var startIndex = 0
                                var maxIndex = 0
                                var travelled = 0f
                                var rowHeight = 0f
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    if (!awaitBriefHold(down)) return@awaitEachGesture
                                    startIndex = latestTabs
                                        .indexOfFirst { it.id == latestId }
                                        .coerceAtLeast(0)
                                    dragIndex = startIndex
                                    maxIndex = latestTabs.lastIndex.coerceAtLeast(0)
                                    travelled = 0f
                                    rowHeight = size.height.toFloat()
                                    draggedId = latestId
                                    draggedOffset = 0f
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    var lastY = down.position.y
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes
                                            .firstOrNull { it.id == down.id } ?: break
                                        if (!change.pressed) break
                                        change.consume()
                                        travelled += change.position.y - lastY
                                        lastY = change.position.y
                                        val slot = rowHeight.takeIf { it > 0f } ?: 1f
                                        // Slots from the drag start, clamped to
                                        // the ends so the row cannot be pulled
                                        // past them and dragging back does not
                                        // have to retrace overshoot.
                                        val slots = (travelled / slot).coerceIn(
                                            -startIndex.toFloat(),
                                            (maxIndex - startIndex).toFloat(),
                                        )
                                        travelled = slots * slot
                                        // One slot per step with a dead band:
                                        // jitter at a boundary must not flip the
                                        // order (and its placement animations)
                                        // back and forth.
                                        val relative = slots - (dragIndex - startIndex)
                                        val step = when {
                                            relative > 0.6f -> 1
                                            relative < -0.6f -> -1
                                            else -> 0
                                        }
                                        if (step != 0) {
                                            val to = dragIndex + step
                                            onMove(dragIndex, to)
                                            dragIndex = to
                                        }
                                        // Keep the row under the finger: subtract
                                        // the distance its slot already moved.
                                        draggedOffset =
                                            travelled - (dragIndex - startIndex) * slot
                                    }
                                    draggedId = null
                                    draggedOffset = 0f
                                }
                            }
                            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    ) {
                        Icon(
                            imageVector = sessionIcon(tab),
                            contentDescription = null,
                            tint = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 12.dp).size(20.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = tab.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                                color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                                else MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = sessionDetail(tab),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(
                            enabled = tabs.size > 1,
                            onClick = { onClose(index) },
                        ) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_close_tab))
                        }
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) }
        },
        confirmButton = {},
    )
}
