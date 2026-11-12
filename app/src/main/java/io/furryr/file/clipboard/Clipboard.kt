package io.furryr.file.clipboard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.furryr.file.core.NodeOps
import io.furryr.file.core.VFolder
import io.furryr.file.core.VNode
import io.furryr.file.core.model.CopyProgress
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * What the pending selection is for.
 *
 * [NONE] means a plain per-tab selection (share, inspect, wait for an
 * operation). [COPY]/[MOVE] mean the sources are frozen and the next step is
 * choosing a destination; these survive a tab switch.
 */
enum class ClipboardAction { NONE, COPY, MOVE }

/**
 * One source: the node itself plus the name it had when it was selected. The
 * renderer that produced it may be gone by the time a paste happens, so no
 * session or FileEntry is kept.
 */
data class ClipboardEntry(
    val node: VNode,
    val name: String = node.name,
)

/**
 * The one selection/transfer state.
 *
 * - [ClipboardAction.NONE] + entries = selection mode; the selection belongs
 *   to one tab and is cleared when that tab is left.
 * - [ClipboardAction.COPY]/[MOVE] + entries = copy/move mode; the sources are
 *   frozen, may be pasted from another tab, and selection changes are refused.
 */
data class ClipboardState(
    val action: ClipboardAction = ClipboardAction.NONE,
    val entries: List<ClipboardEntry> = emptyList(),
) {
    val inTransfer: Boolean get() = action != ClipboardAction.NONE
    val sourceParent: String get() = entries.firstOrNull()?.node?.parent?.name.orEmpty()
    val paths: Set<String> get() = entries.mapTo(HashSet(entries.size)) { it.node.path.toString() }
}

/**
 * Process-wide selection/clipboard state, next to TabSession.
 *
 * A plain selection is cleared on tab change by the shell; a copy/move payload
 * is not. Process death clears the whole state.
 */
object ClipboardStore {
    var state by mutableStateOf(ClipboardState())
        private set

    /** Update the plain selection; ignored once copy/move has frozen it. */
    fun select(entries: List<ClipboardEntry>) {
        if (state.inTransfer) return
        state = ClipboardState(ClipboardAction.NONE, entries.distinctBy { it.node.path.toString() })
    }

    /** Freeze the current selection into a cross-tab copy/move payload. */
    fun begin(entries: List<ClipboardEntry>, action: ClipboardAction) {
        require(action != ClipboardAction.NONE)
        if (entries.isEmpty()) return
        state = ClipboardState(action, entries.distinctBy { it.node.path.toString() })
    }

    fun clear() {
        state = ClipboardState()
    }
}

/**
 * A renderer that can receive a frozen copy/move payload. It answers acceptance
 * and hands the shell the folder each entry lands in; the shell owns the
 * transfer.
 */
interface ClipboardSink {
    fun accepts(state: ClipboardState): Boolean
    fun destinationFolder(): VFolder?
}

/**
 * Runs the standard copy/move list. Provider-level [NodeOps] does the byte
 * movement; progress, cancellation and completion stay with the caller.
 */
object ClipboardTransfer {
    suspend fun run(
        state: ClipboardState,
        folder: VFolder,
        onProgress: (CopyProgress) -> Unit,
    ): Result<Unit> = runCatching {
        require(state.inTransfer) { "Nothing to paste" }
        for (entry in state.entries) {
            currentCoroutineContext().ensureActive()
            val target = when (entry.node) {
                is VFolder -> folder.destinationFolder(entry.name).getOrThrow()
                else -> folder.destination(entry.name).getOrThrow()
            }
            require(target.path != entry.node.path) {
                "${entry.name} is already in the destination"
            }
            if (entry.node is VFolder) {
                val sourcePath = entry.node.path.toString().trimEnd('/')
                val targetPath = folder.path.toString()
                require(targetPath != sourcePath && !targetPath.startsWith("$sourcePath/")) {
                    "A folder cannot be moved or copied into itself"
                }
            }
            val flow = when (state.action) {
                ClipboardAction.COPY -> NodeOps.copyTo(entry.node, target)
                ClipboardAction.MOVE -> NodeOps.moveTo(entry.node, target)
                ClipboardAction.NONE -> error("Nothing to paste")
            }
            flow.collect { progress ->
                currentCoroutineContext().ensureActive()
                onProgress(progress)
            }
        }
    }
}
