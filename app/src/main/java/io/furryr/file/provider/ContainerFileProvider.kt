package io.furryr.file.provider

import android.content.Context
import android.util.Log
import io.furryr.file.agent.ContainerManager
import io.furryr.file.core.model.CopyProgress
import io.furryr.file.core.source.FileSource
import io.furryr.file.core.source.OpenMode
import io.furryr.file.core.source.StreamFileSource
import io.furryr.file.core.model.FileEntry
import io.furryr.file.core.model.ProviderStat
import io.furryr.file.util.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * [FileProvider] for proot containers.
 *
 * Maps `container://<name>/<path>` URIs to files within the container's
 * filesystem directory (`filesDir/containers/<name>/...`).
 * `container:///` maps to `filesDir/containers/` (list of all containers).
 */
object ContainerFileProvider : FileProvider {
    private const val TAG = "ContainerFileProvider"
    private const val COPY_BUFFER_SIZE = 256 * 1024
    private lateinit var appContext: Context

    override val scheme: String get() = "container"
    override val authority: String? get() = null
    override val label: String get() = "Container"
    override val features: Set<ProviderFeature> get() = setOf(
        ProviderFeature.LIST, ProviderFeature.STAT,
        ProviderFeature.CREATE_FILE, ProviderFeature.CREATE_DIR,
        ProviderFeature.DELETE,
        ProviderFeature.RENAME, ProviderFeature.OPEN, ProviderFeature.STREAM_COPY,
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

    override fun copy(from: String, to: String, isCopy: Boolean): Flow<CopyProgress> = flow {
        val source = resolveFile(containerSegments(from))
            ?: throw java.io.FileNotFoundException("Not found: $from")
        val destination = resolveFile(containerSegments(to))
            ?: throw java.io.FileNotFoundException("Invalid destination: $to")
        destination.parentFile?.mkdirs()
        FileInputStream(source).use { input ->
            FileOutputStream(destination).use { output ->
                copyStream(input, source.length(), from, isCopy) { data -> output.write(data) }
                    .collect { emit(it) }
            }
        }
    }.flowOn(Dispatchers.IO)

    override fun copy(src: FileSource, destPath: String, isCopy: Boolean): Flow<CopyProgress> = flow {
        val destination = resolveFile(containerSegments(destPath))
            ?: throw java.io.FileNotFoundException("Invalid destination: $destPath")
        destination.parentFile?.mkdirs()
        FileOutputStream(destination).use { output ->
            var offset = 0L
            var copied = 0L
            while (true) {
                val data = src.readChunk(offset, COPY_BUFFER_SIZE)
                if (data.isEmpty()) break
                output.write(data)
                offset += data.size
                copied += data.size
                emit(CopyProgress(src.size, copied, destPath, false, isCopy))
            }
            emit(CopyProgress(src.size, copied, destPath, true, isCopy))
        }
    }.flowOn(Dispatchers.IO)

    // ── Internal ───────────────────────────────────────────────────

    private fun resolveFile(segments: List<String>): File? {
        val name = segments.first()
        val container = ContainerManager.getContainer(appContext, name) ?: return null
        val rootfs = File(container.rootfsPath)
        val relativePath = segments.drop(1).joinToString("/")
        return if (relativePath.isEmpty()) rootfs else File(rootfs, relativePath)
    }

    private fun containerSegments(path: String): List<String> {
        val segments = Path.parse(path).segmentList
        if (segments.isEmpty()) throw IllegalStateException("Cannot operate at container root")
        return segments
    }

    private fun copyStream(
        input: java.io.InputStream,
        totalSize: Long,
        sourcePath: String,
        isCopy: Boolean,
        write: (ByteArray) -> Unit,
    ): Flow<CopyProgress> = flow {
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        var copied = 0L
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            write(buffer.copyOf(read))
            copied += read
            emit(CopyProgress(totalSize, copied, sourcePath, false, isCopy))
        }
        emit(CopyProgress(totalSize, copied, sourcePath, true, isCopy))
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
