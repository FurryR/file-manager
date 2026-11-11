package io.furryr.file.core

import io.furryr.file.core.model.FileEntry
import io.furryr.file.core.model.ProviderStat
import io.furryr.file.core.source.FileSource
import io.furryr.file.core.source.OpenMode

/**
 * Provider-backed folder node. The provider and inner path are private: the
 * rest of the application navigates through [parent] / [child] and lists
 * through [listNodes].
 */
class Folder internal constructor(
    override val path: Path,
    private val provider: FileProvider,
    private val innerPath: String,
) : VFolder {
    override val name: String get() = path.name ?: provider.label

    override val parent: VFolder?
        get() = path.parent()?.let { Folder(it, provider, innerPathFor(it)) }

    override suspend fun list(): Result<List<FileEntry>> = provider.list(innerPath)

    override suspend fun listNodes(): Result<List<VNode>> =
        list().map { entries -> entries.map(::nodeFor) }

    override suspend fun stat(): Result<ProviderStat> = provider.stat(innerPath)

    override suspend fun capabilities(): DirectoryCapabilities =
        provider.directoryCapabilities(innerPath)

    override suspend fun info(): DirectoryStats {
        val stat = stat().getOrNull()
        return DirectoryStats(
            usableBytes = stat?.usableBytes ?: 0,
            totalBytes = stat?.totalBytes ?: 0,
        )
    }

    override suspend fun child(name: String): Result<VNode> = runCatching {
        requireValidName(name)
        val childPath = path.child(name)
        val childInner = innerPathFor(childPath)
        val stat = provider.stat(childInner).getOrThrow()
        if (stat.isDirectory) {
            Folder(childPath, provider, childInner)
        } else {
            File(childPath, provider, childInner)
        }
    }

    override suspend fun folder(name: String): Result<VFolder> = child(name).mapCatching { node ->
        node as? VFolder ?: error("not a folder: $name")
    }

    override suspend fun file(name: String): Result<VFile> = child(name).mapCatching { node ->
        node as? VFile ?: error("not a file: $name")
    }

    override suspend fun create(name: String): Result<VFile> = runCatching {
        requireValidName(name)
        val childPath = path.child(name)
        val childInner = innerPathFor(childPath)
        provider.createFile(childInner).getOrThrow()
        File(childPath, provider, childInner)
    }

    override suspend fun mkdir(name: String): Result<VFolder> = runCatching {
        requireValidName(name)
        val childPath = path.child(name)
        val childInner = innerPathFor(childPath)
        provider.createDirectory(childInner).getOrThrow()
        Folder(childPath, provider, childInner)
    }

    override suspend fun rename(newName: String): Result<VNode> = runCatching {
        requireValidName(newName)
        val target = path.parent()?.child(newName)
            ?: error("cannot rename a root folder: ${path.toFullUri()}")
        provider.rename(innerPath, newName).getOrThrow()
        Folder(target, provider, innerPathFor(target))
    }

    override suspend fun delete(): Result<Unit> = provider.delete(innerPath)

    private fun nodeFor(entry: FileEntry): VNode {
        val childPath = path.child(entry.name)
        val childInner = innerPathFor(childPath)
        return if (entry.isDirectory) {
            Folder(childPath, provider, childInner)
        } else {
            File(childPath, provider, childInner)
        }
    }

    private fun innerPathFor(nodePath: Path): String =
        ResolvedProvider(provider, nodePath, nodePath.toString()).innerPath
}

/** A child name is a single segment: no separators, no traversal. */
private fun requireValidName(name: String) {
    require(name.isNotEmpty() && name != "." && name != ".." && '/' !in name) {
        "invalid child name: $name"
    }
}

/** Provider-backed file node. */
class File internal constructor(
    override val path: Path,
    // Internal, not private: Vfs.copyTo/moveTo hand the provider pair straight
    // to the provider copy without ever formatting a path.
    internal val provider: FileProvider,
    internal val innerPath: String,
) : VFile {
    override val name: String get() = path.name ?: provider.label

    override val parent: VFolder?
        get() = path.parent()?.let { Folder(it, provider, innerPathFor(it)) }

    override suspend fun stat(): Result<ProviderStat> = provider.stat(innerPath)

    override suspend fun open(mode: OpenMode): Result<FileSource> = provider.open(innerPath, mode)

    override suspend fun describe(): Result<ContentInfo> = runCatching {
        val stat = stat().getOrNull()
        val source = open(OpenMode.READ).getOrNull()
        val size = source?.size ?: stat?.size ?: 0L
        val head = if (source != null && size > 0L) {
            val length = minOf(size, ContentTypes.SNIFF_BYTES.toLong()).toInt()
            runCatching { source.readAt(0L, length) }.getOrDefault(ByteArray(0))
        } else {
            ByteArray(0)
        }
        val seekable = source?.seekable ?: false
        runCatching { source?.close() }
        ContentTypes.describe(name, size, head, seekable)
    }

    override suspend fun rename(newName: String): Result<VNode> = runCatching {
        requireValidName(newName)
        val target = path.parent()?.child(newName)
            ?: error("cannot rename a root file: ${path.toFullUri()}")
        provider.rename(innerPath, newName).getOrThrow()
        File(target, provider, innerPathFor(target))
    }

    override suspend fun delete(): Result<Unit> = provider.delete(innerPath)

    private fun innerPathFor(nodePath: Path): String =
        ResolvedProvider(provider, nodePath, nodePath.toString()).innerPath
}
