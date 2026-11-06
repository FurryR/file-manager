package io.furryr.file.render

import io.furryr.file.core.ContentTypes
import io.furryr.file.core.VFile
import io.furryr.file.core.VFolder
import io.furryr.file.core.VNode
import io.furryr.file.core.ViewRef
import io.furryr.file.core.source.OpenMode

/**
 * HTML file projected as a document. Relative resources are resolved through
 * the same node graph, so a page inside a zip (or zip-in-zip) can load its
 * sibling css/js without extracting the archive.
 */
class HtmlDocument(
    val file: VFile,
) : DocumentView {
    override val origin: ViewRef = file.ref()
    override val name: String = file.name

    override suspend fun open(relative: String?): Result<DocumentResource> = runCatching {
        val target = if (relative.isNullOrBlank()) {
            file
        } else {
            val clean = relative.substringBefore('?').substringBefore('#')
            val segments = clean.trimStart('/').split('/').filter { it.isNotEmpty() }
            var node: VNode = file.parent
                ?: error("html file has no parent folder: ${file.name}")
            for (segment in segments) {
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
            node as? VFile ?: error("not a file: $relative")
        }
        val source = target.open(OpenMode.READ).getOrThrow()
        val info = target.describe().getOrNull()
        DocumentResource(
            mime = info?.mime ?: ContentTypes.fromName(target.name),
            encoding = "utf-8",
            stream = FileSourceInputStream(source),
        )
    }
}
