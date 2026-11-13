package io.furryr.file.shell

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import io.furryr.file.model.FileTab
import io.furryr.file.render.RendererContext

/**
 * One leave confirmation for every renderer. The editor supplies [onSave];
 * a WebView beforeunload prompt only supplies [onLeave].
 */
internal data class LeavePrompt(
    val ownerTabId: String,
    val ownerPageId: String,
    val message: String? = null,
    val canSave: Boolean = false,
    val onSave: (suspend () -> Result<Unit>)? = null,
    val onLeave: () -> Unit,
    val onCancel: () -> Unit = {},
)

/**
 * Tabs belong to the shell's process, not to one activity instance: changing
 * the app language recreates [MainActivity], and the open tabs must survive
 * that rebuild. Process death still starts from Home.
 */
internal object FileTabs {
    var tabs: SnapshotStateList<FileTab>? = null
    var activeId: MutableState<String?>? = null
    var pending: SnapshotStateMap<String, FileTab>? = null
}

/**
 * Shell-owned host with a session back-reference.
 *
 * Callbacks are gated by the page id: an adopted WebView moves to a new page
 * and the harness updates the bound page without knowing the session.
 */
internal interface ShellRendererContext : RendererContext {
    var boundPageId: String?
}
