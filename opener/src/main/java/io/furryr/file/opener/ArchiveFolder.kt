package io.furryr.file.opener

import io.furryr.file.core.ContentInfo
import io.furryr.file.core.ContentTypes
import io.furryr.file.core.DirectoryCapabilities
import io.furryr.file.core.DirectoryStats
import io.furryr.file.core.Path
import io.furryr.file.core.VFile
import io.furryr.file.core.VFolder
import io.furryr.file.core.VNode
import io.furryr.file.core.model.FileEntry
import io.furryr.file.core.model.ProviderStat
import io.furryr.file.core.runCatchingCancellable
import io.furryr.file.core.source.FileSource
import io.furryr.file.core.source.OpenMode

/** One entry reported by a container index (zip, tar, ...). */
data class ArchiveEntry(
    val name: String,
    val isDirectory: Boolean,
    val size: Long = 0L,
    val modifiedAt: Long = 0L,
)

/**
 * Read-only container backend. Implementations parse one archive format and
 * expose entries relative to the archive root. They are Kotlin-side and only
 * depend on [FileSource], never on a provider or a daemon API.
 */
interface ArchiveSource {
    val label: String

    suspend fun entries(path: List<String>): Result<List<ArchiveEntry>>
    suspend fun open(path: List<String>, mode: OpenMode): Result<FileSource>
    suspend fun close()
}

/**
 * Folder inside a container. [backing] is the file that was opened (which may
 * itself live inside another [ArchiveFolder]); [segments] is relative to the
 * archive root. Identity is the backing path plus one view level, so a
 * container inside a container is simply a longer chain — and providers never
 * see any of it.
 */
class ArchiveFolder(
    val backing: VFile,
    val source: ArchiveSource,
    val segments: List<String> = emptyList(),
) : VFolder {
    override val path: Path = backing.path.enterView(segments)

    override val name: String get() = segments.lastOrNull() ?: backing.name

    override val parent: VFolder?
        get() = if (segments.isEmpty()) {
            backing.parent
        } else {
            ArchiveFolder(backing, source, segments.dropLast(1))
        }

    override suspend fun list(): Result<List<FileEntry>> = listNodes().map { nodes ->
        nodes.map(::entryOf)
    }

    override suspend fun listWithNodes(): Result<Pair<List<FileEntry>, List<VNode>>> =
        listNodes().map { nodes -> nodes.map(::entryOf) to nodes }

    private fun entryOf(node: VNode): FileEntry {
        val entry = when (node) {
            is ArchiveFolder -> ArchiveEntry(node.name, true)
            is ArchiveFile -> node.entry
            else -> ArchiveEntry(node.name, false)
        }
        return FileEntry(
            name = entry.name,
            path = node.path.toString(),
            isDirectory = entry.isDirectory,
            modifiedAt = entry.modifiedAt,
            size = entry.size,
        )
    }

    override suspend fun listNodes(): Result<List<VNode>> =
        source.entries(segments).map { entries ->
            entries.map { entry ->
                val childPath = segments + entry.name
                if (entry.isDirectory) {
                    ArchiveFolder(backing, source, childPath)
                } else {
                    ArchiveFile(backing, source, childPath, entry)
                }
            }
        }

    override suspend fun stat(): Result<ProviderStat> = Result.success(
        ProviderStat(
            exists = true,
            isDirectory = true,
            canCreateChildren = false,
            canEnter = true,
        )
    )

    override suspend fun capabilities(): DirectoryCapabilities = DirectoryCapabilities()

    override suspend fun info(): DirectoryStats = DirectoryStats()

    override suspend fun child(name: String): Result<VNode> = runCatchingCancellable {
        require(name.isNotEmpty() && name != "." && name != ".." && '/' !in name) {
            "invalid child name: $name"
        }
        val entry = source.entries(segments).getOrThrow().firstOrNull { it.name == name }
            ?: error("not found: $name")
        val childPath = segments + entry.name
        if (entry.isDirectory) {
            ArchiveFolder(backing, source, childPath)
        } else {
            ArchiveFile(backing, source, childPath, entry)
        }
    }

    override suspend fun folder(name: String): Result<VFolder> = child(name).mapCatching { node ->
        node as? VFolder ?: error("not a folder: $name")
    }

    override suspend fun file(name: String): Result<VFile> = child(name).mapCatching { node ->
        node as? VFile ?: error("not a file: $name")
    }

    override fun toString(): String = "ArchiveFolder($path)"

    private fun displayPath(segments: List<String>): String =
        "/" + segments.joinToString("/")
}

/** File inside a container. It is read-only for archive sources. */
class ArchiveFile(
    val backing: VFile,
    val source: ArchiveSource,
    val segments: List<String>,
    val entry: ArchiveEntry,
) : VFile {
    override val path: Path = backing.path.enterView(segments)

    override val name: String get() = entry.name

    override val parent: VFolder
        get() = ArchiveFolder(backing, source, segments.dropLast(1))

    override suspend fun stat(): Result<ProviderStat> = Result.success(
        ProviderStat(
            exists = true,
            isDirectory = false,
            size = entry.size,
        )
    )

    override suspend fun open(mode: OpenMode): Result<FileSource> {
        if (mode != OpenMode.READ) {
            return Result.failure(UnsupportedOperationException("archive entries are read-only"))
        }
        return source.open(segments, mode)
    }

    /** Archive entries never accept a write handle. */
    override val writable: Boolean get() = false

    override suspend fun describe(): Result<ContentInfo> = runCatchingCancellable {
        val opened = open(OpenMode.READ).getOrNull()
        try {
            ContentTypes.describe(entry.name, entry.size, opened)
        } finally {
            opened?.close()
        }
    }

    override fun toString(): String = "ArchiveFile($path)"
}
