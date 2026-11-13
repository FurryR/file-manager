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

    override suspend fun listWithNodes(): Result<Pair<List<FileEntry>, List<VNode>>> =
        list().map { entries -> entries to entries.map(::nodeFor) }

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

    override suspend fun child(name: String): Result<VNode> = runCatchingCancellable {
        requireValidName(name)
        val childPath = path.child(name)
        val stat = provider.stat(innerPathFor(childPath)).getOrThrow()
        nodeAt(childPath, stat.isDirectory)
    }

    override suspend fun folder(name: String): Result<VFolder> = child(name).mapCatching { node ->
        node as? VFolder ?: error("not a folder: $name")
    }

    override suspend fun file(name: String): Result<VFile> = child(name).mapCatching { node ->
        node as? VFile ?: error("not a file: $name")
    }

    override suspend fun create(name: String): Result<VFile> = runCatchingCancellable {
        requireValidName(name)
        val childPath = path.child(name)
        val childInner = innerPathFor(childPath)
        provider.createFile(childInner).getOrThrow()
        File(childPath, provider, childInner)
    }

    override suspend fun mkdir(name: String): Result<VFolder> = runCatchingCancellable {
        requireValidName(name)
        val childPath = path.child(name)
        val childInner = innerPathFor(childPath)
        provider.createDirectory(childInner).getOrThrow()
        Folder(childPath, provider, childInner)
    }

    override suspend fun rename(newName: String): Result<VNode> = runCatchingCancellable {
        requireValidName(newName)
        val target = path.parent()?.child(newName)
            ?: error("cannot rename a root folder: ${path.toFullUri()}")
        provider.rename(innerPath, newName).getOrThrow()
        Folder(target, provider, innerPathFor(target))
    }

    override suspend fun delete(): Result<Unit> = provider.delete(innerPath)

    private fun nodeFor(entry: FileEntry): VNode = nodeAt(path.child(entry.name), entry.isDirectory)

    private fun nodeAt(childPath: Path, isDirectory: Boolean): VNode {
        val childInner = innerPathFor(childPath)
        return if (isDirectory) Folder(childPath, provider, childInner) else File(childPath, provider, childInner)
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
    // Internal, not private: NodeOps.copyTo/moveTo hand the provider pair straight
    // to the provider copy without ever formatting a path.
    internal val provider: FileProvider,
    internal val innerPath: String,
) : VFile {
    override val name: String get() = path.name ?: provider.label

    override val parent: VFolder?
        get() = path.parent()?.let { Folder(it, provider, innerPathFor(it)) }

    override val writable: Boolean get() = provider.writable

    override suspend fun stat(): Result<ProviderStat> = provider.stat(innerPath)

    override suspend fun open(mode: OpenMode): Result<FileSource> = provider.open(innerPath, mode)

    override suspend fun describe(): Result<ContentInfo> = runCatchingCancellable {
        val stat = stat().getOrNull()
        val source = if ((stat?.size ?: 0L) > 0L) open(OpenMode.READ).getOrNull() else null
        try {
            ContentTypes.describe(name, stat?.size ?: 0L, source)
        } finally {
            source?.close()
        }
    }

    override suspend fun rename(newName: String): Result<VNode> = runCatchingCancellable {
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
