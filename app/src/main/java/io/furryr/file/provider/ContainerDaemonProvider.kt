package io.furryr.file.provider

import android.content.Context
import io.furryr.file.agent.ContainerManager
import io.furryr.file.copy.CopyProgress
import io.furryr.file.copy.FileSource
import io.furryr.file.copy.HandleFileSource
import io.furryr.file.copy.OpenMode
import io.furryr.file.copy.toProto
import io.furryr.file.daemon.DaemonConnection
import io.furryr.file.daemon.DaemonLauncher
import io.furryr.file.model.FileEntry
import io.furryr.file.proto.CreateDirRequest
import io.furryr.file.proto.CreateFileRequest
import io.furryr.file.proto.DeleteRequest
import io.furryr.file.proto.DupHandleRequest
import io.furryr.file.proto.ListRequest
import io.furryr.file.proto.OpenHandleRequest
import io.furryr.file.proto.RenameRequest
import io.furryr.file.proto.Request
import io.furryr.file.proto.StatRequest
import io.furryr.file.proto.CopyRequest
import io.furryr.file.util.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import java.io.File

object ContainerDaemonProvider : FileProvider, PtyCapable {
    private const val TAG = "ContainerDaemonProvider"
    private lateinit var appContext: Context

    private val connLock = Any()
    private var connection: DaemonConnection? = null

    override val scheme: String get() = "container"
    override val authority: String? get() = null
    override val label: String get() = "Container (daemon)"
    override val features: Set<ProviderFeature> get() = ProviderFeature.entries.toSet()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    override fun isAvailable(): Boolean = connection != null

    override suspend fun connect(): Result<Unit> = runCatching {
        ensureConnected()
    }

    override suspend fun disconnect() {
        doDisconnect()
    }

    override suspend fun list(path: String): Result<List<FileEntry>> = runCatching {
        val segments = Path.parse(path).segmentList
        if (segments.isEmpty()) {
            listContainerRoot()
        } else {
            val localPath = resolveLocalPath(segments)
            val response = send(Request.newBuilder()
                .setList(ListRequest.newBuilder().setPath(localPath))
                .build())
            val baseUri = innerPathToUri(path)
            response.entriesList.map { entry ->
                FileEntry(
                    name = entry.name, path = "$baseUri/${entry.name}",
                    isDirectory = entry.isDirectory, modifiedAt = entry.modifiedAtMs,
                    isSymlink = entry.isSymlink, realPath = entry.realPath,
                    mode = entry.mode, size = entry.size,
                )
            }.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
        }
    }

    override suspend fun stat(path: String): Result<ProviderStat> = runCatching {
        val segments = Path.parse(path).segmentList
        val localPath = if (segments.isEmpty()) {
            File(appContext.filesDir, "containers").absolutePath
        } else {
            resolveLocalPath(segments)
        }
        val response = send(Request.newBuilder()
            .setStat(StatRequest.newBuilder().setPath(localPath))
            .build())
        ProviderStat(
            usableBytes = response.stat.usableBytes, totalBytes = response.stat.totalBytes,
            exists = response.stat.exists, isDirectory = response.stat.isDirectory,
        )
    }

    override suspend fun createFile(path: String): Result<Unit> = runCatching {
        val segments = Path.parse(path).segmentList
        if (segments.isEmpty()) throw IllegalStateException("Cannot create file at container root")
        send(Request.newBuilder()
            .setCreateFile(CreateFileRequest.newBuilder().setPath(resolveLocalPath(segments)))
            .build())
    }

    override suspend fun createDirectory(path: String): Result<Unit> = runCatching {
        val segments = Path.parse(path).segmentList
        if (segments.isEmpty()) throw IllegalStateException("Cannot create directory at container root")
        send(Request.newBuilder()
            .setCreateDir(CreateDirRequest.newBuilder().setPath(resolveLocalPath(segments)))
            .build())
    }

    override suspend fun delete(path: String): Result<Unit> = runCatching {
        val segments = Path.parse(path).segmentList
        if (segments.isEmpty()) throw IllegalStateException("Cannot delete container root")
        send(Request.newBuilder()
            .setDelete(DeleteRequest.newBuilder().setPath(resolveLocalPath(segments)))
            .build())
    }

    override suspend fun rename(path: String, newName: String): Result<Unit> = runCatching {
        val segments = Path.parse(path).segmentList
        if (segments.isEmpty()) throw IllegalStateException("Cannot rename at container root")
        send(Request.newBuilder()
            .setRename(RenameRequest.newBuilder().setPath(resolveLocalPath(segments)).setNewName(newName))
            .build())
    }

    override suspend fun open(path: String, mode: OpenMode): Result<FileSource> = runCatching {
        val segments = containerSegments(path)
        val localPath = resolveLocalPath(segments)
        val response = send(Request.newBuilder()
            .setOpenHandle(OpenHandleRequest.newBuilder().setPath(localPath).setMode(mode.toProto()))
            .build())
        val h = response.openHandle
        HandleFileSource(handleId = h.handleId, size = h.size, absolutePath = localPath, mode = mode)
    }

    override fun copy(from: String, to: String, isCopy: Boolean): Flow<CopyProgress> {
        // from/to are inner paths (e.g. "/test/sub/file") — resolve to real rootfs
        val fromLocal = resolveLocalPath(containerSegments(from))
        val toLocal = resolveLocalPath(containerSegments(to))
        return copyInternal(fromLocal, toLocal)
    }

