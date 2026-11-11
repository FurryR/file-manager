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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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

@Composable
fun BottomToolbar(
    modifier: Modifier = Modifier,
    canGoBack: Boolean,
    canGoForward: Boolean,
    canGoParent: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onCreateFile: () -> Unit,
    onTerminal: () -> Unit,
    onParent: () -> Unit,
    copyMoveActive: Boolean = false,
    canConfirmCopyMove: Boolean = true,
    onCopyMoveConfirm: () -> Unit = {},
    onCopyMoveCancel: () -> Unit = {},
    selectionMode: Boolean = false,
    onSelectAll: () -> Unit = {},
    onInvertSelection: () -> Unit = {},
    onSelectSameType: () -> Unit = {},
    onCancelSelection: () -> Unit = {},
    onShare: () -> Unit = {},
    /** Revealed pixels while the history drawer is dragged up from the bar. */
    onHistoryDrag: (Float) -> Unit = {},
    /** Final revealed pixels when the drag ends; the caller decides to open. */
    onHistoryDragEnd: (Float) -> Unit = {},
    canShare: Boolean = true,
    canOpenTerminal: Boolean = true,
    canCreate: Boolean = true
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
            IconButton(enabled = selectionMode || canGoBack, onClick = if (selectionMode) onSelectAll else onBack) {
                Icon(if (selectionMode) Icons.Default.SelectAll else Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource(R.string.cd_back))
            }
            IconButton(enabled = selectionMode || canGoForward, onClick = if (selectionMode) onInvertSelection else onForward) {
                Icon(if (selectionMode) Icons.Default.FlipToBack else Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.cd_forward))
            }
            if (copyMoveActive) {
                IconButton(enabled = canConfirmCopyMove, onClick = onCopyMoveConfirm) {
                    Icon(Icons.Default.Done, contentDescription = stringResource(R.string.cd_confirm))
                }
            } else if (selectionMode) {
                IconButton(onClick = onCancelSelection) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_cancel_selection))
                }
            } else {
                IconButton(enabled = canCreate, onClick = onCreateFile) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.cd_create_file))
                }
            }
            if (copyMoveActive) {
                IconButton(onClick = onCopyMoveCancel) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_cancel))
                }
            } else if (selectionMode) {
                IconButton(onClick = onSelectSameType) {
                    Icon(Icons.Default.Flip, contentDescription = stringResource(R.string.cd_select_same_type))
                }
            } else {
                IconButton(enabled = canOpenTerminal, onClick = onTerminal) {
                    Icon(Icons.Default.Terminal, contentDescription = stringResource(R.string.cd_terminal))
                }
            }
            if (selectionMode) {
                IconButton(enabled = canShare, onClick = onShare) {
                    Icon(Icons.Default.Share, contentDescription = stringResource(R.string.cd_share))
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
