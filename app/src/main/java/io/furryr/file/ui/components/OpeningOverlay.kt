package io.furryr.file.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.furryr.file.R
import kotlinx.coroutines.delay

/**
 * Scrim + spinner for an open that can outlive one frame (remote probe,
 * archive index, container descent). It is deliberately delayed: a fast open
 * must not flash an overlay. While shown it consumes input so a second row
 * cannot start a competing open.
 */
@Composable
fun OpeningOverlay(
    opening: Boolean,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(opening) {
        if (!opening) {
            visible = false
            return@LaunchedEffect
        }
        delay(300)
        visible = true
    }
    if (!opening) return
    val scrim = MaterialTheme.colorScheme.scrim
    val alpha by animateFloatAsState(
        targetValue = if (visible) 0.32f else 0f,
        animationSpec = tween(durationMillis = 150),
        label = "openingScrimAlpha",
    )
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        // The blocker stays behind the cancel button, so the button remains
        // clickable while every other touch is swallowed.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    if (visible) drawRect(scrim.copy(alpha = alpha))
                }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent().changes.forEach { it.consume() }
                        }
                    }
                },
        )
        if (visible) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(modifier = Modifier.size(36.dp))
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier.padding(vertical = 4.dp),
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    }
}
