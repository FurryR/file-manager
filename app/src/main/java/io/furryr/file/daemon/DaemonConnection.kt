package io.furryr.file.daemon

import android.util.Log
import io.furryr.file.copy.CopyProgress
import io.furryr.file.copy.FdFileSource
import io.furryr.file.model.FileEntry
import io.furryr.file.copy.FileSource
import io.furryr.file.copy.HandleFileSource
import io.furryr.file.proto.ClosePTYRequest
import io.furryr.file.proto.CloseHandleRequest
import io.furryr.file.proto.CreateDirRequest
import io.furryr.file.proto.CreateFileRequest
import io.furryr.file.proto.DeleteRequest
import io.furryr.file.proto.DupHandleRequest
import io.furryr.file.proto.HandleMode
import io.furryr.file.proto.KillPtyRequest
import io.furryr.file.proto.ListRequest
import io.furryr.file.proto.OpenHandleRequest
import io.furryr.file.proto.RenameRequest
import io.furryr.file.proto.Request
import io.furryr.file.proto.ResizePTYRequest
import io.furryr.file.proto.SeekHandleRequest
import io.furryr.file.proto.SpawnPTYRequest
import io.furryr.file.proto.StatRequest
import io.furryr.file.proto.CopyRequest
import io.furryr.file.proto.CopyFromSourceRequest
import io.furryr.file.proto.TruncateHandleRequest
import io.furryr.file.proto.WaitPtyRequest
import io.furryr.file.proto.ReadSourceResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import java.io.EOFException
import java.io.FileDescriptor
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

/** Default bound for control requests that should never take long (open/stat/list/…). */
const val OPEN_TIMEOUT_MS = 20_000L

data class StatResult(
    val usableBytes: Long,
    val totalBytes: Long,
    val exists: Boolean,
    val isDirectory: Boolean
)

data class PtyFd(
    val fileDescriptor: FileDescriptor,
    val ptyId: Long,
    val childPid: Int
)

data class OpenHandleResult(
    val handleId: Long,
    val size: Long,
    val fileDescriptor: FileDescriptor? = null,
)

data class ExecResult(
    val exitCode: Int,
    val stdout: ByteArray,
    val stderr: ByteArray,
) {
    val stdoutText: String get() = String(stdout, Charsets.UTF_8)
    val stderrText: String get() = String(stderr, Charsets.UTF_8)
}

