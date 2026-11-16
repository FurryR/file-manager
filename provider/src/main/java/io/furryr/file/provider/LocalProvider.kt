package io.furryr.file.provider

import io.furryr.file.core.*

import io.furryr.file.daemon.DaemonManager
import io.furryr.file.daemon.DaemonTarget
import io.furryr.file.daemon.file.FileService
import io.furryr.file.daemon.process.CommandSpec
import io.furryr.file.provider.terminal.DaemonTerminalProcess
import io.furryr.file.provider.terminal.ExecProvider
import io.furryr.file.provider.terminal.TerminalProcess
import io.furryr.file.core.model.CopyProgress
import io.furryr.file.core.model.FileEntry
import io.furryr.file.core.source.FileSource
import io.furryr.file.core.source.OpenMode
import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * Local filesystem provider, backed by the one host daemon (root when
 * configured, otherwise the app UID). All work is delegated to [FileService];
 * the provider only maps it onto the [FileProvider] contract.
 */
class LocalProvider : FileProvider, ExecProvider, HostPathProvider {
    private val files: FileService get() = DaemonManager.fileService(DaemonTarget.Host)

    override val schemes: Set<String> get() = setOf("file")
    override val label: String get() = "Local Storage"
    override val features: Set<ProviderFeature> get() = setOf(
        ProviderFeature.LIST, ProviderFeature.STAT, ProviderFeature.CREATE_FILE,
        ProviderFeature.CREATE_DIR, ProviderFeature.DELETE, ProviderFeature.RENAME,
        ProviderFeature.STREAM_COPY, ProviderFeature.OPEN,
    )

    override fun innerPath(path: Path): String = path.toLocalPath()

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

    override suspend fun terminal(cwd: VFolder, rows: Int, cols: Int): Result<TerminalProcess> =
        runCatching {
            val channel = DaemonManager.execService(DaemonTarget.Host).spawn(
                CommandSpec(
                    command = "/system/bin/sh",
                    cwd = innerPath(cwd.path),
                    rows = rows,
                    cols = cols,
                ),
            ).getOrThrow()
            DaemonTerminalProcess(channel)
        }

    /**
     * The host shell accepts local entries and container entries whose host
     * pathname exists. Any other provider owns bytes that are not a kernel
     * pathname, so it is rejected.
     */
    override fun terminalPath(cwd: VFolder, node: VNode): String? {
        val owner = node.provider ?: return null
        val inner = owner.innerPath(node.path)
        if (owner === this) return hostPath(inner, node is VFolder)
        val hostPaths = owner as? HostPathProvider ?: return null
        return hostPaths.hostPath(inner, node is VFolder)
    }

    override fun hostPath(innerPath: String, followFinal: Boolean): String? = runCatching {
        val file = File(innerPath)
        // Retain file symlinks, but resolve directory aliases such as /sdcard.
        if (followFinal) file.canonicalPath
        else if (file.parentFile == null) "/"
        else File(file.parentFile!!.canonicalFile, file.name).path
    }.getOrNull()
}
