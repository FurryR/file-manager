package io.furryr.file.core.file

import io.furryr.file.core.ipc.DaemonConnection
import io.furryr.file.core.model.CopyProgress
import io.furryr.file.core.model.FileEntry
import io.furryr.file.core.model.ProviderStat
import io.furryr.file.core.source.FdFileSource
import io.furryr.file.core.source.FileSource
import io.furryr.file.core.source.HandleFileSource
import io.furryr.file.core.source.OpenMode
import io.furryr.file.core.source.openDaemonSource
import io.furryr.file.proto.HandleMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * File operations against one daemon target. Returned by
 * [io.furryr.file.core.DaemonManager.fileService].
 *
 * Paths are daemon-native (container-absolute for container targets); URI
 * prefixing is the caller provider's job.
 */
class FileService internal constructor(private val resolve: () -> DaemonConnection) {

    suspend fun list(path: String): Result<List<FileEntry>> = resolve().listFiles(path)

    suspend fun stat(path: String): Result<ProviderStat> = resolve().stat(path)

    suspend fun createFile(path: String): Result<Unit> = resolve().createFile(path)

    suspend fun createDirectory(path: String): Result<Unit> = resolve().createDir(path)

    suspend fun delete(path: String): Result<Unit> = resolve().deleteEntry(path)

    suspend fun rename(path: String, newName: String): Result<Unit> = resolve().renameEntry(path, newName)

    suspend fun open(path: String, mode: OpenMode): Result<FileSource> = runCatching {
        openDaemonSource(path, mode) { resolve() }
    }

    /** Same-daemon copy/move via the daemon's native stream copy. */
    fun copy(from: String, to: String, isCopy: Boolean): Flow<CopyProgress> =
        resolve().copy(from, to, isCopy)

    /** Copy an external [FileSource] into this daemon at [destPath]. */
    fun copyFromSource(src: FileSource, destPath: String, isCopy: Boolean): Flow<CopyProgress> {
        val connection = resolve()
        return if (src is HandleFileSource || src is FdFileSource) {
            connection.copyFromSource(src, destPath, isCopy)
        } else {
            chunkedCopy(connection, src, destPath, isCopy)
        }
    }

    private fun chunkedCopy(
        connection: DaemonConnection,
        src: FileSource,
        destPath: String,
        isCopy: Boolean,
    ): Flow<CopyProgress> = flow {
        val dst = connection.openHandle(destPath, HandleMode.WRITE).getOrThrow()
        try {
            var offset = 0L
            var copied = 0L
            while (true) {
                val data = src.readChunk(offset, 256 * 1024)
                if (data.isEmpty()) break
                connection.writeHandle(dst.handleId, offset, data).getOrThrow()
                offset += data.size
                copied += data.size
                emit(CopyProgress(src.size, copied, src.absolutePath ?: "", false, isCopy))
            }
            emit(CopyProgress(src.size, copied, src.absolutePath ?: "", true, isCopy))
        } finally {
            runCatching { connection.closeHandle(dst.handleId) }
        }
    }.flowOn(Dispatchers.IO)
}
