package io.furryr.file.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import io.furryr.file.clipboard.ClipboardStore
import io.furryr.file.model.FileTab
import io.furryr.file.render.RendererBase
import io.furryr.file.ui.components.BottomToolbar

/**
 * Bottom toolbar: navigation, selection and copy/move confirm. Selection
 * facts come from the active renderer's [SelectCapabilities]; the shell supplies
 * only the navigation capabilities and the page-drag gesture.
 */
@Composable
internal fun ShellBottomBar(
    tab: FileTab,
    renderer: RendererBase<*>?,
    canGoBack: Boolean,
    canGoForward: Boolean,
    canGoParent: Boolean,
    canOpenTerminal: Boolean,
    pagesVisible: Boolean,
    onHeight: (Int) -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onParent: () -> Unit,
    onTerminal: () -> Unit,
    onPagesDrag: (Float) -> Unit,
    onPagesDragEnd: (Float) -> Unit,
    onShare: () -> Unit,
    onCopyMoveConfirm: () -> Unit,
    onCopyMoveCancel: () -> Unit,
) {
    val clipboard = ClipboardStore.state
    val selection = renderer?.capabilities?.select
    val selectionMode = !clipboard.inTransfer &&
        selection != null && selection.selectedPaths.isNotEmpty()
    val canShare = !clipboard.inTransfer && clipboard.entries.isNotEmpty() &&
        clipboard.entries.all { it.node is io.furryr.file.core.VFile }
    val copyActive = clipboard.inTransfer
    val canConfirmCopyMove = copyActive &&
        renderer?.clipboardSink?.accepts(clipboard) == true
    BottomToolbar(
        modifier = Modifier.onGloballyPositioned {
            onHeight(it.size.height)
        },
        canGoBack = canGoBack,
        canGoForward = canGoForward,
        canGoParent = canGoParent,
        onBack = onBack,
        onForward = onForward,
        onCreate = { renderer?.capabilities?.create?.invoke() },
        canCreate = renderer?.capabilities?.create != null,
        onTerminal = onTerminal,
        onParent = onParent,
        onPagesDrag = { if (!pagesVisible) onPagesDrag(it) },
        onPagesDragEnd = onPagesDragEnd,
        canOpenTerminal = canOpenTerminal,
        selectionMode = selectionMode,
        onSelectAll = { selection?.selectAll() },
        onInvertSelection = { selection?.invertSelection() },
        onCancelSelection = { selection?.clearSelection() },
        onSelectSameType = { selection?.selectSameType() },
        onShare = onShare,
        canShare = canShare,
        copyActive = copyActive,
        canConfirmCopyMove = canConfirmCopyMove,
        onCopyMoveConfirm = onCopyMoveConfirm,
        onCopyMoveCancel = onCopyMoveCancel,
    )
}
