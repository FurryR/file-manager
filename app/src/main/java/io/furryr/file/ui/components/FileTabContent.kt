package io.furryr.file.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.furryr.file.R
import io.furryr.file.model.FileTab
import kotlin.math.roundToInt

@Composable
internal fun FileTabContent(
    index: Int,
    tab: FileTab,
    selected: Boolean,
    showClose: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit,
) {
    Tab(
        selected = selected,
        onClick = onClick,
        text = {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = tab.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(end = if (showClose) 18.dp else 0.dp)
                )
                if (showClose) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.cd_close_tab),
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .size(16.dp)
                            .clickable(onClick = onClose),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    )
}

/**
 * Tab with drag reordering. Dragging starts immediately; a plain tap still
 * selects the tab because the detector only claims the gesture past touch slop.
 *
 * The dragged tab id is snapshotted when the gesture starts: reordering reuses
 * this pointer node for whichever tab now occupies the slot, so reading the id
 * again mid-drag would move the neighbour instead. Displacement is measured in
 * whole tab widths and only the net distance is applied, which keeps a slot
 * from being skipped and stops the drag from drifting when it hits an edge.
 */
@Composable
internal fun DraggableFileTab(
    index: Int,
    tab: FileTab,
    selected: Boolean,
    showClose: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit,
    onMove: (tabId: String, direction: Int) -> Boolean,
) {
    val latestTabId by rememberUpdatedState(tab.id)
    val haptics = LocalHapticFeedback.current
    var widthPx by remember { mutableFloatStateOf(0f) }
    Box(
        modifier = Modifier
            .onGloballyPositioned { widthPx = it.size.width.toFloat() }
            .pointerInput(Unit) {
                var travelled = 0f
                var applied = 0
                var slot = 0f
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!awaitBriefHold(down)) return@awaitEachGesture

                    // Snapshot the identity: reordering reuses this pointer
                    // node for whichever tab now occupies the slot.
                    val draggedTabId = latestTabId
                    travelled = 0f
                    applied = 0
                    // Freeze the slot size: the node may host a different tab
                    // after the first swap, but the gesture keeps its scale.
                    slot = widthPx
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    var lastX = down.position.x
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        change.consume()
                        travelled += change.position.x - lastX
                        lastX = change.position.x
                        val unit = slot.takeIf { it > 0f } ?: 72.dp.toPx()
                        while (true) {
                            val target = (travelled / unit).roundToInt()
                            if (target == applied) break
                            val direction = if (target > applied) 1 else -1
                            if (!onMove(draggedTabId, direction)) {
                                // At the end of the row: drop the surplus so
                                // dragging back does not need a double distance.
                                travelled = applied * unit
                                break
                            }
                            applied += direction
                        }
                    }
                }
            }
    ) {
        FileTabContent(
            index = index,
            tab = tab,
            selected = selected,
            showClose = showClose,
            onClick = onClick,
            onClose = onClose,
        )
    }
}
