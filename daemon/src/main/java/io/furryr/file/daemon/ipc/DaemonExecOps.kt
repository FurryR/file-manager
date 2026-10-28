package io.furryr.file.daemon.ipc

import android.system.Os
import io.furryr.file.daemon.model.ExecResult
import io.furryr.file.proto.ClosePTYRequest
import io.furryr.file.proto.KillPtyRequest
import io.furryr.file.proto.Request
import io.furryr.file.proto.ResizePTYRequest
import io.furryr.file.proto.SignalPtyRequest
import io.furryr.file.proto.SpawnPTYRequest
import io.furryr.file.proto.WaitPtyRequest
import java.io.FileDescriptor

fun DaemonConnection.spawnPty(
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

fun DaemonConnection.resizePty(ptyId: Long, rows: Int, cols: Int): Result<Unit> = runCatching {
    request(Request.newBuilder()
        .setResizePty(ResizePTYRequest.newBuilder().setPtyId(ptyId).setRows(rows).setCols(cols))
        .build())
}

fun DaemonConnection.closePty(ptyId: Long): Result<Unit> = runCatching {
    request(Request.newBuilder()
        .setClosePty(ClosePTYRequest.newBuilder().setPtyId(ptyId))
        .build())
}

fun DaemonConnection.waitPty(ptyId: Long): Result<Int> = runCatching {
    val resp = request(Request.newBuilder()
        .setWaitPty(WaitPtyRequest.newBuilder().setPtyId(ptyId))
        .build())
    resp.response.exitCode.toInt()
}

fun DaemonConnection.killPty(ptyId: Long): Result<Unit> = runCatching {
    request(Request.newBuilder()
        .setKillPty(KillPtyRequest.newBuilder().setPtyId(ptyId))
        .build())
}

/** Deliver an arbitrary [signal] to the PTY's process group. */
fun DaemonConnection.signalPty(ptyId: Long, signal: Int): Result<Unit> = runCatching {
    request(Request.newBuilder()
        .setSignalPty(SignalPtyRequest.newBuilder().setPtyId(ptyId).setSignal(signal))
        .build())
}

// ── Non-PTY exec ──────────────────────────────────────────────────

/** Spawn a pipe-mode command; stdout/stderr descriptors stay owned by the caller. */
fun DaemonConnection.spawnExec(
    command: String,
    args: List<String> = emptyList(),
    cwd: String? = null,
    env: Map<String, String> = emptyMap(),
): Result<ExecStreamFd> = runCatching {
    val execReq = io.furryr.file.proto.ExecRequest.newBuilder()
        .setCommand(command).addAllArgs(args).putAllEnv(env).setPipes(true)
    if (cwd != null) execReq.cwd = cwd
    val resp = request(Request.newBuilder().setExec(execReq).build())
    val fds = resp.fds
    if (fds.size != 2) {
        fds.forEach { runCatching { Os.close(it) } }
        throw IllegalStateException(
            "daemon returned ${fds.size} exec descriptors, expected stdout and stderr",
        )
    }
    val stream = resp.response.execStream
    ExecStreamFd(
        execId = stream.execId,
        childPid = stream.childPid.toInt(),
        stdout = fds[0],
        stderr = fds[1],
    )
}

/**
 * Wait for a pipe-mode exec to finish.
 *
 * [timeoutMillis] defaults to 0 (wait forever) because the daemon owns the
 * child and always answers. Callers that must not hang the UI pass a bound and
 * then [closeExec] to kill the process group.
 */
fun DaemonConnection.waitExec(
    execId: Long,
    timeoutMillis: Long = 0L,
): Result<Int> = runCatching {
    request(
        Request.newBuilder()
            .setWaitExec(io.furryr.file.proto.WaitExecRequest.newBuilder().setExecId(execId))
            .build(),
        timeoutMillis,
    ).response.exitCode
}

/** Kill the pipe-mode exec's process group; the exec id is retired. */
fun DaemonConnection.closeExec(execId: Long): Result<Unit> = runCatching {
    request(Request.newBuilder()
        .setCloseExec(io.furryr.file.proto.CloseExecRequest.newBuilder().setExecId(execId))
        .build())
}

fun DaemonConnection.exec(
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
