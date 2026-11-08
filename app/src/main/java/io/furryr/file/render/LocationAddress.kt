package io.furryr.file.render

import io.furryr.file.core.NodeRef
import io.furryr.file.core.NodeService
import io.furryr.file.core.Path
import io.furryr.file.core.VNode
import io.furryr.file.core.ViewRef
import io.furryr.file.core.Vfs
import io.furryr.file.ui.util.BrowseAddress

/**
 * Text form of any [ViewRef], used by the address bar.
 *
 * Provider paths read as plain paths: `/storage/a.zip/docs`. [resolvePath]
 * locates the Vfs boundary by walking from the end to the first existing
 * ancestor, so the common archive case costs one extra stat.
 *
 * URLs keep `root!/inner` because a URL's own slashes make the boundary
 * ambiguous without a probe. [ViewRef.Session] has no location and therefore
 * no text.
 *
 * This class does not know about zip, tar or any other format;
 * [io.furryr.file.DefaultNodeService] decides how the root opens.
 */
object LocationAddress {
    const val SEPARATOR = "!/"

    /**
     * A typed locator before it is resolved into a node. The view id is
     * intentionally generic: the registered openers, not the address text,
     * decide how the root file is entered.
     */
    data class Locator(
        val root: NodeRef,
        val path: List<String>,
    ) {
        val ref: ViewRef get() = ViewRef.Node(root, viewId = AUTO_VIEW, path = path)
    }

    private const val AUTO_VIEW = "auto"

    fun format(ref: ViewRef): String? = when (ref) {
        is ViewRef.Node -> {
            val root = when (val base = ref.root) {
                is NodeRef.Vfs -> base.uri
                is NodeRef.Url -> base.url
            }
            when {
                ref.path.isEmpty() -> root
                // A URL's own slashes make the boundary ambiguous, so keep
                // `!/`. Provider paths are plain paths.
                ref.root is NodeRef.Url -> root + SEPARATOR + ref.path.joinToString("/")
                root.endsWith("/") -> root + ref.path.joinToString("/")
                else -> root + "/" + ref.path.joinToString("/")
            }
        }
        is ViewRef.Url -> ref.url
        is ViewRef.Session -> null
    }

    /**
     * Resolve a plain Vfs address whose full path does not exist. Walk from the
     * end toward the root and stop at the first existing ancestor:
     *
     *     /storage/a.zip/docs
     *       /storage/a.zip       file  -> open and child("docs")
     *       /storage             dir   -> the path is genuinely missing
     *
     * An existing directory is decisive: a shorter prefix cannot be a file,
     * because a directory cannot live under a file. So a typical archive path
     * costs one extra stat (the parent) after the caller's full-path stat.
     * [NodeService] performs the opener descent; this method only locates the
     * Vfs boundary.
     */
    suspend fun resolvePath(service: NodeService, text: String): VNode? {
        val full = Path.parse(text)
        // Bare relative input is a search term, not a path.
        if (full.scheme == null && !text.startsWith('/')) return null
        var candidate: Path? = full.parent()
        while (candidate != null) {
            val stat = Vfs.stat(candidate.toString()).getOrNull()
            if (stat != null && stat.exists) {
                if (stat.isDirectory) return null
                val file = Vfs.file(candidate.toString()).getOrNull() ?: return null
                val inner = full.segmentList.drop(candidate.segmentList.size)
                if (inner.isEmpty()) return null
                return service.resolveFrom(file, inner).getOrNull()
            }
            candidate = candidate.parent()
        }
        return null
    }

    /**
     * True when [text] is an absolute Vfs path or carries a parseable scheme.
     * A miss with this shape is a real filesystem miss, not a search query, so
     * the caller should navigate to it and let the directory error state
     * render instead of falling back to a web search.
     */
    fun isVfsPath(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.startsWith('/')) return true
        return runCatching { Path.parse(trimmed).scheme != null }.getOrDefault(false)
    }

    /**
     * Split `root!/inner`. Returns null when the text is not a container
     * locator, so callers keep the plain path/URL behaviour. The first
     * separator wins; later ones are ordinary path segments because the flat
     * [ViewRef.Node.path] already represents nested containers.
     */
    fun parse(text: String): Locator? {
        val at = text.indexOf(SEPARATOR)
        if (at <= 0 || at + SEPARATOR.length >= text.length) return null
        val rootText = text.substring(0, at)
        val segments = text.substring(at + SEPARATOR.length)
            .split('/')
            .filter { it.isNotEmpty() }
        if (segments.isEmpty()) return null
        val root = if (BrowseAddress.isWebUrl(rootText)) {
            NodeRef.Url(rootText)
        } else {
            NodeRef.Vfs(rootText)
        }
        return Locator(root, segments)
    }
}
