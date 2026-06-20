package io.furryr.file.ui.components
import io.furryr.file.ui.theme.*
import io.furryr.file.ui.screens.*
import io.furryr.file.ui.components.*
import io.furryr.file.ui.util.*
import io.furryr.file.daemon.*
import io.furryr.file.model.*

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.FlipToBack
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun BottomToolbar(
    canGoBack: Boolean,
    canGoForward: Boolean,
    canGoParent: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onCreateFile: () -> Unit,
    onAgent: () -> Unit,
    onParent: () -> Unit,
    copyMoveActive: Boolean = false,
    onCopyMoveConfirm: () -> Unit = {},
    onCopyMoveCancel: () -> Unit = {},
    selectionMode: Boolean = false,
    onSelectAll: () -> Unit = {},
    onInvertSelection: () -> Unit = {},
    onSelectSameType: () -> Unit = {},
    onCancelSelection: () -> Unit = {},
    onShare: () -> Unit = {},
    canShare: Boolean = true
) {
    Column {
        HorizontalDivider()
        Row(
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
        ) {
            IconButton(enabled = selectionMode || canGoBack, onClick = if (selectionMode) onSelectAll else onBack) {
                Icon(if (selectionMode) Icons.Default.SelectAll else Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Back")
            }
            IconButton(enabled = selectionMode || canGoForward, onClick = if (selectionMode) onInvertSelection else onForward) {
                Icon(if (selectionMode) Icons.Default.FlipToBack else Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Forward")
            }
            if (copyMoveActive) {
                IconButton(onClick = onCopyMoveConfirm) {
                    Icon(Icons.Default.Done, contentDescription = "Confirm")
                }
            } else if (selectionMode) {
                IconButton(onClick = onCancelSelection) {
                    Icon(Icons.Default.Close, contentDescription = "Cancel selection")
                }
            } else {
                IconButton(onClick = onCreateFile) {
                    Icon(Icons.Default.Add, contentDescription = "Create file")
                }
            }
            if (copyMoveActive) {
                IconButton(onClick = onCopyMoveCancel) {
                    Icon(Icons.Default.Close, contentDescription = "Cancel")
                }
            } else if (selectionMode) {
                IconButton(onClick = onSelectSameType) {
                    Icon(Icons.Default.Flip, contentDescription = "Select same type")
                }
            } else {
                IconButton(onClick = onAgent) {
                    Icon(Icons.Default.Android, contentDescription = "Agent")
                }
            }
            if (selectionMode) {
                IconButton(enabled = canShare, onClick = onShare) {
                    Icon(Icons.Default.Share, contentDescription = "Share")
                }
            } else {
                IconButton(enabled = canGoParent, onClick = onParent) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Parent")
                }
            }
        }
    }
}
