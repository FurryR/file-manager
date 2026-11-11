package io.furryr.file.render

import io.furryr.file.core.Path
import java.io.InputStream

/** One resource read from a document (HTML page, script, image, ...). */
class DocumentResource(
    val mime: String?,
    val encoding: String?,
    private val stream: InputStream,
) {
    fun openStream(): InputStream = stream
}

/**
 * One HTML document, projected from a file node.
 *
 * A document is either *projected* — the bytes come from a provider path or an
 * archive view, and [open] serves every sibling resource through the same node
 * graph — or *live*: the bytes come from a network endpoint that the platform
 * renderer must load itself, so [remoteBase] is set and relative references are
 * resolved by the platform against that address. The renderer asks the
 * document, never a URL scheme.
 */
interface DocumentView {
    /** Address of the document itself (provider path plus any view levels). */
    val origin: Path
    val name: String

    /**
     * Absolute address the renderer must load itself, or null when this
     * document is projected through [open]. Non-null means the document's own
     * origin answers for its subresources.
     */
    val remoteBase: String?

    /** relative == null means the main document itself. */
    suspend fun open(relative: String? = null): Result<DocumentResource>
}
