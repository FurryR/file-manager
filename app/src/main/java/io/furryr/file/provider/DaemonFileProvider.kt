package io.furryr.file.provider

import io.furryr.file.copy.CopyProgress
import io.furryr.file.copy.FileSource
import io.furryr.file.copy.HandleFileSource
import io.furryr.file.copy.OpenMode
import io.furryr.file.copy.toProto
import io.furryr.file.daemon.DaemonLauncher
import io.furryr.file.model.FileEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

class DaemonFileProvider : FileProvider, PtyCapable {
    override val scheme: String get() = "file"
    override val authority: String? get() = null
    override val label: String get() = "Local Storage"
    override val features: Set<ProviderFeature> get() = ProviderFeature.entries.toSet()

    override fun isAvailable(): Boolean = true

    override suspend fun connect(): Result<Unit> = runCatching {
        DaemonLauncher.connect()
    }

    override suspend fun disconnect() {}

    override suspend fun list(path: String): Result<List<FileEntry>> =
        DaemonLauncher.getConnection().listFiles(path)

    override suspend fun stat(path: String): Result<ProviderStat> = runCatching {
        val s = DaemonLauncher.getConnection().stat(path).getOrThrow()
        ProviderStat(
            usableBytes = s.usableBytes, totalBytes = s.totalBytes,
            exists = s.exists, isDirectory = s.isDirectory,
        )
    }

    override suspend fun createFile(path: String): Result<Unit> =
        DaemonLauncher.getConnection().createFile(path)

    override suspend fun createDirectory(path: String): Result<Unit> =
        DaemonLauncher.getConnection().createDir(path)

    override suspend fun delete(path: String): Result<Unit> =
        DaemonLauncher.getConnection().deleteEntry(path)

    override suspend fun rename(path: String, newName: String): Result<Unit> =
        DaemonLauncher.getConnection().renameEntry(path, newName)

    override suspend fun open(path: String, mode: OpenMode): Result<FileSource> = runCatching {
        val r = DaemonLauncher.getConnection().openHandle(path, mode.toProto()).getOrThrow()
        HandleFileSource(handleId = r.handleId, size = r.size, absolutePath = path, mode = mode)
    }

    override fun copy(from: String, to: String, isCopy: Boolean): Flow<CopyProgress> =
        DaemonLauncher.getConnection().copy(from, to)

    override fun copy(src: FileSource, destPath: String, isCopy: Boolean): Flow<CopyProgress> {
        val srcAbs = src.absolutePath
        return if (srcAbs != null) {
            DaemonLauncher.getConnection().copy(srcAbs, destPath)
        } else {
            chunkedCopyFallback(src, destPath, isCopy)
        }
    }

    // ── PtyCapable ─────────────────────────────────────────────────

    override suspend fun spawnPty(
        command: String, args: List<String>, env: Map<String, String>,
        termType: String, rows: Int, cols: Int, cwd: String?, useRoot: Boolean,
    ): Result<PtyConnection> = runCatching {
        val ptyFd = DaemonLauncher.getConnection(appDaemon = true).spawnPty(
            command = command, args = args, env = env,
            termType = termType, rows = rows, cols = cols, cwd = cwd, useRoot = useRoot
        ).getOrThrow()
        PtyConnection(
            ptyId = ptyFd.ptyId,
            fileDescriptor = ptyFd.fileDescriptor,
            inputStream = java.io.FileInputStream(ptyFd.fileDescriptor),
            outputStream = java.io.FileOutputStream(ptyFd.fileDescriptor),
        )
    }

    override suspend fun resizePty(ptyId: Long, rows: Int, cols: Int): Result<Unit> =
        DaemonLauncher.getConnection(appDaemon = true).resizePty(ptyId, rows, cols)

    override suspend fun closePty(ptyId: Long): Result<Unit> =
        DaemonLauncher.getConnection(appDaemon = true).closePty(ptyId)
}

// Chunked copy fallback for stream-based sources (no direct path)
private fun chunkedCopyFallback(src: FileSource, destPath: String, isCopy: Boolean): Flow<CopyProgress> = flow {
    val conn = DaemonLauncher.getConnection()
    val dstHandle = conn.openHandle(destPath, io.furryr.file.proto.HandleMode.WRITE).getOrThrow()
    try {
        var offset = 0L
        val chunkSize = 256 * 1024
        var copied = 0L
        while (true) {
            val data = src.readChunk(offset, chunkSize)
            if (data.isEmpty()) break
            conn.writeHandle(dstHandle.handleId, offset, data).getOrThrow()
            offset += data.size
            copied += data.size
            emit(CopyProgress(src.size, copied, src.absolutePath ?: "", copied >= src.size, isCopy))
            if (copied >= src.size) break
        }
        if (copied >= src.size && src.size == 0L) {
            emit(CopyProgress(0, 0, src.absolutePath ?: "", true, isCopy))
        }
    } finally {
        runCatching { conn.closeHandle(dstHandle.handleId) }
    }
}.flowOn(Dispatchers.IO)
