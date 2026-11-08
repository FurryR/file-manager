package io.furryr.file.opener

import io.furryr.file.core.ContentInfo
import io.furryr.file.core.VFile
import io.furryr.file.core.VFolder
import io.furryr.file.core.VNode

/**
 * Turns one file into a different navigable node (a container view, an
 * extracted document, ...). It is not a renderer: it draws nothing and owns no
 * history. Core provides the route and the registry; concrete formats live
 * outside this module.
 */
interface Opener {
    val id: String
    fun supports(node: VNode, content: ContentInfo): Boolean
    suspend fun open(node: VNode): Result<VNode>
}

/**
 * Process-wide opener registry, ordered by priority. The resolver asks for
 * candidates in order and takes the first that supports the file, so adding a
 * format never changes core.
 */
object Openers {
    private val entries = mutableListOf<Pair<Int, Opener>>()

    @Synchronized
    fun register(priority: Int, opener: Opener) {
        entries.removeAll { it.second.id == opener.id }
        entries += priority to opener
        entries.sortByDescending { it.first }
    }

    @Synchronized
    fun unregister(id: String) {
        entries.removeAll { it.second.id == id }
    }

    @Synchronized
    fun candidates(node: VNode, content: ContentInfo): List<Opener> =
        entries.asSequence()
            .map { it.second }
            .filter { it.supports(node, content) }
            .toList()

    fun resolve(node: VNode, content: ContentInfo): Opener? =
        candidates(node, content).firstOrNull()

    /**
     * Structural entry used by a flattened view resolver: the file must become
     * a folder. There is no renderer or platform-viewer fallback here.
     */
    suspend fun openFolder(file: VFile, content: ContentInfo): Result<VFolder> = runCatching {
        val opener = resolve(file, content)
            ?: error("no opener supports ${file.name}")
        opener.open(file).getOrThrow() as? VFolder
            ?: error("opener returned a non-folder for ${file.name}")
    }
}
