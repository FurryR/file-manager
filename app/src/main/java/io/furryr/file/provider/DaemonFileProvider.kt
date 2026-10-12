package io.furryr.file.provider

import io.furryr.file.core.DaemonManager
import io.furryr.file.core.DaemonTarget
import io.furryr.file.core.file.FileService
import io.furryr.file.core.model.CopyProgress
import io.furryr.file.core.model.FileEntry
import io.furryr.file.core.source.FileSource
import io.furryr.file.core.source.OpenMode
import kotlinx.coroutines.flow.Flow

/**
 * The default provider, backed by the primary daemon (root when configured,
 * otherwise the app UID). All work is delegated to [FileService]; the provider
 * only maps it onto the [FileProvider] contract.
 */
class DaemonFileProvider : FileProvider {
    private val files: FileService get() = DaemonManager.fileService(DaemonTarget.Primary)

    override val scheme: String get() = "file"
    override val authority: String? get() = null
    override val label: String get() = "Local Storage"
    override val features: Set<ProviderFeature> get() = ProviderFeature.entries.toSet()

    override fun isAvailable(): Boolean = true

    override suspend fun connect(): Result<Unit> = DaemonManager.warmUp()

    override suspend fun disconnect() {}

    override suspend fun list(path: String): Result<List<FileEntry>> = files.list(path)

    override suspend fun stat(path: String) = files.stat(path)

    override suspend fun createFile(path: String): Result<Unit> = files.createFile(path)

    override suspend fun createDirectory(path: String): Result<Unit> = files.createDirectory(path)

    override suspend fun delete(path: String): Result<Unit> = files.delete(path)

    override suspend fun rename(path: String, newName: String): Result<Unit> = files.rename(path, newName)

    override suspend fun open(path: String, mode: OpenMode): Result<FileSource> = files.open(path, mode)

    override fun copy(from: String, to: String, isCopy: Boolean): Flow<CopyProgress> =
        files.copy(from, to, isCopy)

    override fun copy(src: FileSource, destPath: String, isCopy: Boolean): Flow<CopyProgress> =
        files.copyFromSource(src, destPath, isCopy)
}
