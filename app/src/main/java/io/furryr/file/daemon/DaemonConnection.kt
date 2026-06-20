package io.furryr.file.daemon

import android.net.LocalSocket
import android.util.Log
import io.furryr.file.copy.CopyProgress
import io.furryr.file.model.FileEntry
import io.furryr.file.proto.ClosePTYRequest
import io.furryr.file.proto.CloseHandleRequest
import io.furryr.file.proto.CreateDirRequest
import io.furryr.file.proto.CreateFileRequest
import io.furryr.file.proto.DeleteRequest
import io.furryr.file.proto.DupHandleRequest
import io.furryr.file.proto.HandleMode
import io.furryr.file.proto.ListRequest
import io.furryr.file.proto.OpenHandleRequest
import io.furryr.file.proto.RenameRequest
import io.furryr.file.proto.Request
import io.furryr.file.proto.ResizePTYRequest
import io.furryr.file.proto.SeekHandleRequest
import io.furryr.file.proto.SpawnPTYRequest
import io.furryr.file.proto.StatRequest
import io.furryr.file.proto.CopyRequest
import io.furryr.file.proto.TruncateHandleRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FileDescriptor

data class StatResult(
    val usableBytes: Long,
    val totalBytes: Long,
    val exists: Boolean,
    val isDirectory: Boolean
)

data class PtyFd(
    val fileDescriptor: FileDescriptor,
    val ptyId: Long
)

data class OpenHandleResult(val handleId: Long, val size: Long)

