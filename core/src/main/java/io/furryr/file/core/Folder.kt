package io.furryr.file.core

import io.furryr.file.core.model.FileEntry
import io.furryr.file.core.model.ProviderStat
import io.furryr.file.core.source.FileSource
import io.furryr.file.core.source.OpenMode

/**
 * Abstract directory node returned by [Vfs.folder]. It hides the concrete
 * provider while exposing the read-side filesystem interface used by the UI.
 */
class Folder internal constructor(
    val path: Path,
    private val provider: FileProvider,
    private val innerPath: String,
) {
    suspend fun list(): Result<List<FileEntry>> = provider.list(innerPath)

    suspend fun stat(): Result<ProviderStat> = provider.stat(innerPath)

    suspend fun capabilities(): DirectoryCapabilities = provider.directoryCapabilities(innerPath)

    suspend fun info(entries: List<FileEntry>): DirectoryStats {
        val stat = stat().getOrNull()
        return DirectoryStats(
            folderCount = entries.count { it.isDirectory },
            fileCount = entries.count { !it.isDirectory },
            usableBytes = stat?.usableBytes ?: 0,
            totalBytes = stat?.totalBytes ?: 0,
        )
    }

    /** Resolve a child file/directory by name using the canonical path. */
    suspend fun child(name: String): Result<Node> = Vfs.node(path.child(name).toString())
}

/** Abstract file node returned by [Vfs.file] / [Folder.child]. */
class File internal constructor(
    val path: Path,
    private val provider: FileProvider,
    private val innerPath: String,
) {
    suspend fun stat(): Result<ProviderStat> = provider.stat(innerPath)

    suspend fun open(mode: OpenMode): Result<FileSource> = provider.open(innerPath, mode)
}

sealed interface Node {
    val path: Path

    data class Directory(val folder: Folder) : Node {
        override val path: Path get() = folder.path
    }

    data class Entry(val file: File) : Node {
        override val path: Path get() = file.path
    }
}
