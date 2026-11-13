package io.furryr.file.opener

import io.furryr.file.core.ContentInfo
import io.furryr.file.core.ContentKind
import io.furryr.file.core.ContentTypes
import io.furryr.file.core.VFile
import io.furryr.file.core.VFolder
import io.furryr.file.core.ViewOpener
import java.io.File
import java.util.Locale

/**
 * Opens zip-compatible files as a container root. This is an opener, not a
 * renderer: it draws nothing and never touches navigation history.
 */
class ArchiveOpener(private val stagingDir: File) : ViewOpener {
    override val id: String = "archive"

    override fun supports(file: VFile, content: ContentInfo): Boolean {
        // An APK is a zip, but browsing it is never what the user meant: it
        // opens with the package installer instead.
        if (content.name.substringAfterLast('.', "").lowercase(Locale.ROOT) == "apk") return false
        return content.kind == ContentKind.ARCHIVE && ContentTypes.isArchiveMime(content.mime)
    }

    override suspend fun open(file: VFile): Result<VFolder> = Archives.open(stagingDir, file)
}
