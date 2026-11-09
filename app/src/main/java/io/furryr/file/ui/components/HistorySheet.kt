package io.furryr.file.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.furryr.file.R

/** One entry of the per-tab history sheet. */
data class HistoryStop(
    /** Navigation target: a raw Vfs path or a page address. */
    val path: String,
    /** Primary line: folder name or page title. */
    val label: String,
    /** Secondary line: the full path or URL this entry points at. */
    val detail: String,
    val isWeb: Boolean,
    val current: Boolean = false,
    val folder: io.furryr.file.core.VFolder? = null,
)

/**
 * Per-tab history panel.
 *
 * The panel sits above the bottom toolbar ([bottomInset] is that toolbar's
 * height) and is revealed *by the drag itself*: [dragPx] is how far the reveal
 * gesture has travelled, so the panel tracks the finger instead of jumping
 * open. Releasing decides [visible]; the remaining distance is animated.
 */
@Composable
fun HistorySheet(
    visible: Boolean,
    dragPx: Float,
    stops: List<HistoryStop>,
    bottomInset: Dp,
    onSelect: (HistoryStop) -> Unit,
    onDismiss: () -> Unit,
) {
    var panelHeightPx by remember { mutableFloatStateOf(0f) }
    var containerHeightPx by remember { mutableFloatStateOf(0f) }
    val revealPx = remember { Animatable(0f) }
    val shown by remember { derivedStateOf { revealPx.value > 0.5f } }

    LaunchedEffect(visible, dragPx, panelHeightPx) {
        when {
            // Finger down: follow it exactly, never past the full height.
            dragPx > 0f -> revealPx.snapTo(
                if (panelHeightPx > 0f) dragPx.coerceAtMost(panelHeightPx) else dragPx
            )
            visible && panelHeightPx > 0f ->
                revealPx.animateTo(panelHeightPx, spring(dampingRatio = Spring.DampingRatioNoBouncy))
            else -> revealPx.animateTo(0f, spring(dampingRatio = Spring.DampingRatioNoBouncy))
        }
    }

    BackHandler(enabled = visible) { onDismiss() }

    Box(modifier = Modifier.fillMaxSize()) {
        if (shown) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = bottomInset)
                    .graphicsLayer {
                        alpha = 0.32f *
                            (revealPx.value / panelHeightPx.coerceAtLeast(1f)).coerceIn(0f, 1f)
                    }
                    .background(MaterialTheme.colorScheme.scrim)
                    .pointerInput(Unit) { detectTapGestures { onDismiss() } }
            )
        }
        // Clip at the toolbar line so a partially revealed panel emerges from
        // behind the bar instead of floating over it.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = bottomInset)
                .clipToBounds()
                .onGloballyPositioned { containerHeightPx = it.size.height.toFloat() }
        ) {
            Surface(
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .onGloballyPositioned { panelHeightPx = it.size.height.toFloat() }
                    .graphicsLayer {
                        // Before the first measurement, hide by the container
                        // height so the closed panel never flashes.
                        val hidden = if (panelHeightPx > 0f) panelHeightPx else containerHeightPx
                        translationY = (hidden - revealPx.value).coerceAtLeast(0f)
                    },
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(width = 32.dp, height = 2.dp)
                                .background(
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                    RoundedCornerShape(2.dp),
                                )
                        )
                    }
                    Text(
                        text = stringResource(R.string.history_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 8.dp),
                    )
                    if (stops.isEmpty()) {
                        Text(
                            text = stringResource(R.string.history_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                        )
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
                            itemsIndexed(stops) { _, stop ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onSelect(stop) }
                                        .padding(horizontal = 20.dp, vertical = 12.dp),
                                ) {
                                    Icon(
                                        imageVector = when {
                                            stop.current -> Icons.Default.Check
                                            stop.isWeb -> Icons.Default.Language
                                            else -> Icons.Default.Folder
                                        },
                                        contentDescription = null,
                                        tint = if (stop.current) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.width(14.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = stop.label,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            color = if (stop.current) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurface,
                                        )
                                        if (stop.detail.isNotEmpty()) {
                                            Text(
                                                text = stop.detail,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
