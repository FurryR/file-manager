package io.furryr.file

import io.furryr.file.core.NodeRef
import io.furryr.file.core.NodeService
import io.furryr.file.opener.Openers
import io.furryr.file.core.VFile
import io.furryr.file.core.VFolder
import io.furryr.file.core.VNode
import io.furryr.file.core.ViewRef
import io.furryr.file.core.Vfs
import io.furryr.file.render.WebUrlFile

/**
 * Resolves flattened [ViewRef] chains for history restore, agent tools and
 * share targets. Only this class and [Vfs] know how a reference becomes a
 * node. Container segments may cross archive files; when a file has more path
 * segments after it, the resolver enters it as a container automatically.
 */
class DefaultNodeService : NodeService {
    override suspend fun resolve(ref: ViewRef): Result<VNode> = runCatching {
        when (ref) {
            is ViewRef.Node -> {
                var node: VNode = when (val root = ref.root) {
                    is NodeRef.Vfs -> Vfs.node(root.uri).getOrThrow()
                    is NodeRef.Url -> WebUrlFile(root.url)
                }
                if (ref.viewId != "vfs") node = openView(node)
                resolveFrom(node, ref.path).getOrThrow()
            }
            is ViewRef.Url -> error("URL locations are owned by a renderer, not Vfs")
            is ViewRef.Session -> error("session locations are owned by a service")
        }
    }

    override suspend fun resolveFrom(root: VNode, path: List<String>): Result<VNode> = runCatching {
        var node = root
        for (segment in path) {
            val folder = when (val current = node) {
                is VFolder -> current
                is VFile -> openView(current)
                else -> error("not a folder while resolving: ${current.name}")
            }
            node = folder.child(segment).getOrThrow()
        }
        node
    }

    /**
     * Enter whatever view the registered openers offer for [node]. The opener
     * module owns the folder route; this service owns the policy that an
     * intermediate file with more segments is entered automatically.
     */
    private suspend fun openView(node: VNode): VFolder {
        val file = node as? VFile ?: error("view requires a file: ${node.name}")
        val content = file.describe().getOrThrow()
        return Openers.openFolder(file, content).getOrThrow()
    }
}
