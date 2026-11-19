package io.furryr.file.container

import io.furryr.file.core.*

import android.content.Context
import io.furryr.file.daemon.DaemonManager
import io.furryr.file.daemon.DaemonTarget
import io.furryr.file.daemon.file.FileService
import io.furryr.file.core.model.CopyProgress
import io.furryr.file.core.model.FileEntry
import io.furryr.file.core.model.ProviderStat
import io.furryr.file.daemon.process.CommandSpec
import io.furryr.file.provider.terminal.DaemonTerminalProcess
import io.furryr.file.provider.terminal.ExecProvider
import io.furryr.file.provider.terminal.TerminalProcess
import io.furryr.file.pulse.PulseManager
import io.furryr.file.wayland.WaylandManager
import io.furryr.file.core.source.FileSource
import io.furryr.file.core.source.OpenMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File

/**
 * [FileProvider] for containers. Each container has its own dedicated daemon
 * (chroot or proot) started by [DaemonManager] through the app's launcher.
 *
 * `container://<name>/<path>` maps to `<path>` inside that container's daemon;
 * `container:///` lists the app-local set of containers.
 */
class ContainerProvider(
    private val appContext: Context,
) : FileProvider, ExecProvider, HostPathProvider {
    override val schemes: Set<String> get() = setOf(ContainerUris.SCHEME)
    override val label: String get() = "Containers"
    override val features: Set<ProviderFeature> get() = setOf(
        ProviderFeature.LIST, ProviderFeature.STAT, ProviderFeature.CREATE_FILE,
        ProviderFeature.CREATE_DIR, ProviderFeature.DELETE, ProviderFeature.RENAME,
        ProviderFeature.STREAM_COPY, ProviderFeature.OPEN,
    )

    /** User-visible locations: one per container, with edit/delete actions. */
    fun locations(context: Context): List<LocationEntry> =
        ContainerManager.listContainers(context).getOrDefault(emptyList()).map { container ->
            LocationEntry(
                id = container.name,
                title = container.name,
                subtitle = container.backend.name.lowercase(java.util.Locale.ROOT),
                root = ContainerUris.forContainer(container.name),
                provider = this,
                kind = "container",
                actions = object : LocationActions {
                    override suspend fun rename(name: String): Result<Unit> =
                        ContainerManager.renameContainer(context, container.name, name)

                    override suspend fun delete(): Result<Unit> =
                        ContainerManager.deleteContainer(context, container.name)
                },
            )
        }

    /** Drop every cached container daemon (e.g. after changing bind mounts). */
    fun resetConnections() = DaemonManager.closeNamedConnections()

    /** Run a command inside [containerName] and return its stdout. */
    suspend fun execContainer(containerName: String, command: String): String {
        val result = exec(containerName)
            .run(CommandSpec("/bin/sh", listOf("-c", command)))
            .getOrThrow()
        if (result.exitCode != 0) {
            val message = result.stderrText.trim()
            throw RuntimeException(
                if (message.isNotEmpty()) message else "Command exited with code ${result.exitCode}"
            )
        }
        return result.stdoutText.trim()
    }

    override fun isAvailable(): Boolean = true

    override suspend fun connect(): Result<Unit> = Result.success(Unit)

    override suspend fun disconnect() = DaemonManager.closeNamedConnections()

    override suspend fun list(path: String): Result<List<FileEntry>> = runCatching {
        val segments = containerSegmentsOrEmpty(path)
        if (segments.isEmpty()) {
            listContainerRoot()
        } else {
            val baseUri = ContainerUris.forContainer(segments.first())
            files(segments.first()).list(daemonPath(segments)).getOrThrow().map { entry ->
                entry.copy(path = "$baseUri/${entry.name}")
            }.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenBy { it.name.lowercase(java.util.Locale.ROOT) })
        }
    }

    override suspend fun stat(path: String): Result<ProviderStat> = runCatching {
        val segments = containerSegmentsOrEmpty(path)
        if (segments.isEmpty()) {
            // Container root listing is app-local; no per-container daemon exists
            // to stat it (a chroot daemon requires a container name).
            val dir = ContainerManager.containersDir(appContext)
            dir.mkdirs()
            ProviderStat(
                usableBytes = dir.freeSpace, totalBytes = dir.totalSpace,
                exists = true, isDirectory = true,
            )
        } else {
            files(segments.first()).stat(daemonPath(segments)).getOrThrow()
        }
    }

    override suspend fun createFile(path: String): Result<Unit> = runCatching {
        val segments = containerSegments(path)
        files(segments.first()).createFile(daemonPath(segments)).getOrThrow()
    }

    override suspend fun createDirectory(path: String): Result<Unit> = runCatching {
        val segments = containerSegments(path)
        files(segments.first()).createDirectory(daemonPath(segments)).getOrThrow()
    }

    override suspend fun delete(path: String): Result<Unit> = runCatching {
        val segments = containerSegments(path)
        files(segments.first()).delete(daemonPath(segments)).getOrThrow()
    }

    override suspend fun rename(path: String, newName: String): Result<Unit> = runCatching {
        val segments = containerSegments(path)
        files(segments.first()).rename(daemonPath(segments), newName).getOrThrow()
    }

    override suspend fun open(path: String, mode: OpenMode): Result<FileSource> = runCatching {
        val segments = containerSegments(path)
        files(segments.first()).open(daemonPath(segments), mode).getOrThrow()
    }

    override fun copy(from: String, to: String, isCopy: Boolean): Flow<CopyProgress> {
        val fromSegments = containerSegments(from)
        val toSegments = containerSegments(to)
        if (fromSegments.first() == toSegments.first()) {
            return files(fromSegments.first())
                .copy(daemonPath(fromSegments), daemonPath(toSegments), isCopy)
        }
        // Different containers have separate daemons; stream through the app.
        return flow {
            val source = files(fromSegments.first())
                .open(daemonPath(fromSegments), OpenMode.READ).getOrThrow()
            try {
                files(toSegments.first())
                    .copyFromSource(source, daemonPath(toSegments), isCopy)
                    .collect { emit(it) }
            } finally {
                source.close()
            }
        }.flowOn(Dispatchers.IO)
    }

    override fun copy(src: FileSource, destPath: String, isCopy: Boolean): Flow<CopyProgress> {
        val toSegments = containerSegments(destPath)
        return files(toSegments.first()).copyFromSource(src, daemonPath(toSegments), isCopy)
    }

    // ── Internal ───────────────────────────────────────────────────

    override suspend fun terminal(
        cwd: VFolder,
        rows: Int,
        cols: Int,
        terminalId: String,
    ): Result<TerminalProcess> =
        runCatching {
            val segments = containerSegments(innerPath(cwd.path))
            val name = segments.first()
            DaemonManager.warmNamed(name).getOrThrow()
            // Login profiles replace PATH even with `login -p`; the generated
            // /etc/profile.d/hub.sh appends HUB_PATH after they have run.
            val env = PulseManager.environment() + WaylandManager.environment(appContext) + mapOf(
                "PATH" to ContainerDaemonLauncher.DEFAULT_PATH,
                "HUB_PATH" to DaemonManager.nativeLibraryDir,
            ) + DaemonManager.hubEnvironment(DaemonTarget.Named(name), terminalId)
            val channel = exec(name).spawn(
                CommandSpec(
                    command = "",
                    args = listOf("-c", "exec /bin/login -p -f root"),
                    env = env,
                    cwd = "~",
                    user = "root",
                    rows = rows,
                    cols = cols,
                ),
            ).getOrThrow()
            DaemonTerminalProcess(channel, containerName = name)
        }

    /**
     * The shell's own container sees its entries directly. Host entries and
     * entries from another container are projected through the target's bind
     * mounts, so a path under a mounted host location becomes its container
     * path. A host location the target does not mount is rejected.
     */
    override fun terminalPath(cwd: VFolder, node: VNode): String? {
        val target = runCatching { containerSegments(innerPath(cwd.path)).first() }.getOrNull()
            ?: return null
        val source = node.provider ?: return null
        val followFinal = node is VFolder
        if (source === this) {
            val segments = runCatching { containerSegments(source.innerPath(node.path)) }.getOrNull()
                ?: return null
            val guest = daemonPath(segments)
            if (segments.first() == target) return guest
            val host = hostPath(source.innerPath(node.path), followFinal)
            val viaBind = host?.let { ContainerPaths.containerGuestPath(appContext, target, it) }
            if (viaBind != null) return viaBind
            // The source rootfs stays reachable through the target's /data bind
            // even when the target cannot see the source's private mount.
            val rootfs = ContainerPaths.containerRootfsHostPath(appContext, segments.first(), guest)
                ?: return null
            return ContainerPaths.containerGuestPath(appContext, target, rootfs)
        }
        val hostPaths = source as? HostPathProvider ?: return null
        val host = hostPaths.hostPath(source.innerPath(node.path), followFinal) ?: return null
        return ContainerPaths.containerGuestPath(appContext, target, host)
    }

    /** The host pathname of an entry in this container's rootfs or binds. */
    override fun hostPath(innerPath: String, followFinal: Boolean): String? = runCatching {
        val segments = containerSegments(innerPath)
        ContainerPaths.containerHostPath(appContext, segments.first(), daemonPath(segments), followFinal)
    }.getOrNull()

    private fun files(name: String): FileService =
        DaemonManager.fileService(DaemonTarget.Named(name))

    private fun exec(name: String) = DaemonManager.execService(DaemonTarget.Named(name))

    /** The container daemon lives inside the container, so paths are container-absolute. */
    private fun daemonPath(segments: List<String>): String {
        val subPath = segments.drop(1).joinToString("/")
        return if (subPath.isEmpty()) "/" else "/$subPath"
    }

    private fun containerSegments(raw: String): List<String> {
        val segments = containerSegmentsOrEmpty(raw)
        if (segments.isEmpty()) throw IllegalStateException("Cannot operate at container root")
        return segments
    }

    /** Provider-facing inner paths are already parsed; split them, never re-parse. */
    private fun containerSegmentsOrEmpty(raw: String): List<String> =
        raw.trim('/').split('/').filter { it.isNotEmpty() }

    private fun listContainerRoot(): List<FileEntry> {
        return ContainerManager.listContainers(appContext).getOrDefault(emptyList()).map { container ->
            val root = ContainerUris.forContainer(container.name)
            FileEntry(
                name = container.name,
                path = root,
                isDirectory = true,
                modifiedAt = container.createdAt,
                realPath = root,
                mode = 0x41ED, // drwxr-xr-x
            )
        }.sortedBy { it.name.lowercase(java.util.Locale.ROOT) }
    }
}
