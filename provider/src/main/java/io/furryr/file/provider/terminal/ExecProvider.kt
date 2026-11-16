package io.furryr.file.provider.terminal

import io.furryr.file.core.FileProvider
import io.furryr.file.core.VFolder
import io.furryr.file.core.VNode

/**
 * A provider that can start a terminal for one of its folders.
 *
 * Capability modules cast a node's owner (`VFolder.provider`) to this
 * interface. A provider that cannot host a shell simply does not implement it.
 */
interface ExecProvider : FileProvider {
    /** Start a shell at [cwd]; the provider resolves its own inner path. */
    suspend fun terminal(cwd: VFolder, rows: Int, cols: Int): Result<TerminalProcess>

    /**
     * The path to insert into the shell started at [cwd] for [node], or null
     * when the node is not addressable in that shell.
     *
     * The default accepts only nodes this provider minted. A provider whose
     * shell reaches another namespace overrides it; the host provider accepts
     * container entries, and a container provider projects host entries through
     * its own bind mounts.
     */
    fun terminalPath(cwd: VFolder, node: VNode): String? =
        if (node.provider === this) innerPath(node.path) else null
}
