package io.furryr.file.core.ipc

import android.system.Os
import android.util.Log
import io.furryr.file.core.DaemonIdentity
import io.furryr.file.core.PermissionDeniedException
import io.furryr.file.core.model.CopyProgress
import io.furryr.file.core.model.ExecResult
import io.furryr.file.core.model.FileEntry
import io.furryr.file.core.model.ProviderStat
import io.furryr.file.core.source.FdFileSource
import io.furryr.file.core.source.FileSource
import io.furryr.file.core.source.HandleFileSource
import io.furryr.file.proto.ClosePTYRequest
import io.furryr.file.proto.CloseHandleRequest
import io.furryr.file.proto.CancelRequest
import io.furryr.file.proto.CreateDirRequest
import io.furryr.file.proto.CreateFileRequest
import io.furryr.file.proto.DeleteRequest
import io.furryr.file.proto.DupHandleRequest
import io.furryr.file.proto.ExitRequest
import io.furryr.file.proto.HandleMode
import io.furryr.file.proto.KillPtyRequest
import io.furryr.file.proto.ListRequest
import io.furryr.file.proto.OpenHandleRequest
import io.furryr.file.proto.RenameRequest
import io.furryr.file.proto.Request
import io.furryr.file.proto.ResizePTYRequest
import io.furryr.file.proto.SeekHandleRequest
import io.furryr.file.proto.SignalPtyRequest
import io.furryr.file.proto.SpawnPTYRequest
import io.furryr.file.proto.StatRequest
import io.furryr.file.proto.CopyRequest
import io.furryr.file.proto.CopyFromSourceRequest
import io.furryr.file.proto.TruncateHandleRequest
import io.furryr.file.proto.WaitPtyRequest
import io.furryr.file.proto.ReadSourceResponse
import io.furryr.file.proto.Response
import io.furryr.file.uds.Frame
import io.furryr.file.uds.Transport
import io.furryr.file.uds.TransportEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.EOFException
import java.io.FileDescriptor
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

/** Default bound for control requests that should never take long (open/stat/list/…). */
internal const val OPEN_TIMEOUT_MS = 20_000L

internal data class PtyFd(
    val fileDescriptor: FileDescriptor,
    val ptyId: Long,
    val childPid: Int
)

internal data class OpenHandleResult(
    val handleId: Long,
    val size: Long,
    val fileDescriptor: FileDescriptor? = null,
)

/**
 * Protocol layer over a proto-agnostic [Transport]: it owns request/response
 * correlation, streaming, source-read brokering and handle/pty decoding. The
 * socket, framing and SCM_RIGHTS all live in `:uds`; disconnects are terminal.
 *
 * Blocking by design — the public facades ([io.furryr.file.core.file.FileService],
 * [io.furryr.file.core.process.ExecService]) stay synchronous; sends bridge into
 * the transport's coroutine world.
 */
