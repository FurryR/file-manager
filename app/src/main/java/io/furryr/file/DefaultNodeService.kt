package io.furryr.file

import io.furryr.file.core.NodeService
import io.furryr.file.core.Path
import io.furryr.file.core.VFile
import io.furryr.file.core.VFolder
import io.furryr.file.core.VNode
import io.furryr.file.core.Vfs
import io.furryr.file.opener.Openers

/**
 * Resolves an address into a node.
 *
 * The part before the first `!` is one provider lookup — `https://` is just
 * another registered scheme — and each `!` level after it is entered through the
 * opener registry. Because the boundaries are explicit in the address, nothing
 * is guessed: a segment is only ever resolved inside the view that owns it, and
 * a middle "segment that happens to be a file" is no longer silently entered.
 */
class DefaultNodeService : NodeService {
    override suspend fun resolve(path: Path): Result<VNode> = runCatching {
        var node = root(path.providerPath())
        for (view in path.views) {
            val parent = when (node) {
                is VFolder -> node
                is VFile -> openView(node)
                else -> error("unsupported node: ${node.path}")
            }
            node = resolveFrom(parent, view).getOrThrow()
        }
        node
    }

    override suspend fun resolveFrom(root: VNode, path: List<String>): Result<VNode> = runCatching {
        var node = root
        for (segment in path) {
            val folder = node as? VFolder
                ?: error("not a folder while resolving '$segment': ${node.path}")
            node = folder.child(segment).getOrThrow()
        }
        node
    }

    /** The provider-visible address: one [Vfs] lookup, whatever the scheme is. */
    private suspend fun root(path: Path): VNode = Vfs.node(path).getOrThrow()

    /** Enter whatever view the registered openers offer for [node]. */
    private suspend fun openView(node: VNode): VFolder {
        val file = node as? VFile ?: error("view requires a file: ${node.path}")
        val content = file.describe().getOrThrow()
        return Openers.openFolder(file, content).getOrThrow()
    }
}
