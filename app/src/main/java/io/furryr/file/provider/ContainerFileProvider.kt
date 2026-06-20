package io.furryr.file.provider

import android.content.Context
import android.util.Log
import io.furryr.file.agent.ContainerManager
import io.furryr.file.copy.CopyProgress
import io.furryr.file.copy.FileSource
import io.furryr.file.copy.OpenMode
import io.furryr.file.copy.StreamFileSource
import io.furryr.file.model.FileEntry
import io.furryr.file.util.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.FileInputStream

/**
 * [FileProvider] for proot containers.
 *
 * Maps `container://<name>/<path>` URIs to files within the container's
 * filesystem directory (`filesDir/containers/<name>/...`).
 * `container:///` maps to `filesDir/containers/` (list of all containers).
 */
object ContainerFileProvider : FileProvider {
    private const val TAG = "ContainerFileProvider"
    private lateinit var appContext: Context

    override val scheme: String get() = "container"
    override val authority: String? get() = null
    override val label: String get() = "Container"
    override val features: Set<ProviderFeature> get() = setOf(
        ProviderFeature.LIST, ProviderFeature.STAT,
        ProviderFeature.CREATE_FILE, ProviderFeature.CREATE_DIR,
        ProviderFeature.DELETE,
        ProviderFeature.RENAME, ProviderFeature.OPEN,
    )

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    override fun isAvailable(): Boolean = true

    override suspend fun connect(): Result<Unit> = Result.success(Unit)
    override suspend fun disconnect() {}

    override suspend fun list(path: String): Result<List<FileEntry>> = runCatching {
        val segments = Path.parse(path).segmentList
        if (segments.isEmpty()) {
            listContainerRoot()
        } else {
            val dir = resolveDir(segments) ?: throw java.io.FileNotFoundException("Not found: $path")
            val children = dir.listFiles() ?: emptyArray()
            val baseUri = innerPathToUri(path)
            children.map { file ->
                FileEntry(
                    name = file.name,
                    path = "$baseUri/${file.name}",
                    isDirectory = file.isDirectory,
                    modifiedAt = file.lastModified(),
                    isSymlink = java.nio.file.Files.isSymbolicLink(file.toPath()),
                    size = file.length(),
                )
            }.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
        }
    }

    override suspend fun stat(path: String): Result<ProviderStat> = runCatching {
        val segments = Path.parse(path).segmentList
        val file = if (segments.isEmpty()) {
            File(appContext.filesDir, "containers")
        } else {
            resolveFile(segments)
        }
        if (file == null) ProviderStat(exists = false)
        else ProviderStat(
            exists = true,
            isDirectory = file.isDirectory,
            usableBytes = file.freeSpace,
            totalBytes = file.totalSpace,
        )
    }

    override suspend fun createFile(path: String): Result<Unit> = runCatching {
        val segments = Path.parse(path).segmentList
        if (segments.isEmpty()) throw IllegalStateException("Cannot create file at container root")
        val file = resolveFile(segments) ?: throw IllegalStateException("Invalid container path: $path")
        file.createNewFile()
    }

    override suspend fun createDirectory(path: String): Result<Unit> = runCatching {
        val segments = Path.parse(path).segmentList
        if (segments.isEmpty()) throw IllegalStateException("Cannot create directory at container root")
        val file = resolveFile(segments) ?: throw IllegalStateException("Invalid container path: $path")
        file.mkdirs()
    }

    override suspend fun delete(path: String): Result<Unit> = runCatching {
        val segments = Path.parse(path).segmentList
        if (segments.isEmpty()) throw IllegalStateException("Cannot delete container root")
        val file = resolveFile(segments) ?: throw java.io.FileNotFoundException("Not found: $path")
        file.deleteRecursively()
    }

    override suspend fun rename(path: String, newName: String): Result<Unit> = runCatching {
        val segments = Path.parse(path).segmentList
        if (segments.isEmpty()) throw IllegalStateException("Cannot rename at container root")
        val file = resolveFile(segments) ?: throw java.io.FileNotFoundException("Not found: $path")
        val parent = file.parentFile ?: throw IllegalStateException("Cannot rename root")
        file.renameTo(File(parent, newName))
    }

    override suspend fun open(path: String, mode: OpenMode): Result<FileSource> = runCatching {
        val segments = Path.parse(path).segmentList
        if (segments.isEmpty()) throw IllegalStateException("Cannot open container root")
        val file = resolveFile(segments) ?: throw java.io.FileNotFoundException("Not found: $path")
        if (mode != OpenMode.READ) throw UnsupportedOperationException("ContainerFileProvider supports READ only")
        StreamFileSource(inputStream = FileInputStream(file), size = file.length(), absolutePath = file.absolutePath)
    }

    override fun copy(from: String, to: String, isCopy: Boolean): Flow<CopyProgress> =
        flow<CopyProgress> { throw UnsupportedOperationException("ContainerFileProvider copy not implemented") }.flowOn(Dispatchers.IO)

    override fun copy(src: FileSource, destPath: String, isCopy: Boolean): Flow<CopyProgress> =
        flow<CopyProgress> { throw UnsupportedOperationException("ContainerFileProvider copy not implemented") }.flowOn(Dispatchers.IO)

    // ── Internal ───────────────────────────────────────────────────

    private fun resolveFile(segments: List<String>): File? {
        val name = segments.first()
        val container = ContainerManager.getContainer(appContext, name) ?: return null
        val rootfs = File(container.rootfsPath)
        val relativePath = segments.drop(1).joinToString("/")
        return if (relativePath.isEmpty()) rootfs else File(rootfs, relativePath)
    }

    private fun resolveDir(segments: List<String>): File? {
        val file = resolveFile(segments) ?: return null
        return if (file.isDirectory) file else file.parentFile
    }

    private fun listContainerRoot(): List<FileEntry> {
        val containersDir = File(appContext.filesDir, "containers")
        if (!containersDir.isDirectory) return emptyList()

        val containers = ContainerManager.listContainers(appContext).getOrDefault(emptyList())
        return containers.map { container ->
            FileEntry(
                name = container.name,
                path = "$scheme://${container.name}/",
                isDirectory = true,
                modifiedAt = container.createdAt,
                isSymlink = false,
                realPath = "$scheme://${container.name}/",
                mode = 0,
                size = 0,
            )
        }.sortedBy { it.name.lowercase() }
    }

    private fun innerPathToUri(innerPath: String): String {
        return "${scheme}://${innerPath.trimStart('/')}"
    }
}