    override fun copy(src: FileSource, destPath: String, isCopy: Boolean): Flow<CopyProgress> {
        // destPath is an inner path (e.g. "/test/foo.txt") — resolve to real rootfs
        val toLocal = resolveLocalPath(containerSegments(destPath))
        return chunkedCopyFallback(src, toLocal, isCopy)
    }

    override suspend fun spawnPty(
        command: String, args: List<String>, env: Map<String, String>,
        termType: String, rows: Int, cols: Int, cwd: String?, useRoot: Boolean,
    ): Result<PtyConnection> =
        Result.failure(UnsupportedOperationException("Container PTY not supported"))

    override suspend fun resizePty(ptyId: Long, rows: Int, cols: Int): Result<Unit> =
        Result.failure(UnsupportedOperationException())

    override suspend fun closePty(ptyId: Long): Result<Unit> =
        Result.failure(UnsupportedOperationException())

    // ── Internal ───────────────────────────────────────────────────

    private fun ensureConnected(): DaemonConnection = synchronized(connLock) {
        connection?.let { conn ->
            if (conn.isAlive()) return@ensureConnected conn
            conn.close()
        }
        val conn = DaemonLauncher.connectAppUid()
        connection = conn
        conn
    }

    private fun send(request: Request): io.furryr.file.proto.Response = synchronized(connLock) {
        var conn = ensureConnected()
        var lastError: Exception? = null
        for (attempt in 0 until 2) {
            if (attempt > 0) { conn.close(); connection = null; conn = ensureConnected() }
            try {
                return conn.request(request).response
            } catch (e: io.furryr.file.daemon.PermissionDeniedException) { throw e
            } catch (e: Exception) { lastError = e; if (attempt == 0) continue }
        }
        throw lastError ?: IllegalStateException("container daemon send failed")
    }

    private fun doDisconnect() = synchronized(connLock) {
        connection?.close()
        connection = null
    }

    private fun resolveLocalPath(segments: List<String>): String {
        val name = segments.first()
        val container = ContainerManager.getContainer(appContext, name)
            ?: throw IllegalStateException("Container '$name' not found")
        val rootfs = container.rootfsPath.trimEnd('/')
        val subPath = segments.drop(1).joinToString("/")
        return if (subPath.isEmpty()) rootfs else "$rootfs/$subPath"
    }

    private fun resolvePath(raw: String): String {
        if (raw.startsWith("/")) return raw
        val segments = Path.parse(raw).segmentList
        return resolveLocalPath(segments)
    }

    private fun containerSegments(raw: String): List<String> {
        val segments = Path.parse(raw).segmentList
        if (segments.isEmpty()) throw IllegalStateException("Cannot operate at container root")
        return segments
    }

    private fun copyInternal(from: String, to: String): Flow<CopyProgress> {
        val request = Request.newBuilder()
            .setCopy(CopyRequest.newBuilder().setFrom(from).setTo(to))
            .build()
        val conn = ensureConnected()
        return conn.requestStream(request)
    }

    private fun chunkedCopyFallback(src: FileSource, destPath: String, isCopy: Boolean): Flow<CopyProgress> = flow {
        val h = send(Request.newBuilder()
            .setOpenHandle(OpenHandleRequest.newBuilder().setPath(destPath).setMode(io.furryr.file.proto.HandleMode.WRITE))
            .build()).openHandle
        try {
            var offset = 0L
            var copied = 0L
            while (true) {
                val data = src.readChunk(offset, 256 * 1024)
                if (data.isEmpty()) break
                send(Request.newBuilder()
                    .setWriteHandle(io.furryr.file.proto.WriteHandleRequest.newBuilder()
                        .setHandleId(h.handleId).setOffset(offset)
                        .setData(com.google.protobuf.ByteString.copyFrom(data)))
                    .build())
                offset += data.size
                copied += data.size
                emit(CopyProgress(src.size, copied, src.absolutePath ?: "", copied >= src.size, isCopy))
                if (copied >= src.size) break
            }
            // Always emit finished event (handles 0-byte and short-circuit edge cases)
            if (copied >= src.size && src.size == 0L) {
                emit(CopyProgress(0, 0, src.absolutePath ?: "", true, isCopy))
            }
        } finally {
            runCatching {
                send(Request.newBuilder()
                    .setCloseHandle(io.furryr.file.proto.CloseHandleRequest.newBuilder().setHandleId(h.handleId))
                    .build())
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun listContainerRoot(): List<FileEntry> {
        val containersDir = File(appContext.filesDir, "containers")
        if (!containersDir.isDirectory) return emptyList()

        val containers = ContainerManager.listContainers(appContext).getOrDefault(emptyList())
        return containers.map { container ->
            FileEntry(
                name = container.name,
                path = "$scheme://${container.name}",
                isDirectory = true,
                modifiedAt = container.createdAt,
                isSymlink = false,
                realPath = "$scheme://${container.name}",
                mode = 0x41ED, // drwxr-xr-x
                size = 0,
            )
        }.sortedBy { it.name.lowercase() }
    }

    private fun innerPathToUri(innerPath: String): String {
        return "${scheme}://${innerPath.trimStart('/')}"
    }
}
