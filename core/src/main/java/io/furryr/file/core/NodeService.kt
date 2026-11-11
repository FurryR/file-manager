package io.furryr.file.core

import io.furryr.file.core.source.FileSource
import io.furryr.file.core.source.OpenMode

/**
 * Resolve an address into a node. A [Path] without view levels is one provider
 * lookup; with view levels the service enters each level through the opener
 * registry, so a container inside a container is just a longer chain. This is
 * the only place outside [Vfs] that turns an address into a node.
 */
interface NodeService {
    suspend fun resolve(path: Path): Result<VNode>

    /**
     * Resolve [path] from an already resolved [root], entering any intermediate
     * file through the opener route. Callers that already hold the root (for
     * example an archive detected by a path walk) use this to avoid a second
     * provider stat.
     */
    suspend fun resolveFrom(root: VNode, path: List<String>): Result<VNode>

    suspend fun list(path: Path): Result<List<VNode>> = runCatching {
        val folder = resolve(path).getOrThrow() as? VFolder
            ?: error("not a folder: $path")
        folder.listNodes().getOrThrow()
    }

    suspend fun open(path: Path, mode: OpenMode): Result<FileSource> = runCatching {
        val file = resolve(path).getOrThrow() as? VFile
            ?: error("not a file: $path")
        file.open(mode).getOrThrow()
    }

    suspend fun describe(path: Path): Result<ContentInfo> = runCatching {
        val file = resolve(path).getOrThrow() as? VFile
            ?: error("not a file: $path")
        file.describe().getOrThrow()
    }
}
