package io.furryr.file.render

import io.furryr.file.core.ViewRef
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
 * A document projection. HTML files and HTTP URLs both implement this, so a
 * WebRenderer only sees a document, never a Vfs path or a URL scheme.
 */
interface DocumentView {
    val origin: ViewRef
    val name: String

    /** relative == null means the main document itself. */
    suspend fun open(relative: String? = null): Result<DocumentResource>
}
