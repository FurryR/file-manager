package io.furryr.file.core

/**
 * Turns one file into a folder view (a container, an extracted document, ...).
 * It is not a renderer: it draws nothing and owns no history. Core provides the
 * route and the registry; concrete formats live outside this module.
 */
interface ViewOpener {
    val id: String
    fun supports(file: VFile, content: ContentInfo): Boolean
    suspend fun open(file: VFile): Result<VFolder>
}

/**
 * Process-wide view-opener registry, ordered by priority. [Vfs.node] asks for
 * candidates in order and takes the first that supports the file, so adding a
 * format never changes core.
 */
object ViewOpeners {
    private val entries = mutableListOf<Pair<Int, ViewOpener>>()

    @Synchronized
    fun register(priority: Int, opener: ViewOpener) {
        entries.removeAll { it.second.id == opener.id }
        entries += priority to opener
        entries.sortByDescending { it.first }
    }

    @Synchronized
    fun unregister(id: String) {
        entries.removeAll { it.second.id == id }
    }

    fun resolve(file: VFile, content: ContentInfo): ViewOpener? = synchronized(this) {
        entries.asSequence()
            .map { it.second }
            .firstOrNull { it.supports(file, content) }
    }

    /** Structural entry used by the view walker: the file becomes a folder. */
    suspend fun enter(file: VFile, opener: ViewOpener): Result<VFolder> = runCatching {
        opener.open(file).getOrThrow()
    }
}
