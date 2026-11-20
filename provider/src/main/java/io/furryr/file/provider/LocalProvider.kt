package io.furryr.file.provider

import android.content.Context
import io.furryr.file.core.*

import io.furryr.file.daemon.DaemonManager
import io.furryr.file.daemon.DaemonTarget
import io.furryr.file.daemon.file.FileService
import io.furryr.file.daemon.process.CommandSpec
import io.furryr.file.daemon.process.PipeChannel
import io.furryr.file.provider.terminal.DaemonTerminalProcess
import io.furryr.file.provider.terminal.ExecProvider
import io.furryr.file.provider.terminal.TerminalProcess
import io.furryr.file.core.model.CopyProgress
import io.furryr.file.core.model.FileEntry
import io.furryr.file.core.source.FileSource
import io.furryr.file.core.source.OpenMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File

/** Host shell search path; the packaged utilities directory is appended at spawn time. */
private const val HOST_SHELL_PATH =
    "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:" +
        "/system/bin:/system/xbin:/vendor/bin"

/**
 * Local filesystem provider. Ordinary paths use the host daemon (root,
 * Shizuku or direct); app-private paths use an internal app-UID daemon so a
 * shell-UID host daemon never needs access to `filesDir`.
 *
 * All work is delegated to [FileService]; the provider only maps it onto the
 * [FileProvider] contract.
 */
class LocalProvider(private val appContext: Context? = null) : FileProvider, ExecProvider, HostPathProvider {
    private fun isAppPrivate(path: String): Boolean {
        val context = appContext ?: return false
        val target = File(path).absoluteFile
        val roots = buildList {
            add(context.filesDir)
            add(context.cacheDir)
            add(context.noBackupFilesDir)
            add(context.codeCacheDir)
            context.getExternalFilesDirs(null).filterNotNull().forEach(::add)
        }
        return roots.any { root ->
            val base = root.absolutePath.trimEnd('/')
            target.path == base || target.path.startsWith("$base/")
        }
    }

    private fun files(path: String): FileService =
        if (isAppPrivate(path)) DaemonManager.appFileService()
        else DaemonManager.fileService(DaemonTarget.Host)

    private fun exec(path: String) =
        if (isAppPrivate(path)) DaemonManager.appExecService()
        else DaemonManager.execService(DaemonTarget.Host)

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

    override suspend fun list(path: String): Result<List<FileEntry>> = files(path).list(path)

    override suspend fun stat(path: String) = files(path).stat(path)

    override suspend fun createFile(path: String): Result<Unit> = files(path).createFile(path)

    override suspend fun createDirectory(path: String): Result<Unit> = files(path).createDirectory(path)

    override suspend fun delete(path: String): Result<Unit> = files(path).delete(path)

    override suspend fun rename(path: String, newName: String): Result<Unit> = files(path).rename(path, newName)

    override suspend fun open(path: String, mode: OpenMode): Result<FileSource> = files(path).open(path, mode)

    override fun copy(from: String, to: String, isCopy: Boolean): Flow<CopyProgress> {
        if (isAppPrivate(from) == isAppPrivate(to)) return files(from).copy(from, to, isCopy)
        return flow {
            files(from).open(from, OpenMode.READ).getOrThrow().use { source ->
                files(to).copyFromSource(source, to, isCopy).collect { emit(it) }
            }
        }.flowOn(Dispatchers.IO)
    }

    override fun copy(src: FileSource, destPath: String, isCopy: Boolean): Flow<CopyProgress> =
        files(destPath).copyFromSource(src, destPath, isCopy)

    override suspend fun terminal(
        cwd: VFolder,
        rows: Int,
        cols: Int,
        terminalId: String,
    ): Result<TerminalProcess> =
        runCatching {
            val path = innerPath(cwd.path)
            val private = isAppPrivate(path)
            val env = (if (private) emptyMap() else DaemonManager.hubEnvironment(DaemonTarget.Host, terminalId)) +
                mapOf("PATH" to "$HOST_SHELL_PATH:${DaemonManager.nativeLibraryDir}")
            val channel = exec(path).spawn(
                CommandSpec(
                    command = "/system/bin/sh",
                    env = env,
                    cwd = path,
                    rows = rows,
                    cols = cols,
                ),
            ).getOrThrow()
            DaemonTerminalProcess(channel)
        }

    override suspend fun spawnPipes(cwd: VFolder, spec: CommandSpec): Result<PipeChannel> =
        exec(spec.cwd ?: innerPath(cwd.path)).spawnPipes(spec)

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
