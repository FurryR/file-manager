package io.furryr.file.provider

import io.furryr.file.copy.CopyProgress
import io.furryr.file.copy.FileSource
import io.furryr.file.copy.OpenMode
import io.furryr.file.model.FileEntry
import kotlinx.coroutines.flow.Flow

/**
 * Unified interface for file system operations, abstracting over
 * different backends: local daemon, containers, SAF, remote, etc.
 *
 * Each provider is identified by its [scheme] and optional [authority],
 * enabling URI-based routing like `container://alpine/home/`.
 */
interface FileProvider {
    val scheme: String
    val authority: String?
    val label: String
    val features: Set<ProviderFeature>

    suspend fun list(path: String): Result<List<FileEntry>>
    suspend fun stat(path: String): Result<ProviderStat>
    suspend fun createFile(path: String): Result<Unit>
    suspend fun createDirectory(path: String): Result<Unit>
    suspend fun delete(path: String): Result<Unit>
    suspend fun rename(path: String, newName: String): Result<Unit>

    /** Open a file and return a universally readable/writable [FileSource]. */
    suspend fun open(path: String, mode: OpenMode): Result<FileSource>

    /**
     * Same-provider copy: both [from] and [to] are native paths of this provider.
     * Daemon-native [stream_copy] is used — zero-copy, no handle open.
     */
    fun copy(from: String, to: String, isCopy: Boolean): Flow<CopyProgress>

    /**
     * Cross-provider copy: [src] is a [FileSource] obtained via another provider's
     * [open]. Destination [destPath] is a native path of this provider.
     */
    fun copy(src: FileSource, destPath: String, isCopy: Boolean): Flow<CopyProgress>

    fun isAvailable(): Boolean
    suspend fun connect(): Result<Unit>
    suspend fun disconnect()
}

enum class ProviderFeature {
    LIST, STAT, CREATE_FILE, CREATE_DIR, DELETE,
    RENAME, STREAM_COPY, PTY, SYMLINK,
    OPEN,
}

data class ProviderStat(
    val usableBytes: Long = 0,
    val totalBytes: Long = 0,
    val exists: Boolean = false,
    val isDirectory: Boolean = false,
)
