package io.furryr.file.daemon.ipc

import android.system.Os
import android.util.Log
import io.furryr.file.daemon.DaemonIdentity
import io.furryr.file.core.PermissionDeniedException
import io.furryr.file.core.model.CopyProgress
import io.furryr.file.daemon.model.ExecResult
import io.furryr.file.core.model.FileEntry
import io.furryr.file.core.model.ProviderStat
import io.furryr.file.daemon.source.FdFileSource
import io.furryr.file.core.source.FileSource
import io.furryr.file.daemon.source.HandleFileSource
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

data class PtyFd(
    val fileDescriptor: FileDescriptor,
    val ptyId: Long,
    val childPid: Int
)

data class ExecStreamFd(
    val execId: Long,
    val childPid: Int,
    val stdout: FileDescriptor,
    val stderr: FileDescriptor,
)

data class OpenHandleResult(
    val handleId: Long,
    val size: Long,
    val fileDescriptor: FileDescriptor? = null,
)

/**
 * Protocol layer over a proto-agnostic [Transport]: it owns request/response
 * correlation, streaming, source-read brokering and handle/pty decoding. The
 * socket, framing and SCM_RIGHTS all live in `:uds`; disconnects are terminal.
 *
 * Blocking by design — the public facades ([io.furryr.file.daemon.file.FileService],
 * [io.furryr.file.daemon.process.ExecService]) stay synchronous; sends bridge into
 * the transport's coroutine world.
 */
class DaemonConnection internal constructor(
    private val identity: DaemonIdentity,
    private val transport: Transport,
    process: Process?,
    private val onClosed: (() -> Unit)? = null,
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
    internal val sourceReaders = HashMap<Long, FileSource>()
    internal val pendingLock = Any()
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
            onClosed?.let { runCatching(it) }
        }
    }

    private fun dispatchResponse(frame: Frame) {
        val decoded = try {
            DaemonResponse(Response.parseFrom(frame.payload), frame.fds)
        } catch (e: Exception) {
            frame.fds.forEach { runCatching { Os.close(it) } }
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
                decoded.fds.forEach { runCatching { Os.close(it) } }
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

    internal fun rawSend(request: Request, fds: List<FileDescriptor> = emptyList()) {
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
            resp.fds.forEach { runCatching { Os.close(it) } }
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

    private fun cancelStream(id: Long) {
        // Cancellation must not wait for an acknowledgement from the copy worker.
        runCatching {
            rawSend(Request.newBuilder()
                .setId(allocateRequestId())
                .setCancel(CancelRequest.newBuilder().setTargetId(id))
                .build())
        }.onFailure { Log.w(TAG, "Failed to cancel stream $id", it) }
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
        } catch (e: CancellationException) {
            cancelStream(id)
            throw e
        } finally {
            synchronized(pendingLock) { pendingRequests.remove(id) }
        }
    }.flowOn(Dispatchers.IO)

    // ── File operations ──────────────────────────────────────────────

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
        } catch (e: CancellationException) {
            cancelStream(id)
            throw e
        } finally {
            synchronized(pendingLock) {
                pendingRequests.remove(id)
                sourceReaders.remove(id)
            }
        }
    }.flowOn(Dispatchers.IO)
}
