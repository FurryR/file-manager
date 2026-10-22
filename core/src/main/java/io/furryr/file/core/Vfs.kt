package io.furryr.file.core

import io.furryr.file.core.PermissionDeniedException
import io.furryr.file.core.model.CopyProgress
import io.furryr.file.core.model.FileEntry
import io.furryr.file.core.source.FileSource
import io.furryr.file.core.source.OpenMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runBlocking

/**
 * Virtual filesystem router.
 *
 * Maps a canonical path or URI to a [FileProvider] by scheme, then dispatches
 * every filesystem operation through the provider contract. Bare paths use the
 * registered default provider. This layer is platform- and UI-independent:
 * callers (UI, hub, CLI bridges) only deal in paths and opaque providers.
 */
object Vfs {
    private val providers = mutableMapOf<String, FileProvider>()
    private var default: FileProvider? = null

    fun register(scheme: String, provider: FileProvider) {
        check(providers.putIfAbsent(scheme, provider) == null) {
            "A provider is already registered for scheme '$scheme'"
        }
    }

    fun register(provider: FileProvider) {
        register(provider.scheme, provider)
    }

    fun unregister(scheme: String) {
        providers.remove(scheme)
    }

    fun setDefault(provider: FileProvider) {
        default = provider
    }

    fun resolve(uri: String): Result<ResolvedProvider> {
        if (uri.startsWith("/")) {
            val def = default ?: return Result.failure(IllegalStateException("No default provider"))
            return Result.success(ResolvedProvider(def, Path.parse(uri), uri))
        }
        val path = runCatching { Path.parse(uri) }.getOrElse { error ->
            return Result.failure(IllegalArgumentException("Cannot parse path: $uri", error))
        }
        val scheme = path.scheme
            ?: return Result.failure(IllegalArgumentException("No scheme in URI: $uri"))
        val provider = providers[scheme]
            ?: return Result.failure(IllegalStateException("No provider for scheme '$scheme'"))
        return Result.success(ResolvedProvider(provider, path, uri))
    }

    fun folder(path: String): Result<Folder> {
        val resolved = resolve(path).getOrElse { return Result.failure(it) }
        return Result.success(Folder(resolved.path, resolved.provider, resolved.innerPath))
    }

    fun file(path: String): Result<File> {
        val resolved = resolve(path).getOrElse { return Result.failure(it) }
        return Result.success(File(resolved.path, resolved.provider, resolved.innerPath))
    }

    suspend fun node(path: String): Result<Node> {
        val resolved = resolve(path).getOrElse { return Result.failure(it) }
        val stat = resolved.provider.stat(resolved.innerPath).getOrElse { return Result.failure(it) }
        return Result.success(
            if (stat.isDirectory) {
                Node.Directory(Folder(resolved.path, resolved.provider, resolved.innerPath))
            } else {
                Node.Entry(File(resolved.path, resolved.provider, resolved.innerPath))
            },
        )
    }

    fun navigate(currentPath: String, relPath: String): String? {
        val path = Path.parse(currentPath)
        val result = path.navigate(relPath)
        if (result == path) return null
        return result.toString()
    }

    // ── Simple operations ──────────────────────────────────────────

    fun list(path: String) = dispatch(path) { provider, inner -> runBlocking { provider.list(inner) } }

    fun stat(path: String) = dispatch(path) { provider, inner -> runBlocking { provider.stat(inner) } }

    suspend fun directoryCapabilities(path: String): DirectoryCapabilities {
        val resolved = resolve(path).getOrNull() ?: return DirectoryCapabilities()
        return resolved.provider.directoryCapabilities(resolved.innerPath)
    }

    fun createFile(parentPath: String, name: String): Result<String> = runCatching {
        if (!runBlocking { directoryCapabilities(parentPath) }.canCreate) {
            throw PermissionDeniedException(parentPath)
        }
        val fullPath = Path.parse(parentPath).child(name).toString()
        dispatch(fullPath) { provider, inner -> runBlocking { provider.createFile(inner) } }.getOrThrow()
        fullPath
    }

    fun createDirectory(parentPath: String, name: String): Result<String> = runCatching {
        if (!runBlocking { directoryCapabilities(parentPath) }.canCreate) {
            throw PermissionDeniedException(parentPath)
        }
        val fullPath = Path.parse(parentPath).child(name).toString()
        dispatch(fullPath) { provider, inner ->
            runBlocking { provider.createDirectory(inner) }
        }.getOrThrow()
        fullPath
    }

