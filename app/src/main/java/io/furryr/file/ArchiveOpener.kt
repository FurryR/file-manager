package io.furryr.file

import io.furryr.file.core.FileWorld
import io.furryr.file.core.OpenIcon
import io.furryr.file.core.OpenOption
import io.furryr.file.core.VFile
import io.furryr.file.proxy.ArchiveProxy
import io.furryr.file.render.FolderPageState
import io.furryr.file.render.FolderRenderer

/**
 * The archive opener: one generic-chooser method that asks [ArchiveProxy] for
 * the structural view and hands the resulting folder renderer back to the
 * opener. It is not part of [Opener]'s MIME routing and has no dependency on
 * the proxy registry's auto-open rules.
 */
internal class ArchiveOpener(
    private val files: FileWorld,
    private val archive: ArchiveProxy,
    label: String,
) : Factory {
    override val option: OpenOption =
        OpenOption(id = "archive", label = label, icon = OpenIcon.ARCHIVE)

    override suspend fun create(file: VFile): Result<Renderer> =
        archive.open(file).map { folder ->
            FolderRenderer(files.vfs, initialState = FolderPageState(folder))
        }
}