class DaemonConnection internal constructor(
    internal val socket: OsSocket,
    process: Process?,
) {
    internal val daemonProcess: Process? = process

    @Volatile
    private var isClosed = false

    // ── Async request dispatch ──────────────────────────────────────

    private val requestIdCounter = AtomicLong(1)

    private sealed class PendingRequest {
        class Single(val future: CompletableFuture<DaemonResponse>) : PendingRequest()
        class Stream(val channel: Channel<DaemonResponse>) : PendingRequest()
    }

    private val pendingRequests = HashMap<Long, PendingRequest>()
    private val sourceReaders = HashMap<Long, FileSource>()
    private val pendingLock = Any()
    private val writeLock = Any()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        scope.launch { readerLoop() }
        // Surface daemon/proot stderr in logcat — invaluable when a container
        // daemon fails to come up.
        daemonProcess?.let { process ->
            scope.launch(Dispatchers.IO) {
                runCatching {
                    process.inputStream.bufferedReader().forEachLine { line ->
                        Log.d("DaemonConnection/proc", line)
                    }
                }
            }
        }
    }

    private fun readerLoop() {
        try {
            while (true) {
                val resp = socket.readFrame()
                dispatchResponse(resp)
            }
        } catch (_: EOFException) {
            // normal shutdown
        } catch (e: Exception) {
            if (!isClosed) {
                Log.w(TAG, "reader error", e)
                failAllPending(e)
            }
        }
    }

    private fun dispatchResponse(resp: DaemonResponse) {
        resp.response.readSourceRequest?.let { readRequest ->
            val source = synchronized(pendingLock) { sourceReaders[resp.response.id] }
            if (source != null) {
                scope.launch(Dispatchers.IO) {
                    val result = runCatching { source.readChunk(readRequest.offset, readRequest.length) }
                    val response = ReadSourceResponse.newBuilder()
                        .setRequestId(readRequest.requestId)
                    result.onSuccess { data ->
                        response.setData(com.google.protobuf.ByteString.copyFrom(data))
                            .setEof(data.isEmpty() || readRequest.offset + data.size >= source.size)
                    }.onFailure { error ->
                        response.setError(error.message ?: "source read failed")
                    }
                    runCatching {
                        request(Request.newBuilder().setReadSourceResponse(response).build())
                    }
                }
            }
        }
        val id = resp.response.id
        synchronized(pendingLock) {
            val pending = pendingRequests[id] ?: return
            when (pending) {
                is PendingRequest.Single -> {
                    pendingRequests.remove(id)
                    pending.future.complete(resp)
                }
                is PendingRequest.Stream -> {
                    val prog = resp.response.streamProgress
                    if (prog != null && prog.finished) {
                        pendingRequests.remove(id)
                        pending.channel.trySend(resp)
                        pending.channel.close()
                    } else {
                        pending.channel.trySend(resp)
                    }
                }
            }
        }
    }

    private fun failAllPending(cause: Exception) {
        synchronized(pendingLock) {
            val err = IllegalStateException("daemon connection lost", cause)
            pendingRequests.values.forEach {
                when (it) {
                    is PendingRequest.Single -> it.future.completeExceptionally(err)
                    is PendingRequest.Stream -> it.channel.close(err)
                }
            }
            pendingRequests.clear()
        }
    }

    fun isAlive(): Boolean = !isClosed && socket.isOpen

    fun close() {
        synchronized(writeLock) {
            if (isClosed) return
            isClosed = true
        }
        failAllPending(EOFException("connection closed"))
        // Closing the socket makes the daemon read EOF and exit its loop, which
        // tears down its whole process tree (close_all). Give it a moment, then
        // force-kill if it is still around.
        runCatching { socket.close() }
        scope.cancel()
        daemonProcess?.let { p ->
            // Shizuku's remote Process throws from isAlive()/exitValue() when
            // the process has not exited, so guard the whole teardown.
            runCatching {
                if (p.isAlive && !p.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    p.destroyForcibly()
                }
            }
        }
    }

    private val TAG = "DaemonConnection"

    // ── Core I/O ──────────────────────────────────────────────────────

    /** Send a request and wait for the matching response. */
    fun request(request: Request): DaemonResponse = request(request, 0L)

    /**
     * Send a request and wait for the matching response.
     *
     * @param timeoutMillis 0 means wait indefinitely; a positive value bounds
     *        the wait so a wedged daemon cannot hang the caller forever.
     */
    fun request(request: Request, timeoutMillis: Long): DaemonResponse {
        val id = requestIdCounter.getAndIncrement()
        val req = request.toBuilder().setId(id).build()
        val future = CompletableFuture<DaemonResponse>()

        synchronized(pendingLock) { pendingRequests[id] = PendingRequest.Single(future) }
        synchronized(writeLock) {
            if (isClosed) throw IllegalStateException("connection closed")
            socket.writeFrame(req.toByteArray())
        }

        val resp = try {
            if (timeoutMillis > 0L) future.get(timeoutMillis, TimeUnit.MILLISECONDS)
            else future.get()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("interrupted", e)
        } catch (e: TimeoutException) {
            synchronized(pendingLock) { pendingRequests.remove(id) }
            throw IllegalStateException("daemon request timed out after ${timeoutMillis}ms", e)
        } catch (e: ExecutionException) {
            throw e.cause as? Exception ?: IllegalStateException("daemon request failed", e)
        }

        if (!resp.response.ok) {
            throw PermissionDeniedException(resp.response.error.ifBlank { "daemon returned error" })
        }
        return resp
    }

    /** Send a streaming request, returning a Flow of CopyProgress. */
    fun requestStream(request: Request, isCopy: Boolean = true): Flow<CopyProgress> = flow {
        val id = requestIdCounter.getAndIncrement()
        val req = request.toBuilder().setId(id).build()
        val channel = Channel<DaemonResponse>(Channel.UNLIMITED)

        synchronized(pendingLock) { pendingRequests[id] = PendingRequest.Stream(channel) }
        synchronized(writeLock) {
            if (isClosed) throw IllegalStateException("connection closed")
            socket.writeFrame(req.toByteArray())
        }

        for (resp in channel) {
            if (!resp.response.ok) {
                throw IllegalStateException(resp.response.error.ifBlank { "daemon stream request failed" })
            }
            val p = resp.response.streamProgress ?: continue
            emit(CopyProgress(
                totalBytes = p.totalBytes, copiedBytes = p.copiedBytes,
                currentName = p.currentName, finished = p.finished, isCopy = isCopy,
            ))
            if (p.finished) break
        }

        synchronized(pendingLock) { pendingRequests.remove(id) }
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

    fun copy(from: String, to: String, isCopy: Boolean = true): Flow<CopyProgress> {
        val req = Request.newBuilder()
            .setCopy(CopyRequest.newBuilder().setFrom(from).setTo(to))
            .build()
        return requestStream(req, isCopy)
    }

    /** Destination-driven copy. Real FDs travel as SCM_RIGHTS; virtual handles are pulled. */
    fun copyFromSource(source: FileSource, to: String, isCopy: Boolean = true): Flow<CopyProgress> = flow {
        val id = requestIdCounter.getAndIncrement()
        val req = Request.newBuilder()
            .setId(id)
            .setCopyFromSource(CopyFromSourceRequest.newBuilder()
                .setTo(to)
                .setSourceSize(source.size)
                .setVirtualSource(source is HandleFileSource))
            .build()
        val channel = Channel<DaemonResponse>(Channel.UNLIMITED)
        if (source !is FdFileSource && source !is HandleFileSource) {
            throw UnsupportedOperationException("source cannot be sent to daemon")
        }
        synchronized(pendingLock) {
            pendingRequests[id] = PendingRequest.Stream(channel)
            if (source is HandleFileSource) sourceReaders[id] = source
        }
        try {
            synchronized(writeLock) {
                if (isClosed) throw IllegalStateException("connection closed")
                socket.writeFrame(req.toByteArray(), (source as? FdFileSource)?.fileDescriptor)
            }
            for (resp in channel) {
                if (!resp.response.ok) {
                    throw IllegalStateException(resp.response.error.ifBlank { "daemon copy failed" })
                }
                val progress = resp.response.streamProgress ?: continue
                emit(CopyProgress(
                    totalBytes = progress.totalBytes,
                    copiedBytes = progress.copiedBytes,
                    currentName = progress.currentName,
                    finished = progress.finished,
                    isCopy = isCopy,
                ))
                if (progress.finished) break
            }
        } finally {
            synchronized(pendingLock) {
                pendingRequests.remove(id)
                sourceReaders.remove(id)
            }
        }
    }.flowOn(Dispatchers.IO)

    // ── Handle operations ────────────────────────────────────────────

    fun openHandle(
        path: String,
        mode: HandleMode,
        timeoutMillis: Long = OPEN_TIMEOUT_MS,
        wantFd: Boolean = true,
    ): Result<OpenHandleResult> = runCatching {
        val daemonResponse = request(Request.newBuilder()
            .setOpenHandle(OpenHandleRequest.newBuilder().setPath(path).setMode(mode).setWantFd(wantFd))
            .build(), timeoutMillis)
        val h = daemonResponse.response.openHandle
        OpenHandleResult(
            handleId = h.handleId,
            size = h.size,
            fileDescriptor = daemonResponse.attachedFd,
        )
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
        PtyFd(
            fileDescriptor = fd,
            ptyId = resp.response.ptyId,
            childPid = resp.response.childPid.toInt()
        )
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

    fun waitPty(ptyId: Long): Result<Int> = runCatching {
        val resp = request(Request.newBuilder()
            .setWaitPty(WaitPtyRequest.newBuilder().setPtyId(ptyId))
            .build())
        resp.response.exitCode.toInt()
    }

    fun killPty(ptyId: Long): Result<Unit> = runCatching {
        request(Request.newBuilder()
            .setKillPty(KillPtyRequest.newBuilder().setPtyId(ptyId))
            .build())
    }

    // ── Non-PTY exec ──────────────────────────────────────────────────

    fun exec(
        command: String,
        args: List<String> = emptyList(),
        cwd: String? = null,
        env: Map<String, String> = emptyMap(),
    ): Result<ExecResult> = runCatching {
        val execReq = io.furryr.file.proto.ExecRequest.newBuilder()
            .setCommand(command).addAllArgs(args).putAllEnv(env)
        if (cwd != null) execReq.cwd = cwd
        val resp = request(Request.newBuilder().setExec(execReq).build()).response.exec
        ExecResult(
            exitCode = resp.exitCode,
            stdout = resp.stdout.toByteArray(),
            stderr = resp.stderr.toByteArray(),
        )
    }

}
