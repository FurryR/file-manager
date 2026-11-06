package io.furryr.file.core

import io.furryr.file.core.source.FileSource
import io.furryr.file.core.source.OpenMode

/**
 * String-free access to nodes for clients that only have a [ViewRef] (tab
 * restore, agent tools, share targets). This is the only place outside [Vfs]
 * that may turn a reference into a node.
 */
interface NodeService {
    suspend fun resolve(ref: ViewRef): Result<VNode>

    suspend fun list(ref: ViewRef): Result<List<VNode>> = runCatching {
        val folder = resolve(ref).getOrThrow() as? VFolder
            ?: error("not a folder: $ref")
        folder.listNodes().getOrThrow()
    }

    suspend fun open(ref: ViewRef, mode: OpenMode): Result<FileSource> = runCatching {
        val file = resolve(ref).getOrThrow() as? VFile
            ?: error("not a file: $ref")
        file.open(mode).getOrThrow()
    }

    suspend fun describe(ref: ViewRef): Result<ContentInfo> = runCatching {
        val file = resolve(ref).getOrThrow() as? VFile
            ?: error("not a file: $ref")
        file.describe().getOrThrow()
    }
}