internal class DaemonConnection internal constructor(
    private val identity: DaemonIdentity,
    private val transport: Transport,
    process: Process?,
) {
    internal val socketName: String get() = identity.socketName
    internal val key: String get() = identity.key
    internal val daemonProcess: Process? = process

    @Volatile
    private var isClosed = false

    /** Set once the transport is lost; this connection is never reused. */
    @Volatile
    private var dead = false

    // ── Async request dispatch ──────────────────────────────────────

    private val requestIdCounter = AtomicLong(1)

    private sealed class PendingRequest {
        class Single(val future: CompletableFuture<DaemonResponse>) : PendingRequest()
        class Stream(val channel: Channel<DaemonResponse>) : PendingRequest()
    }

    private val pendingRequests = HashMap<Long, PendingRequest>()
    private val sourceReaders = HashMap<Long, FileSource>()
    private val pendingLock = Any()
    private val closeLock = Any()
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

    private suspend fun readerLoop() {
        try {
            transport.events.collect { event ->
                when (event) {
                    is TransportEvent.Incoming -> dispatchResponse(event.frame)
                    is TransportEvent.Closed -> {
                        dead = true
                        failAllPending(event.cause ?: EOFException("daemon connection lost"))
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "daemon reader failed", e)
        } finally {
            dead = true
            failAllPending(EOFException("daemon connection lost"))
            transport.close()
        }
    }

    private fun dispatchResponse(frame: Frame) {
        // The daemon protocol attaches at most one descriptor; drop the rest.
        if (frame.fds.size > 1) {
            frame.fds.drop(1).forEach { runCatching { Os.close(it) } }
        }
        val fd = frame.fds.firstOrNull()
        val decoded = try {
            DaemonResponse(Response.parseFrom(frame.payload), fd)
        } catch (e: Exception) {
            fd?.let { runCatching { Os.close(it) } }
            throw e
        }

        decoded.response.readSourceRequest?.let { readRequest ->
            val source = synchronized(pendingLock) { sourceReaders[decoded.response.id] }
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

        val id = decoded.response.id
        synchronized(pendingLock) {
            val pending = pendingRequests[id]
            if (pending == null) {
                fd?.let { runCatching { Os.close(it) } }
                return
            }
            when (pending) {
                is PendingRequest.Single -> {
                    pendingRequests.remove(id)
                    pending.future.complete(decoded)
                }
                is PendingRequest.Stream -> {
                    val prog = decoded.response.streamProgress
                    if (prog != null && prog.finished) {
                        pendingRequests.remove(id)
                        pending.channel.trySend(decoded)
                        pending.channel.close()
                    } else {
                        pending.channel.trySend(decoded)
                    }
                }
            }
        }
    }

    private fun failAllPending(cause: Throwable) {
        synchronized(pendingLock) {
            val err = IllegalStateException("daemon connection lost", cause)
            pendingRequests.values.forEach {
                when (it) {
                    is PendingRequest.Single -> it.future.completeExceptionally(err)
                    is PendingRequest.Stream -> it.channel.close(err)
                }
            }
            pendingRequests.clear()
            sourceReaders.clear()
        }
    }

    /** A dropped connection is terminal; later operations need a fresh daemon. */
    fun isAlive(): Boolean = !isClosed && !dead

    /**
     * Ask the daemon to shut down (graceful). The daemon serves multiple clients
     * and also exits when its last authenticated client disconnects.
     */
    fun exit() {
        val req = Request.newBuilder()
            .setId(requestIdCounter.getAndIncrement())
            .setExit(ExitRequest.getDefaultInstance())
            .build()
        runCatching { rawSend(req) }
    }

    /** Disconnect only this client, leaving other authenticated clients alone. */
    fun close() {
        synchronized(closeLock) {
            if (isClosed) return
            isClosed = true
        }
        failAllPending(EOFException("connection closed"))
        runCatching { transport.close() }
        scope.cancel()
    }

    /** Explicitly stop the entire daemon, then release this connection. */
    fun shutdown() {
        if (!isClosed) exit()
        close()
        daemonProcess?.let { p ->
            // Shizuku's remote Process throws from isAlive()/exitValue() when
            // the process has not exited, so guard the whole teardown.
            runCatching {
                if (p.isAlive && !p.waitFor(1500, TimeUnit.MILLISECONDS)) {
                    p.destroyForcibly()
                }
            }.onFailure { Log.w(TAG, "Failed to stop daemon process", it) }
        }
    }

    private val TAG = "DaemonConnection"

    // ── Core I/O ──────────────────────────────────────────────────────

    private fun rawSend(request: Request, fds: List<FileDescriptor> = emptyList()) {
        check(isAlive()) { "connection closed" }
        runBlocking { transport.send(request.toByteArray(), fds) }
    }

    /** Send a request and wait for the matching response. */
    fun request(request: Request): DaemonResponse = request(request, 0L)

    /**
     * Send a request and wait for the matching response.
     *
     * @param timeoutMillis 0 means wait indefinitely; a positive value bounds
     *        the wait so a wedged daemon cannot hang the caller forever.
     */
    fun request(request: Request, timeoutMillis: Long, fds: List<FileDescriptor> = emptyList()): DaemonResponse {
        if (!isAlive()) throw IllegalStateException("connection closed")
        val id = requestIdCounter.getAndIncrement()
        val req = request.toBuilder().setId(id).build()
        val future = CompletableFuture<DaemonResponse>()

        synchronized(pendingLock) {
            check(isAlive()) { "connection closed" }
            pendingRequests[id] = PendingRequest.Single(future)
        }
        try {
            rawSend(req, fds)
        } catch (e: Exception) {
            synchronized(pendingLock) { pendingRequests.remove(id) }
            throw e
        }

        val resp = try {
            if (timeoutMillis > 0L) future.get(timeoutMillis, TimeUnit.MILLISECONDS)
            else future.get()
        } catch (e: InterruptedException) {
            synchronized(pendingLock) { pendingRequests.remove(id) }
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

    /** Allocate a request id for a stream whose handle is created by the caller. */
    fun allocateRequestId(): Long = requestIdCounter.getAndIncrement()

    /** Ask the daemon to abort an in-flight streaming request by id. */
    fun cancel(targetId: Long): Result<Unit> = runCatching {
        request(Request.newBuilder()
            .setCancel(CancelRequest.newBuilder().setTargetId(targetId))
            .build())
    }

    /** Send a streaming request, returning a Flow of CopyProgress. */
    fun requestStream(request: Request, isCopy: Boolean = true): Flow<CopyProgress> =
        requestStream(request, allocateRequestId(), isCopy)

    internal fun requestStream(request: Request, id: Long, isCopy: Boolean): Flow<CopyProgress> = flow {
        val req = request.toBuilder().setId(id).build()
        val channel = Channel<DaemonResponse>(Channel.UNLIMITED)

        synchronized(pendingLock) {
            check(isAlive()) { "connection closed" }
            pendingRequests[id] = PendingRequest.Stream(channel)
        }
        try {
            rawSend(req)
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
        } finally {
            synchronized(pendingLock) { pendingRequests.remove(id) }
        }
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

    fun stat(path: String): Result<ProviderStat> = runCatching {
        val resp = request(Request.newBuilder()
            .setStat(StatRequest.newBuilder().setPath(path))
            .build()).response
        ProviderStat(
            usableBytes = resp.stat.usableBytes, totalBytes = resp.stat.totalBytes,
            exists = resp.stat.exists, isDirectory = resp.stat.isDirectory,
            canCreateChildren = resp.stat.canCreateChildren, canEnter = resp.stat.canEnter
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

    fun copy(from: String, to: String, isCopy: Boolean = true): Flow<CopyProgress> =
        copyWithId(allocateRequestId(), from, to, isCopy)

    internal fun copyWithId(id: Long, from: String, to: String, isCopy: Boolean): Flow<CopyProgress> {
        val req = Request.newBuilder()
            .setCopy(CopyRequest.newBuilder().setFrom(from).setTo(to))
            .build()
        return requestStream(req, id, isCopy)
    }

    /** Destination-driven copy. Real FDs travel as SCM_RIGHTS; virtual handles are pulled. */
    fun copyFromSource(source: FileSource, to: String, isCopy: Boolean = true): Flow<CopyProgress> =
        copyFromSource(source, to, allocateRequestId(), isCopy)

    internal fun copyFromSource(source: FileSource, to: String, id: Long, isCopy: Boolean): Flow<CopyProgress> = flow {
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
            check(isAlive()) { "connection closed" }
            pendingRequests[id] = PendingRequest.Stream(channel)
            if (source is HandleFileSource) sourceReaders[id] = source
        }
        try {
            rawSend(req, listOfNotNull((source as? FdFileSource)?.fileDescriptor))
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
        rows: Int = 24, cols: Int = 80, cwd: String? = null, user: String? = null,
        nice: Int? = null, cwdFd: FileDescriptor? = null,
    ): Result<PtyFd> = runCatching {
        val ptyReq = SpawnPTYRequest.newBuilder()
            .setCommand(command).addAllArgs(args).putAllEnv(env)
            .setTermType(termType).setRows(rows).setCols(cols).setCwdFd(cwdFd != null)
        if (cwd != null) ptyReq.cwd = cwd
        if (user != null) ptyReq.user = user
        if (nice != null) ptyReq.nice = nice
        val req = Request.newBuilder().setSpawnPty(ptyReq).build()

        val resp = request(req, 0L, listOfNotNull(cwdFd))
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

    /** Deliver an arbitrary [signal] to the PTY's process group. */
    fun signalPty(ptyId: Long, signal: Int): Result<Unit> = runCatching {
        request(Request.newBuilder()
            .setSignalPty(SignalPtyRequest.newBuilder().setPtyId(ptyId).setSignal(signal))
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
