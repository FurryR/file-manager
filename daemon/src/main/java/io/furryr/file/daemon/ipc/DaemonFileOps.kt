package io.furryr.file.daemon.ipc

import io.furryr.file.core.model.CopyProgress
import io.furryr.file.core.model.FileEntry
import io.furryr.file.core.model.ProviderStat
import io.furryr.file.core.source.FileSource
import io.furryr.file.daemon.source.FdFileSource
import io.furryr.file.daemon.source.HandleFileSource
import io.furryr.file.proto.CloseHandleRequest
import io.furryr.file.proto.CopyRequest
import io.furryr.file.proto.CreateDirRequest
import io.furryr.file.proto.CreateFileRequest
import io.furryr.file.proto.DeleteRequest
import io.furryr.file.proto.DupHandleRequest
import io.furryr.file.proto.HandleMode
import io.furryr.file.proto.ListRequest
import io.furryr.file.proto.OpenHandleRequest
import io.furryr.file.proto.RenameRequest
import io.furryr.file.proto.Request
import io.furryr.file.proto.SeekHandleRequest
import io.furryr.file.proto.StatRequest
import io.furryr.file.proto.TruncateHandleRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

fun DaemonConnection.listFiles(path: String): Result<List<FileEntry>> = runCatching {
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

fun DaemonConnection.createFile(path: String): Result<Unit> = runCatching {
    request(Request.newBuilder()
        .setCreateFile(CreateFileRequest.newBuilder().setPath(path))
        .build())
}

fun DaemonConnection.createDir(path: String): Result<Unit> = runCatching {
    request(Request.newBuilder()
        .setCreateDir(CreateDirRequest.newBuilder().setPath(path))
        .build())
}

fun DaemonConnection.stat(path: String): Result<ProviderStat> = runCatching {
    val resp = request(Request.newBuilder()
        .setStat(StatRequest.newBuilder().setPath(path))
        .build()).response
    ProviderStat(
        usableBytes = resp.stat.usableBytes, totalBytes = resp.stat.totalBytes,
        exists = resp.stat.exists, isDirectory = resp.stat.isDirectory,
        canCreateChildren = resp.stat.canCreateChildren, canEnter = resp.stat.canEnter
    )
}

fun DaemonConnection.renameEntry(path: String, newName: String): Result<Unit> = runCatching {
    request(Request.newBuilder()
        .setRename(RenameRequest.newBuilder().setPath(path).setNewName(newName))
        .build())
}

fun DaemonConnection.deleteEntry(path: String): Result<Unit> = runCatching {
    request(Request.newBuilder()
        .setDelete(DeleteRequest.newBuilder().setPath(path))
        .build())
}

// ── Copy with progress ─────────────────────────────────────────

fun DaemonConnection.copy(from: String, to: String, isCopy: Boolean = true): Flow<CopyProgress> =
    copyWithId(allocateRequestId(), from, to, isCopy)

internal fun DaemonConnection.copyWithId(id: Long, from: String, to: String, isCopy: Boolean): Flow<CopyProgress> {
    val req = Request.newBuilder()
        .setCopy(CopyRequest.newBuilder().setFrom(from).setTo(to))
        .build()
    return requestStream(req, id, isCopy)
}

// ── Handle operations ────────────────────────────────────────────

fun DaemonConnection.openHandle(
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

fun DaemonConnection.readHandle(handleId: Long, offset: Long, length: Int) = runCatching {
    request(Request.newBuilder()
        .setReadHandle(io.furryr.file.proto.ReadHandleRequest.newBuilder()
            .setHandleId(handleId).setOffset(offset).setLength(length))
        .build()).response.readHandle
}

fun DaemonConnection.writeHandle(handleId: Long, offset: Long, data: ByteArray): Result<Unit> = runCatching {
    request(Request.newBuilder()
        .setWriteHandle(io.furryr.file.proto.WriteHandleRequest.newBuilder()
            .setHandleId(handleId).setOffset(offset)
            .setData(com.google.protobuf.ByteString.copyFrom(data)))
        .build())
}

fun DaemonConnection.closeHandle(handleId: Long): Result<Unit> = runCatching {
    request(Request.newBuilder()
        .setCloseHandle(CloseHandleRequest.newBuilder().setHandleId(handleId))
        .build())
}

fun DaemonConnection.seekHandle(handleId: Long, position: Long): Result<Unit> = runCatching {
    request(Request.newBuilder()
        .setSeekHandle(SeekHandleRequest.newBuilder().setHandleId(handleId).setPosition(position))
        .build())
}

fun DaemonConnection.dupHandle(handleId: Long): Result<OpenHandleResult> = runCatching {
    val resp = request(Request.newBuilder()
        .setDupHandle(DupHandleRequest.newBuilder().setHandleId(handleId))
        .build()).response
    val h = resp.dupHandle
    OpenHandleResult(handleId = h.handleId, size = h.size)
}

fun DaemonConnection.truncateHandle(handleId: Long, newSize: Long): Result<Unit> = runCatching {
    request(Request.newBuilder()
        .setTruncateHandle(TruncateHandleRequest.newBuilder().setHandleId(handleId).setNewSize(newSize))
        .build())
}

// ── PTY ───────────────────────────────────────────────────────────