    fun rename(path: String, newName: String): Result<Unit> =
        dispatch(path) { provider, inner -> runBlocking { provider.rename(inner, newName) } }

    fun delete(path: String): Result<Unit> =
        dispatch(path) { provider, inner -> runBlocking { provider.delete(inner) } }

    // ── Event-driven copy/move ──────────────────────────────────────

    fun copy(from: String, toDir: String): Flow<CopyProgress> = flow {
        val sourceName = Path.parse(from).name ?: from.substringAfterLast('/')
        val targetPath = Path.parse(toDir).child(sourceName).toString()
        val source = resolve(from).getOrThrow()
        val destination = resolve(targetPath).getOrThrow()
        copyRecursive(source, destination, isCopy = true).collect { emit(it) }
    }.flowOn(Dispatchers.IO)

    /** Copy [from] to the exact path [to], across providers if needed. */
    fun copyTo(from: String, to: String): Flow<CopyProgress> = flow {
        val source = resolve(from).getOrThrow()
        val destination = resolve(to).getOrThrow()
        copyRecursive(source, destination, isCopy = true).collect { emit(it) }
    }.flowOn(Dispatchers.IO)

    fun move(from: String, toDir: String): Flow<CopyProgress> = flow {
        val sourceName = Path.parse(from).name ?: from.substringAfterLast('/')
        val targetPath = Path.parse(toDir).child(sourceName).toString()
        val source = resolve(from).getOrThrow()
        val destination = resolve(targetPath).getOrThrow()
        copyRecursive(source, destination, isCopy = false).collect { emit(it) }
        runBlocking(Dispatchers.IO) {
            runCatching { source.provider.delete(source.innerPath) }
        }
    }.flowOn(Dispatchers.IO)

    suspend fun info(path: String, entries: List<FileEntry>): DirectoryStats {
        val stat = resolve(path).getOrNull()?.let { resolved ->
            resolved.provider.stat(resolved.innerPath).getOrNull()
        }
        return DirectoryStats(
            folderCount = entries.count { it.isDirectory },
            fileCount = entries.count { !it.isDirectory },
            usableBytes = stat?.usableBytes ?: 0,
            totalBytes = stat?.totalBytes ?: 0,
        )
    }

    // ── Internal ────────────────────────────────────────────────────

    private inline fun <T> dispatch(
        path: String,
        block: (FileProvider, String) -> Result<T>,
    ): Result<T> {
        val resolved = resolve(path).getOrElse { return Result.failure(it) }
        return block(resolved.provider, resolved.innerPath)
    }

    /**
     * Recursive copy across one or two providers. Directories are created,
     * listed, and recursed. Files use the zero-copy same-provider path when
     * possible and a streaming source otherwise.
     */
    private fun copyRecursive(
        source: ResolvedProvider,
        destination: ResolvedProvider,
        isCopy: Boolean,
    ): Flow<CopyProgress> = flow {
        val stat = runBlocking(Dispatchers.IO) {
            source.provider.stat(source.innerPath).getOrThrow()
        }

        if (stat.isDirectory) {
            runBlocking(Dispatchers.IO) {
                destination.provider.createDirectory(destination.innerPath).getOrThrow()
            }
            val entries = runBlocking(Dispatchers.IO) {
                source.provider.list(source.innerPath).getOrThrow()
            }
            for (entry in entries) {
                val childSourcePath = source.path.child(entry.name).toString()
                val childDestinationPath = destination.path.child(entry.name).toString()
                val childSource = resolve(childSourcePath).getOrNull() ?: continue
                val childDestination = resolve(childDestinationPath).getOrNull() ?: continue
                copyRecursive(childSource, childDestination, isCopy).collect { emit(it) }
            }
        } else if (source.provider === destination.provider) {
            destination.provider.copy(
                source.innerPath,
                destination.innerPath,
                isCopy,
            ).collect { emit(it) }
        } else {
            val fileSource = runBlocking(Dispatchers.IO) {
                source.provider.open(source.innerPath, OpenMode.READ).getOrThrow()
            }
            try {
                destination.provider.copy(fileSource, destination.innerPath, isCopy)
                    .collect { emit(it) }
            } finally {
                runCatching { fileSource.close() }
            }
        }
    }.flowOn(Dispatchers.IO)
}
