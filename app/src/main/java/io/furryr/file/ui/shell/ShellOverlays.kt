package io.furryr.file.ui.shell

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import io.furryr.file.R
import io.furryr.file.core.model.FileEntry
import io.furryr.file.isHomePath
import io.furryr.file.model.FileTab
import io.furryr.file.model.OperationProgress
import io.furryr.file.render.TerminalFactory
import io.furryr.file.shell.LeavePrompt
import io.furryr.file.terminal.TerminalSheet
import io.furryr.file.terminal.TerminalTab
import io.furryr.file.ui.components.EntryInfoDialog
import io.furryr.file.ui.components.OperationProgressDialog
import io.furryr.file.ui.components.PageRow
import io.furryr.file.ui.components.PageSheet
import io.furryr.file.ui.components.SessionManagerDialog

/** Per-tab page panel, with the Home landing marker hidden from its rows. */
@Composable
internal fun PageSheetOverlay(
    visible: Boolean,
    dragPx: Float,
    rows: List<PageRow>,
    bottomInset: Dp,
    onSelect: (PageRow) -> Unit,
    onDismiss: () -> Unit,
) {
    PageSheet(
        visible = visible,
        dragPx = dragPx,
        pages = rows.filterNot { isHomePath(it.page.path) },
        bottomInset = bottomInset,
        onSelect = onSelect,
        onDismiss = onDismiss,
    )
}

/** Terminal sheet whose create action is the active renderer's capability. */
@Composable
internal fun TerminalSheetOverlay(
    visible: Boolean,
    terminalFactory: TerminalFactory?,
    onDismiss: () -> Unit,
    onShow: () -> Unit,
    onDropAccepted: () -> Unit,
) {
    TerminalSheet(
        visible = visible,
        onDismiss = onDismiss,
        onShow = onShow,
        onDropAccepted = onDropAccepted,
        // The factory is the capability: its absence disables creation while
        // existing terminals stay reachable.
        newTerminal = terminalFactory?.let { factory ->
            { terminalContext: Context -> factory.create(terminalContext) }
        },
    )
}

@Composable
internal fun SessionManagerOverlay(
    visible: Boolean,
    tabs: List<FileTab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onClose: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    SessionManagerDialog(
        tabs = tabs,
        selectedIndex = selectedIndex,
        onSelect = onSelect,
        onClose = onClose,
        onMove = onMove,
        onDismiss = onDismiss,
    )
}

@Composable
internal fun ContainerErrorOverlay(
    reason: String?,
    onDismiss: () -> Unit,
) {
    if (reason == null) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.container_create_failed)) },
        text = { Text(reason) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.confirm))
            }
        },
    )
}

@Composable
internal fun EntryInfoOverlay(
    entry: FileEntry?,
    onDismiss: () -> Unit,
) {
    if (entry == null) return
    EntryInfoDialog(entry = entry, onDismiss = onDismiss)
}

@Composable
internal fun OperationProgressOverlay(
    progress: OperationProgress?,
    hidden: Boolean,
    sourceParent: String,
    targetPath: String,
    onHide: () -> Unit,
    onCancel: () -> Unit,
) {
    if (progress == null || hidden) return
    OperationProgressDialog(
        progress = progress,
        sourceParent = sourceParent,
        targetPath = targetPath,
        onHide = onHide,
        onCancel = onCancel,
    )
}

@Composable
internal fun LeavePromptOverlay(
    prompt: LeavePrompt?,
    saving: Boolean,
    modalActive: Boolean,
    onDismiss: () -> Unit,
    onCancel: () -> Unit,
    onLeave: () -> Unit,
    onSave: () -> Unit,
) {
    if (prompt == null || modalActive) return
    val save = prompt.onSave
    val canSave = prompt.canSave && save != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_unsaved)) },
        text = {
            Text(
                prompt.message?.takeIf { it.isNotBlank() }
                    ?: stringResource(
                        if (canSave) R.string.editor_unsaved_message
                        else R.string.editor_leave_message
                    )
            )
        },
        confirmButton = {
            TextButton(
                enabled = !saving,
                onClick = {
                    if (save == null || !canSave) onLeave() else onSave()
                },
            ) {
                Text(stringResource(if (canSave) R.string.editor_save else R.string.editor_leave))
            }
        },
        dismissButton = {
            if (canSave) {
                Row {
                    TextButton(enabled = !saving, onClick = onCancel) {
                        Text(stringResource(R.string.cancel))
                    }
                    TextButton(enabled = !saving, onClick = onLeave) {
                        Text(stringResource(R.string.editor_discard))
                    }
                }
            } else {
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.cancel))
                }
            }
        },
    )
}

/** Dims everything below the measured top bar while the path field is edited. */
@Composable
internal fun PathEditScrim(
    visible: Boolean,
    topBarHeightPx: Float,
) {
    if (!visible) return
    val focusManager = LocalFocusManager.current
    val scrimTop = with(LocalDensity.current) { topBarHeightPx.toDp() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = scrimTop)
            .background(Color.Black.copy(alpha = 0.32f))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ) { focusManager.clearFocus() },
    )
}
