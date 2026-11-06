package io.furryr.file.core

import io.furryr.file.core.model.FileEntry
import io.furryr.file.core.model.ProviderStat
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
 * itself live inside another [ArchiveFolder]); [path] is relative to the
 * archive root. There is no synthetic URI scheme: identity is [ViewRef.Node] with viewId = "archive".
 */
class ArchiveFolder(
    val backing: VFile,
    val source: ArchiveSource,
    val path: List<String> = emptyList(),
) : VFolder {
    override val name: String get() = path.lastOrNull() ?: backing.name

    override val parent: VFolder?
        get() = if (path.isEmpty()) backing.parent else ArchiveFolder(backing, source, path.dropLast(1))

    override fun ref(): ViewRef.Node = (backing.ref() as ViewRef.Node).archivePath(path)

    override suspend fun list(): Result<List<FileEntry>> = listNodes().map { nodes ->
        nodes.map { node ->
            val entry = when (node) {
                is ArchiveFolder -> ArchiveEntry(node.name, true)
                is ArchiveFile -> node.entry
                else -> ArchiveEntry(node.name, false)
            }
            FileEntry(
                name = entry.name,
                path = displayPath(path + entry.name),
                isDirectory = entry.isDirectory,
                modifiedAt = entry.modifiedAt,
                size = entry.size,
            )
        }
    }

    override suspend fun listNodes(): Result<List<VNode>> =
        source.entries(path).map { entries ->
            entries.map { entry ->
                val childPath = path + entry.name
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

    override suspend fun child(name: String): Result<VNode> = runCatching {
        require(name.isNotEmpty() && name != "." && name != ".." && '/' !in name) {
            "invalid child name: $name"
        }
        val entry = source.entries(path).getOrThrow().firstOrNull { it.name == name }
            ?: error("not found: $name")
        val childPath = path + entry.name
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

    override fun toString(): String = "ArchiveFolder(${displayPath(path)})"

    private fun displayPath(segments: List<String>): String =
        "/" + segments.joinToString("/")
}

/** File inside a container. It is read-only for archive sources. */
class ArchiveFile(
    val backing: VFile,
    val source: ArchiveSource,
    val path: List<String>,
    val entry: ArchiveEntry,
) : VFile {
    override val name: String get() = entry.name

    override val parent: VFolder
        get() = ArchiveFolder(backing, source, path.dropLast(1))

    override fun ref(): ViewRef.Node = (backing.ref() as ViewRef.Node).archivePath(path)

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
        return source.open(path, mode)
    }

    override suspend fun describe(): Result<ContentInfo> = runCatching {
        val opened = open(OpenMode.READ).getOrNull()
        val size = opened?.size ?: entry.size
        val head = if (opened != null && size > 0L) {
            val length = minOf(size, ContentTypes.SNIFF_BYTES.toLong()).toInt()
            runCatching { opened.readAt(0L, length) }.getOrDefault(ByteArray(0))
        } else {
            ByteArray(0)
        }
        val seekable = opened?.seekable ?: false
        runCatching { opened?.close() }
        ContentTypes.describe(entry.name, size, head, seekable)
    }

    override fun toString(): String = "ArchiveFile(${path.joinToString("/")})"
}
