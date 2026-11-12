package io.furryr.file.ui.components
import io.furryr.file.ui.theme.*
import io.furryr.file.ui.screens.*
import io.furryr.file.ui.components.*
import io.furryr.file.ui.util.*
import io.furryr.file.model.*

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.FlipToBack
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.furryr.file.R

/**
 * One bottom bar, always five slots. Each mode has a fixed layout:
 * normal = Back, Forward, "+", Terminal, Parent;
 * selection = Select all, Invert, Clear, Same type, Share;
 * copy = Back, Forward, Confirm, Cancel, Parent.
 *
 * Renderers own what enters selection/copy and whether the action is enabled;
 * the shell only projects the current mode.
 */
@Composable
fun BottomToolbar(
    modifier: Modifier = Modifier,
    canGoBack: Boolean,
    canGoForward: Boolean,
    canGoParent: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onCreate: () -> Unit,
    onTerminal: () -> Unit,
    onParent: () -> Unit,
    /** Revealed pixels while the history drawer is dragged up from the bar. */
    onHistoryDrag: (Float) -> Unit = {},
    /** Final revealed pixels when the drag ends; the caller decides to open. */
    onHistoryDragEnd: (Float) -> Unit = {},
    canOpenTerminal: Boolean = true,
    canCreate: Boolean = true,
    selectionMode: Boolean = false,
    onSelectAll: () -> Unit = {},
    onInvertSelection: () -> Unit = {},
    onCancelSelection: () -> Unit = {},
    onSelectSameType: () -> Unit = {},
    onShare: () -> Unit = {},
    canShare: Boolean = true,
    copyActive: Boolean = false,
    canConfirmCopyMove: Boolean = true,
    onCopyMoveConfirm: () -> Unit = {},
    onCopyMoveCancel: () -> Unit = {},
) {
    Column(modifier = modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        HorizontalDivider()
        Row(
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
        ) {
            if (selectionMode && !copyActive) {
                IconButton(onClick = onSelectAll) {
                    Icon(Icons.Default.SelectAll, contentDescription = stringResource(R.string.menu_select_all))
                }
            } else {
                IconButton(enabled = canGoBack, onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource(R.string.cd_back))
                }
            }
            if (selectionMode && !copyActive) {
                IconButton(onClick = onInvertSelection) {
                    Icon(Icons.Default.FlipToBack, contentDescription = stringResource(R.string.menu_invert_selection))
                }
            } else {
                IconButton(enabled = canGoForward, onClick = onForward) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.cd_forward))
                }
            }
            when {
                copyActive -> IconButton(enabled = canConfirmCopyMove, onClick = onCopyMoveConfirm) {
                    Icon(Icons.Default.Done, contentDescription = stringResource(R.string.cd_confirm))
                }
                selectionMode -> IconButton(onClick = onCancelSelection) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_cancel_selection))
                }
                else -> IconButton(enabled = canCreate, onClick = onCreate) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.cd_create_file))
                }
            }
            when {
                copyActive -> IconButton(onClick = onCopyMoveCancel) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_cancel))
                }
                selectionMode -> IconButton(onClick = onSelectSameType) {
                    Icon(Icons.Default.Flip, contentDescription = stringResource(R.string.menu_select_same_type))
                }
                else -> IconButton(enabled = canOpenTerminal, onClick = onTerminal) {
                    Icon(Icons.Default.Terminal, contentDescription = stringResource(R.string.cd_terminal))
                }
            }
            if (selectionMode && !copyActive) {
                IconButton(enabled = canShare, onClick = onShare) {
                    Icon(Icons.Default.Share, contentDescription = stringResource(R.string.share))
                }
            } else {
                IconButton(
                    enabled = canGoParent,
                    onClick = onParent,
                    modifier = Modifier.pointerInput(Unit) {
                        var revealed = 0f
                        detectVerticalDragGestures(
                            onDragStart = { revealed = 0f },
                            onVerticalDrag = { change, amount ->
                                revealed = (revealed - amount).coerceAtLeast(0f)
                                change.consume()
                                onHistoryDrag(revealed)
                            },
                            onDragEnd = {
                                onHistoryDragEnd(revealed)
                                revealed = 0f
                            },
                            onDragCancel = {
                                onHistoryDragEnd(0f)
                                revealed = 0f
                            },
                        )
                    },
                ) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = stringResource(R.string.cd_parent))
                }
            }
        }
    }
}
