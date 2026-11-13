package io.furryr.file.render

import io.furryr.file.core.ContentTypes
import io.furryr.file.core.Path
import io.furryr.file.core.VFile
import io.furryr.file.core.VFolder
import io.furryr.file.core.VNode
import io.furryr.file.core.Vfs
import io.furryr.file.core.source.OpenMode
import java.net.URI

/**
 * HTML file projected as a document.
 *
 * A local or archived page resolves relative resources through the same node
 * graph, so a page inside a zip (or zip-in-zip) can load its sibling css/js
 * without extracting the archive. A page whose bytes live at a network endpoint
 * ([VFile.liveAddress]) instead reports that endpoint as [remoteBase]: the platform
 * renderer loads the address itself and resolves the page's references against
 * the origin, exactly like a browser.
 */
class HtmlDocument(
    val file: VFile,
) : DocumentView {
    override val origin: Path get() = file.path
    override val name: String get() = file.name
    override val remoteBase: String? get() = file.liveAddress

    override suspend fun open(relative: String?): Result<DocumentResource> = runCatching {
        val live = file.liveAddress
        val target = if (relative.isNullOrBlank()) {
            file
        } else if (live != null) {
            // A live document answers for its own tree: resolve the reference
            // against the address and fetch it as a node like any other file.
            val url = runCatching { URI(live).resolve(relative).toString() }
                .getOrElse { live }
            Vfs.node(url).getOrThrow() as? VFile
                ?: error("not a file: $relative")
        } else {
            locate(relative)
        }
        val source = target.open(OpenMode.READ).getOrThrow()
        val info = target.describe().getOrNull()
        DocumentResource(
            mime = info?.mime ?: ContentTypes.fromName(target.name),
            encoding = "utf-8",
            stream = FileSourceInputStream(source),
        )
    }

    /** Resolve [relative] inside the file's own folder graph. */
    private suspend fun locate(relative: String): VFile {
        val clean = relative.substringBefore('?').substringBefore('#')
        val absolute = clean.startsWith('/')
        val segments = clean.trimStart('/').split('/').filter { it.isNotEmpty() }
        var node: VNode = if (absolute) {
            if (file.path.isViewed) {
                // An absolute reference inside an archive starts at that
                // archive's root, never at the app filesystem root.
                var current: VNode = file
                while (true) {
                    val parent = current.parent ?: break
                    if (parent.path.views.size < file.path.views.size) break
                    current = parent
                }
                current as? VFolder ?: error("html file has no view root: ${file.name}")
            } else {
                Vfs.folder(file.path.providerPath().root).getOrThrow()
            }
        } else {
            file.parent ?: error("html file has no parent folder: ${file.name}")
        }
        for (raw in segments) {
            val segment = android.net.Uri.decode(raw)
            when (segment) {
                ".", "" -> Unit
                ".." -> {
                    node = (node as? VFolder)?.parent
                        ?: error("cannot resolve parent of ${file.name}")
                }
                else -> {
                    val folder = node as? VFolder
                        ?: error("not a folder while resolving '$relative'")
                    node = folder.child(segment).getOrThrow()
                }
            }
        }
        return node as? VFile ?: error("not a file: $relative")
    }
}
