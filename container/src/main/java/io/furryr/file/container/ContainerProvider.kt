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
object ContainerProvider : FileProvider {
    private lateinit var appContext: Context

    override val scheme: String get() = "container"
    override val authority: String? get() = null
    override val label: String get() = "Containers"
    override val features: Set<ProviderFeature> get() = ProviderFeature.entries.toSet()

    fun init(context: Context) {
        appContext = context.applicationContext
    }


    /** User-visible locations: one per container, with edit/delete actions. */
    fun locations(context: Context): List<LocationEntry> =
        ContainerManager.listContainers(context).getOrDefault(emptyList()).map { container ->
            LocationEntry(
                id = container.name,
                title = container.name,
                subtitle = container.backend.name.lowercase(),
                root = "container://${container.name}",
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

    override suspend fun directoryCapabilities(path: String): DirectoryCapabilities {
        val segments = Path.parse(path).segmentList
        if (segments.isEmpty()) return DirectoryCapabilities()
        val directory = stat(path).getOrNull() ?: return DirectoryCapabilities()
        return DirectoryCapabilities(
            canCreate = directory.canCreateChildren,
            canOpenTerminal = directory.canEnter,
        )
    }

    override suspend fun list(path: String): Result<List<FileEntry>> = runCatching {
        val segments = Path.parse(path).segmentList
        if (segments.isEmpty()) {
            listContainerRoot()
        } else {
            val baseUri = innerPathToUri(path)
            files(segments.first()).list(daemonPath(segments)).getOrThrow().map { entry ->
                entry.copy(path = "$baseUri/${entry.name}")
            }.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
        }
    }

    override suspend fun stat(path: String): Result<ProviderStat> = runCatching {
        val segments = Path.parse(path).segmentList
        if (segments.isEmpty()) {
            // Container root listing is app-local; no per-container daemon exists
            // to stat it (a chroot daemon requires a container name).
            val dir = File(appContext.filesDir, "containers")
            ProviderStat(
                usableBytes = dir.freeSpace, totalBytes = dir.totalSpace,
                exists = dir.exists(), isDirectory = dir.isDirectory,
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

    private fun files(name: String): FileService =
        DaemonManager.fileService(DaemonTarget.Named(name))

    private fun exec(name: String) = DaemonManager.execService(DaemonTarget.Named(name))

    /** The container daemon lives inside the container, so paths are container-absolute. */
    private fun daemonPath(segments: List<String>): String {
        val subPath = segments.drop(1).joinToString("/")
        return if (subPath.isEmpty()) "/" else "/$subPath"
    }

    private fun containerSegments(raw: String): List<String> {
        val segments = Path.parse(raw).segmentList
        if (segments.isEmpty()) throw IllegalStateException("Cannot operate at container root")
        return segments
    }

    private fun listContainerRoot(): List<FileEntry> {
        return ContainerManager.listContainers(appContext).getOrDefault(emptyList()).map { container ->
            FileEntry(
                name = container.name,
                path = "$scheme://${container.name}",
                isDirectory = true,
                modifiedAt = container.createdAt,
                realPath = "$scheme://${container.name}",
                mode = 0x41ED, // drwxr-xr-x
            )
        }.sortedBy { it.name.lowercase() }
    }

    private fun innerPathToUri(innerPath: String): String =
        "${scheme}://${innerPath.trimStart('/')}"
}
