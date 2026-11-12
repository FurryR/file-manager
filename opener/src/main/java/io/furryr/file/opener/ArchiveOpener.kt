package io.furryr.file.opener

import io.furryr.file.core.ContentInfo
import io.furryr.file.core.ContentKind
import io.furryr.file.core.VFile
import io.furryr.file.core.VFolder
import io.furryr.file.core.ViewOpener
import java.io.File

/**
 * Opens zip-compatible files as a container root. This is an opener, not a
 * renderer: it draws nothing and never touches navigation history.
 */
class ArchiveOpener(private val stagingDir: File) : ViewOpener {
    override val id: String = "archive"

    override fun supports(file: VFile, content: ContentInfo): Boolean {
        // An APK is a zip, but browsing it is never what the user meant: it
        // opens with the package installer instead.
        if (content.name.substringAfterLast('.', "").lowercase() == "apk") return false
        if (content.kind != ContentKind.ARCHIVE) return false
        val mime = content.mime?.lowercase()
        if (mime == "application/zip") return true
        val extension = content.name.substringAfterLast('.', "").lowercase()
        return extension in zipExtensions
    }

    override suspend fun open(file: VFile): Result<VFolder> = Archives.open(stagingDir, file)

    private companion object {
        val zipExtensions = setOf("zip", "jar", "epub")
    }
}
