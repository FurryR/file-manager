package io.furryr.file.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import io.furryr.file.model.FileTab
import io.furryr.file.render.RendererContext

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

/**
 * One entry in the shell modal stack. [content] is composed only while this
 * entry is on top; [cancel] settles the entry when its owner disappears.
 */
internal class ShellModal(
    val tabId: String? = null,
    val pageId: String? = null,
    val content: @Composable () -> Unit,
    val cancel: (Any?) -> Unit = {},
)
