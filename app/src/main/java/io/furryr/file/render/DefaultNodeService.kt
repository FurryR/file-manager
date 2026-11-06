package io.furryr.file.render

import android.content.Context
import io.furryr.file.core.NodeRef
import io.furryr.file.core.NodeService
import io.furryr.file.core.VFile
import io.furryr.file.core.VFolder
import io.furryr.file.core.VNode
import io.furryr.file.core.ViewRef
import io.furryr.file.core.Vfs

/**
 * Resolves flattened [ViewRef] chains for history restore, agent tools and
 * share targets. Only this class and [Vfs] know how a reference becomes a
 * node. Container segments may cross archive files; when a file has more path
 * segments after it, the resolver enters it as a container automatically.
 */
class DefaultNodeService(private val context: Context) : NodeService {
    override suspend fun resolve(ref: ViewRef): Result<VNode> = runCatching {
        when (ref) {
            is ViewRef.Node -> {
                val rootUri = when (val root = ref.root) {
                    is NodeRef.Vfs -> root.uri
                }
                var node: VNode = Vfs.node(rootUri).getOrThrow()
                if (ref.viewId == "archive") {
                    node = Archives.open(context, node as? VFile
                        ?: error("archive view requires a file: $rootUri")).getOrThrow()
                }
                for (segment in ref.path) {
                    val folder = when (val current = node) {
                        is VFolder -> current
                        is VFile -> Archives.open(context, current).getOrThrow()
                        else -> error("not a folder while resolving: ${current.name}")
                    }
                    node = folder.child(segment).getOrThrow()
                }
                node
            }
            is ViewRef.Url -> error("URL locations are owned by a renderer, not Vfs")
            is ViewRef.Session -> error("session locations are owned by a service")
        }
    }
}
