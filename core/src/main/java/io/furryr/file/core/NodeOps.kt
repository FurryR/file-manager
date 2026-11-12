package io.furryr.file.core

import io.furryr.file.core.model.CopyProgress
import io.furryr.file.core.source.OpenMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Node-to-node operations. Every function takes nodes that already exist; the
 * caller resolves a path once through [Vfs.node] (or takes the destination from
 * [VFolder.destination]), so an operation never formats a provider path.
 */
object NodeOps {

    /**
     * Copy [source] onto [target]. Both nodes already exist: the caller takes
     * the destination from [VFolder.destination] / [VFolder.destinationFolder]
     * (or resolved it), and a page link is a [VFile] like any other.
     */
    fun copyTo(source: VNode, target: VNode): Flow<CopyProgress> =
        copyNode(source, target, isCopy = true)

    /** Move [source] onto [target], renaming when both live in one provider. */
    fun moveTo(source: VNode, target: VNode): Flow<CopyProgress> = flow {
        val native = sameProviderCopy(source, target, isCopy = false)
        if (native != null) {
            native.collect { emit(it) }
            return@flow
        }
        copyNode(source, target, isCopy = false).collect { emit(it) }
        source.delete().getOrThrow()
    }.flowOn(Dispatchers.IO)

    /**
     * The file node at [path] for writing: the existing node, or a new one in
     * its parent folder. This is the string→node boundary for callers that are
     * handed a path from outside (agent tool input, restored state).
     */
    suspend fun destination(path: String): Result<VFile> = runCatching {
        (Vfs.node(path).getOrNull() as? VFile)?.let { return@runCatching it }
        val parsed = Path.parse(path)
        val name = parsed.name ?: error("no file name in $path")
        val parent = parsed.parent() ?: error("no parent folder for $path")
        Vfs.folder(parent).getOrThrow().destination(name).getOrThrow()
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
        val native = sameProviderCopy(source, target, isCopy)
        if (native != null) {
            native.collect { emit(it) }
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

    /** Same provider on both sides: the provider copies or renames natively. */
    private fun sameProviderCopy(source: VNode, target: VNode, isCopy: Boolean): Flow<CopyProgress>? =
        if (source is File && target is File && source.provider === target.provider) {
            source.provider.copy(source.innerPath, target.innerPath, isCopy)
        } else {
            null
        }

    private const val COPY_CHUNK = 256 * 1024
}
