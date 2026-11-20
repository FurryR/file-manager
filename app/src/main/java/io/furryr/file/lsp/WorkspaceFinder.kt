package io.furryr.file.lsp

import io.furryr.file.core.VFile
import io.furryr.file.core.VFolder
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap

/**
 * Finds a workspace root by walking the node graph upward.
 *
 * Only directory entry names are read. A marker can be a file or a directory
 * (`.git` is often a file in a worktree). The walk never leaves the innermost
 * `!` view: a project inside `a.zip!/proj` must not find `a.zip`'s parent as
 * its root.
 */
internal class WorkspaceFinder {
    private data class Names(val value: Set<String>, val at: Long)

    private val cache = ConcurrentHashMap<String, Names>()

    suspend fun find(
        file: VFile,
        markers: Set<String>,
        mode: LspRootMode,
    ): VFolder? {
        val start = file.parent ?: return null
        var cursor: VFolder? = start
        val view = start.path.views
        var nearest: VFolder? = null
        var outermost: VFolder? = null
        var explicit: VFolder? = null
        var depth = 0

        while (depth++ < MAX_DEPTH) {
            val current = cursor ?: break
            if (current.path.views != view) break
            val names = names(current, markers) ?: break
            if (LspCatalog.ROOT_MARKER in names) {
                explicit = current
                break
            }
            if (names.any { it in markers }) {
                if (nearest == null) nearest = current
                outermost = current
            }
            cursor = current.parent
        }

        val root = explicit ?: when (mode) {
            LspRootMode.OUTERMOST -> outermost
            LspRootMode.NEAREST -> nearest
        } ?: return null

        // A provider root is never an implicit project root: it can mean the
        // whole device or the whole container. An explicit .lsp-root wins.
        if (explicit == null && !safe(root)) return null
        return root
    }

    private fun safe(folder: VFolder): Boolean {
        val path = folder.path
        if (path.isRoot || path.segmentList.isEmpty()) return false
        // A container/SAF root is one segment below a scheme root. Do not index
        // the whole container just because a marker happens to live there. A
        // host root (`ssh://host/project`) is a normal workspace.
        val parent = path.parent()
        if (path.scheme != null && parent != null && parent.host == null && parent.isRoot) return false
        return true
    }

    private suspend fun names(folder: VFolder, markers: Set<String>): Set<String>? {
        val key = folder.path.toString() + '\u0000' + markers.sorted().joinToString("\u0000")
        val now = System.currentTimeMillis()
        cache[key]?.takeIf { now - it.at < TTL_MS }?.let { return it.value }
        val listed = try {
            folder.list().getOrNull() ?: return null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
        val value = listed.asSequence().map { it.name }.toSet()
        cache[key] = Names(value, now)
        return value
    }

    private companion object {
        const val MAX_DEPTH = 32
        const val TTL_MS = 5 * 60 * 1000L
    }
}
