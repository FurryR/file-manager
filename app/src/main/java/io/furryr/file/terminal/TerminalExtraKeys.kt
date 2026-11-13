package io.furryr.file.terminal

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.furryr.file.R
import kotlinx.coroutines.delay
import kotlin.math.abs

/** The extra-keys row: modifiers, navigation, enter and the IME swipe gesture. */
@Composable
internal fun TerminalExtraKeys(
    inputState: TerminalInputState,
    enabled: Boolean,
    onOpenDrawer: () -> Unit,
) {
    val rows = listOf(
        listOf(
            "ESC" to KeyEvent.KEYCODE_ESCAPE, "TAB" to KeyEvent.KEYCODE_TAB,
            "PgUp" to KeyEvent.KEYCODE_PAGE_UP, "HOME" to KeyEvent.KEYCODE_MOVE_HOME,
            "\u2191" to KeyEvent.KEYCODE_DPAD_UP, "END" to KeyEvent.KEYCODE_MOVE_END,
        ),
        listOf(
            "CTRL" to KeyEvent.KEYCODE_CTRL_LEFT, "ALT" to KeyEvent.KEYCODE_ALT_LEFT,
            "PgDn" to KeyEvent.KEYCODE_PAGE_DOWN, "\u2190" to KeyEvent.KEYCODE_DPAD_LEFT,
            "\u2193" to KeyEvent.KEYCODE_DPAD_DOWN, "\u2192" to KeyEvent.KEYCODE_DPAD_RIGHT,
        ),
    )
    val imeSwipeThreshold = with(LocalDensity.current) { 24.dp.toPx() }
    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .pointerInput(inputState, enabled, imeSwipeThreshold) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(
                        requireUnconsumed = false,
                        pass = PointerEventPass.Initial,
                    )
                    val startX = down.position.x
                    val startY = down.position.y
                    while (true) {
                        val change = awaitPointerEvent(PointerEventPass.Initial)
                            .changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val dy = change.position.y - startY
                        val dx = change.position.x - startX
                        if (dy <= -imeSwipeThreshold && abs(dy) >= abs(dx)) {
                            inputState.requestIme()
                            change.consume()
                            break
                        }
                    }
                }
            }
    ) {
        rows.forEachIndexed { rowIndex, keys ->
            Row(Modifier.fillMaxWidth()) {
                keys.forEach { (label, keyCode) ->
                    val isSelected = when (keyCode) {
                        KeyEvent.KEYCODE_CTRL_LEFT -> inputState.ctrl
                        KeyEvent.KEYCODE_ALT_LEFT -> inputState.alt
                        KeyEvent.KEYCODE_SHIFT_LEFT -> inputState.shift
                        else -> false
                    }
                    val isModifier = KeyEvent.isModifierKey(keyCode)
                    val interactionSource = remember { MutableInteractionSource() }
                    val pressed by interactionSource.collectIsPressedAsState()
                    var repeated by remember { mutableStateOf(false) }

                    LaunchedEffect(pressed, enabled, isModifier) {
                        if (!pressed || !enabled || isModifier) return@LaunchedEffect
                        repeated = false
                        delay(KEY_REPEAT_DELAY_MS)
                        repeated = true
                        while (true) {
                            inputState.sendKey(keyCode)
                            delay(KEY_REPEAT_INTERVAL_MS)
                        }
                    }

                    TextButton(
                        onClick = {
                            when {
                                isModifier -> when (keyCode) {
                                    KeyEvent.KEYCODE_CTRL_LEFT -> inputState.ctrl = !inputState.ctrl
                                    KeyEvent.KEYCODE_ALT_LEFT -> inputState.alt = !inputState.alt
                                    KeyEvent.KEYCODE_SHIFT_LEFT -> inputState.shift = !inputState.shift
                                }
                                repeated -> repeated = false
                                else -> inputState.sendKey(keyCode)
                            }
                        },
                        enabled = enabled,
                        contentPadding = PaddingValues(0.dp),
                        shape = MaterialTheme.shapes.small,
                        colors = ButtonDefaults.textButtonColors(
                            containerColor = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                            contentColor = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                        ),
                        interactionSource = interactionSource,
                        modifier = Modifier.weight(1f).height(36.dp)
                            .focusProperties { canFocus = false }
                            .semantics {
                                contentDescription = label
                                if (isModifier) selected = isSelected
                            },
                    ) {
                        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    }
                }
                val enterSource = remember { MutableInteractionSource() }
                val enterPressed by enterSource.collectIsPressedAsState()
                var enterRepeated by remember { mutableStateOf(false) }

                LaunchedEffect(enterPressed, enabled, rowIndex) {
                    if (!enterPressed || !enabled || rowIndex == 0) return@LaunchedEffect
                    enterRepeated = false
                    delay(KEY_REPEAT_DELAY_MS)
                    enterRepeated = true
                    while (true) {
                        inputState.sendKey(KeyEvent.KEYCODE_ENTER)
                        delay(KEY_REPEAT_INTERVAL_MS)
                    }
                }

                TextButton(
                    onClick = {
                        when {
                            rowIndex == 0 -> onOpenDrawer()
                            enterRepeated -> enterRepeated = false
                            else -> inputState.sendKey(KeyEvent.KEYCODE_ENTER)
                        }
                    },
                    enabled = enabled,
                    contentPadding = PaddingValues(0.dp),
                    shape = MaterialTheme.shapes.small,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                    interactionSource = enterSource,
                    modifier = Modifier.weight(1f).height(36.dp)
                        .focusProperties { canFocus = false },
                ) {
                    Icon(
                        imageVector = if (rowIndex == 0) Icons.Default.Menu else Icons.AutoMirrored.Filled.KeyboardReturn,
                        modifier = Modifier.size(16.dp),
                        contentDescription = stringResource(
                            if (rowIndex == 0) R.string.terminal_drawer_title else R.string.terminal_key_enter,
                        ),
                    )
                }
            }
        }
    }
}
