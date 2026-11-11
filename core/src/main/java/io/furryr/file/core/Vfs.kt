package io.furryr.file.core

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

    /** Route a parsed [Path] to its provider. View levels never reach a provider. */
    fun resolve(path: Path): Result<ResolvedProvider> {
        if (path.isViewed) {
            return Result.failure(
                IllegalArgumentException("a view address has no provider: $path"),
            )
        }
        val scheme = path.scheme
        val provider = if (scheme == null) {
            default ?: return Result.failure(IllegalStateException("No default provider"))
        } else {
            providers[scheme]
                ?: return Result.failure(IllegalStateException("No provider for scheme '$scheme'"))
        }
        return Result.success(ResolvedProvider(provider, path, path.toString()))
    }

    /** Address-text boundary: parse once, then stay on [Path]. */
    fun resolve(uri: String): Result<ResolvedProvider> {
        val path = runCatching { Path.parse(uri) }.getOrElse { error ->
            return Result.failure(IllegalArgumentException("Cannot parse path: $uri", error))
        }
        return resolve(path)
    }

    fun folder(path: Path): Result<Folder> {
        val resolved = resolve(path).getOrElse { return Result.failure(it) }
        return Result.success(Folder(resolved.path, resolved.provider, resolved.innerPath))
    }

    fun folder(path: String): Result<Folder> = folder(Path.parse(path))

    fun file(path: Path): Result<File> {
        val resolved = resolve(path).getOrElse { return Result.failure(it) }
        return Result.success(File(resolved.path, resolved.provider, resolved.innerPath))
    }

    fun file(path: String): Result<File> = file(Path.parse(path))

    /**
     * The node at [path]. The provider mints it: a storage provider answers with
     * a [Folder]/[File], a live one with its own [VNode] implementation, and the
     * caller never has to know which.
     */
    suspend fun node(path: Path): Result<VNode> {
        val resolved = resolve(path).getOrElse { return Result.failure(it) }
        return resolved.provider.node(resolved.path, resolved.innerPath)
    }

    suspend fun node(path: String): Result<VNode> = node(Path.parse(path))

    // ── Simple operations ──────────────────────────────────────────

    fun stat(path: String) = dispatch(path) { provider, inner -> runBlocking { provider.stat(inner) } }

    // ── Node operations ────────────────────────────────────────────
    //
    // Everything below takes nodes, never paths. A caller that has a path
    // resolved it once through [node]/[folder]/[file] (or [destination]), and
    // stays on nodes afterwards; only provider implementations and the
    // address parser still see strings.

    /**
     * Copy [source] onto [target]. Both nodes already exist: the caller takes
     * the destination from [VFolder.destination] / [VFolder.destinationFolder]
     * (or resolved it), so an operation never formats a provider path. A page
     * link (`WebUrlFile`) is a [VFile] like any other and streams straight into
     * the destination node.
     */
    fun copyTo(source: VNode, target: VNode): Flow<CopyProgress> =
        copyNode(source, target, isCopy = true)

    /** Move [source] onto [target], renaming when both live in one provider. */
    fun moveTo(source: VNode, target: VNode): Flow<CopyProgress> = flow {
        if (source is File && target is File && source.provider === target.provider) {
            source.provider.copy(source.innerPath, target.innerPath, isCopy = false)
                .collect { emit(it) }
            return@flow
        }
        // Folder children keep the provider rename when they share one, so a
        // move inside a provider never moves bytes.
        copyNode(source, target, isCopy = false).collect { emit(it) }
        source.delete().getOrThrow()
    }.flowOn(Dispatchers.IO)

    /**
     * The file node at [path] for writing: the existing node, or a new one in
     * its parent folder. This is the string→node boundary for callers that are
     * handed a path from outside (agent tool input, restored state).
     */
    suspend fun destination(path: String): Result<VFile> = runCatching {
        (node(path).getOrNull() as? VFile)?.let { return@runCatching it }
        val parsed = Path.parse(path)
        val name = parsed.name ?: error("no file name in $path")
        val parent = parsed.parent() ?: error("no parent folder for $path")
        folder(parent).getOrThrow().destination(name).getOrThrow()
    }

    private fun copyNode(source: VNode, target: VNode, isCopy: Boolean): Flow<CopyProgress> = flow {
        when {
            source is VFolder && target is VFolder ->
                copyFolder(source, target, isCopy).collect { emit(it) }
            source is VFile && target is VFile ->
                copyFile(source, target, isCopy).collect { emit(it) }
            else -> error("copy needs a file→file or folder→folder pair: ${source.path} → ${target.path}")
        }
    }.flowOn(Dispatchers.IO)

    private fun copyFolder(
        source: VFolder,
        target: VFolder,
        isCopy: Boolean,
    ): Flow<CopyProgress> = flow {
        for (child in source.listNodes().getOrThrow()) {
            val destination = when (child) {
                is VFolder -> target.destinationFolder(child.name).getOrThrow()
                is VFile -> target.destination(child.name).getOrThrow()
                else -> continue
            }
            copyNode(child, destination, isCopy).collect { emit(it) }
        }
    }.flowOn(Dispatchers.IO)

    private fun copyFile(source: VFile, target: VFile, isCopy: Boolean): Flow<CopyProgress> = flow {
        // Same provider on both sides: the provider moves or copies natively.
        if (source is File && target is File && source.provider === target.provider) {
            source.provider.copy(source.innerPath, target.innerPath, isCopy).collect { emit(it) }
            return@flow
        }
        // Otherwise stream through the destination node. The source may be a
        // URL, an archive entry or a remote backend with no kernel handle.
        val input = source.open(OpenMode.READ).getOrThrow()
        try {
            val output = target.open(OpenMode.WRITE).getOrThrow()
            try {
                var offset = 0L
                emit(CopyProgress(input.size, 0L, target.name, finished = false, isCopy = isCopy))
                while (true) {
                    val chunk = input.readChunk(offset, COPY_CHUNK)
                    if (chunk.isEmpty()) break
                    output.writeAt(offset, chunk)
                    offset += chunk.size
                    emit(CopyProgress(input.size, offset, target.name, finished = false, isCopy = isCopy))
                }
                // The destination may be longer than the source.
                runCatching { output.truncate(offset) }
                emit(CopyProgress(input.size, offset, target.name, finished = true, isCopy = isCopy))
            } finally {
                runCatching { output.close() }
            }
        } finally {
            runCatching { input.close() }
        }
    }.flowOn(Dispatchers.IO)

    private const val COPY_CHUNK = 256 * 1024

    // ── Internal ────────────────────────────────────────────────────

    private inline fun <T> dispatch(
        path: String,
        block: (FileProvider, String) -> Result<T>,
    ): Result<T> {
        val resolved = resolve(path).getOrElse { return Result.failure(it) }
        return block(resolved.provider, resolved.innerPath)
    }
}