class DaemonConnection internal constructor(
    internal val socket: LocalSocket,
    process: Process?
) {
    private val input = DataInputStream(socket.inputStream)
    private val output = DataOutputStream(socket.outputStream)
    private val ioLock = Any()
    private val writeLock = Any()
    internal val daemonProcess: Process? = process

    @Volatile
    private var isClosed = false

    fun isAlive(): Boolean = !isClosed && runCatching { socket.isConnected }.getOrDefault(false)

    fun close() {
        synchronized(ioLock) {
            if (isClosed) return
            isClosed = true
        }
        runCatching { input.close() }
        runCatching { output.close() }
        runCatching { socket.close() }
        daemonProcess?.let { p ->
            if (p.isAlive) {
                runCatching { p.destroy() }
                runCatching { p.destroyForcibly() }
            }
        }
    }

    // ── Core I/O ──────────────────────────────────────────────────────

    /** Send a request and receive a response + optional ancillary fd. */
    fun request(request: Request): DaemonResponse = synchronized(ioLock) {
        var lastError: Exception? = null
        for (attempt in 0 until 2) {
            if (attempt > 0) {
                lastError = null
            }
            if (isClosed) throw IllegalStateException("connection closed")
            try {
                DaemonProtocol.writeRequest(output, request)
                return DaemonProtocol.readResponse(socket)
            } catch (e: PermissionDeniedException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                if (attempt == 0) continue
            }
        }
        throw lastError ?: IllegalStateException("daemon request failed")
    }

    /** Send a streaming request, returning a Flow of CopyProgress. */
    fun requestStream(request: Request): Flow<CopyProgress> = flow {
        val channel = Channel<CopyProgress>(Channel.UNLIMITED)
        CoroutineScope(Dispatchers.IO).launch {
            synchronized(writeLock) {
                if (isClosed) {
                    channel.close()
                    return@launch
                }
                try {
                    DaemonProtocol.writeRequest(output, request)
                    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
                    while (!channel.isClosedForSend) {
                        val resp = DaemonProtocol.readResponseRaw(input) ?: break
                        val p = resp.streamProgress
                        channel.trySend(CopyProgress(
                            totalBytes = p.totalBytes, copiedBytes = p.copiedBytes,
                            currentName = p.currentName, finished = p.finished, isCopy = true,
                        ))
                        if (p.finished || !resp.ok) break
                    }
                } catch (_: Exception) {
                    channel.close()
                }
            }
        }
        channel.consumeAsFlow().collect { emit(it) }
    }.flowOn(Dispatchers.IO)

    // ── File operations ──────────────────────────────────────────────

    fun listFiles(path: String): Result<List<FileEntry>> = runCatching {
        val resp = request(Request.newBuilder()
            .setList(ListRequest.newBuilder().setPath(path))
            .build()).response
        resp.entriesList.map { entry ->
            FileEntry(
                name = entry.name, path = entry.path, isDirectory = entry.isDirectory,
                modifiedAt = entry.modifiedAtMs, isSymlink = entry.isSymlink,
                realPath = entry.realPath, mode = entry.mode, size = entry.size
            )
        }.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    fun createFile(path: String): Result<Unit> = runCatching {
        request(Request.newBuilder()
            .setCreateFile(CreateFileRequest.newBuilder().setPath(path))
            .build())
    }

    fun createDir(path: String): Result<Unit> = runCatching {
        request(Request.newBuilder()
            .setCreateDir(CreateDirRequest.newBuilder().setPath(path))
            .build())
    }

    fun stat(path: String): Result<StatResult> = runCatching {
        val resp = request(Request.newBuilder()
            .setStat(StatRequest.newBuilder().setPath(path))
            .build()).response
        StatResult(
            usableBytes = resp.stat.usableBytes, totalBytes = resp.stat.totalBytes,
            exists = resp.stat.exists, isDirectory = resp.stat.isDirectory
        )
    }

    fun renameEntry(path: String, newName: String): Result<Unit> = runCatching {
        request(Request.newBuilder()
            .setRename(RenameRequest.newBuilder().setPath(path).setNewName(newName))
            .build())
    }

    fun deleteEntry(path: String): Result<Unit> = runCatching {
        request(Request.newBuilder()
            .setDelete(DeleteRequest.newBuilder().setPath(path))
            .build())
    }

    // ── Copy with progress ─────────────────────────────────────────

    fun copy(from: String, to: String): Flow<CopyProgress> {
        val req = Request.newBuilder()
            .setCopy(CopyRequest.newBuilder().setFrom(from).setTo(to))
            .build()
        return requestStream(req)
    }

    // ── Handle operations ────────────────────────────────────────────

    fun openHandle(path: String, mode: HandleMode): Result<OpenHandleResult> = runCatching {
        val resp = request(Request.newBuilder()
            .setOpenHandle(OpenHandleRequest.newBuilder().setPath(path).setMode(mode))
            .build()).response
        val h = resp.openHandle
        OpenHandleResult(handleId = h.handleId, size = h.size)
    }

    fun readHandle(handleId: Long, offset: Long, length: Int) = runCatching {
        request(Request.newBuilder()
            .setReadHandle(io.furryr.file.proto.ReadHandleRequest.newBuilder()
                .setHandleId(handleId).setOffset(offset).setLength(length))
            .build()).response.readHandle
    }

    fun writeHandle(handleId: Long, offset: Long, data: ByteArray): Result<Unit> = runCatching {
        request(Request.newBuilder()
            .setWriteHandle(io.furryr.file.proto.WriteHandleRequest.newBuilder()
                .setHandleId(handleId).setOffset(offset)
                .setData(com.google.protobuf.ByteString.copyFrom(data)))
            .build())
    }

    fun closeHandle(handleId: Long): Result<Unit> = runCatching {
        request(Request.newBuilder()
            .setCloseHandle(CloseHandleRequest.newBuilder().setHandleId(handleId))
            .build())
    }

    fun seekHandle(handleId: Long, position: Long): Result<Unit> = runCatching {
        request(Request.newBuilder()
            .setSeekHandle(SeekHandleRequest.newBuilder().setHandleId(handleId).setPosition(position))
            .build())
    }

    fun dupHandle(handleId: Long): Result<OpenHandleResult> = runCatching {
        val resp = request(Request.newBuilder()
            .setDupHandle(DupHandleRequest.newBuilder().setHandleId(handleId))
            .build()).response
        val h = resp.dupHandle
        OpenHandleResult(handleId = h.handleId, size = h.size)
    }

    fun truncateHandle(handleId: Long, newSize: Long): Result<Unit> = runCatching {
        request(Request.newBuilder()
            .setTruncateHandle(TruncateHandleRequest.newBuilder().setHandleId(handleId).setNewSize(newSize))
            .build())
    }

    // ── PTY ───────────────────────────────────────────────────────────

    fun spawnPty(
        command: String = "/system/bin/sh", args: List<String> = emptyList(),
        env: Map<String, String> = emptyMap(), termType: String = "xterm-256color",
        rows: Int = 24, cols: Int = 80, cwd: String? = null, useRoot: Boolean = false
    ): Result<PtyFd> = runCatching {
        val ptyReq = SpawnPTYRequest.newBuilder()
            .setCommand(command).addAllArgs(args).putAllEnv(env)
            .setTermType(termType).setRows(rows).setCols(cols).setUseRoot(useRoot)
        if (cwd != null) ptyReq.cwd = cwd
        val req = Request.newBuilder().setSpawnPty(ptyReq).build()

        val resp = request(req)
        val fd = resp.attachedFd
            ?: throw IllegalStateException("no ancillary fd received from daemon")
        PtyFd(fileDescriptor = fd, ptyId = resp.response.ptyId)
    }

    fun resizePty(ptyId: Long, rows: Int, cols: Int): Result<Unit> = runCatching {
        request(Request.newBuilder()
            .setResizePty(ResizePTYRequest.newBuilder().setPtyId(ptyId).setRows(rows).setCols(cols))
            .build())
    }

    fun closePty(ptyId: Long): Result<Unit> = runCatching {
        request(Request.newBuilder()
            .setClosePty(ClosePTYRequest.newBuilder().setPtyId(ptyId))
            .build())
    }
}
