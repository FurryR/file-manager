package io.furryr.file.ui.components

import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Hold detector with a caller-chosen delay, shorter than the platform's ~500 ms
 * long press.
 *
 * Returns true only when the finger stayed inside touch slop for [timeoutMs].
 * A lift, an outside consumer or a move past slop returns false first, so taps
 * and the surrounding scroll still win and the reorder never steals them.
 */
internal suspend fun AwaitPointerEventScope.awaitBriefHold(
    down: PointerInputChange,
    timeoutMs: Long = 220L,
): Boolean {
    val cancelled = withTimeoutOrNull(timeoutMs) {
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id }
                ?: return@withTimeoutOrNull true
            if (!change.pressed || change.isConsumed) return@withTimeoutOrNull true
            if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                return@withTimeoutOrNull true
            }
        }
        @Suppress("UNREACHABLE_CODE")
        true
    }
    return cancelled == null
}
