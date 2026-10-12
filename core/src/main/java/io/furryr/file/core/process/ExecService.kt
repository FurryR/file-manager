package io.furryr.file.core.process

import io.furryr.file.core.ipc.DaemonConnection
import io.furryr.file.core.model.ExecResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * What to run. One type serves the terminal, container exec and the future
 * run0-style attach path.
 */
data class CommandSpec(
    val command: String,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val cwd: String? = null,
    val termType: String = "xterm-256color",
    val rows: Int = 24,
    val cols: Int = 80,
    /** Request privilege escalation; the root daemon honours it. */
    val escalate: Boolean = false,
)

/**
 * A live child process wired back through a PTY master. The single abstraction
 * shared by the terminal (which owns [fileDescriptor]) and by command
 * execution, including a future attach-a-running-daemon (run0) mechanism.
 */
interface ProcessChannel : Closeable {
    /** Daemon-side handle id (PTY id). */
    val id: Long

    /** Child pid. */
    val pid: Int

    /** Raw PTY master fd, when the kernel handed one over. */
    val fileDescriptor: FileDescriptor?

    /** Process stdout (PTY master read side). */
    val input: InputStream

    /** Process stdin (PTY master write side). */
    val output: OutputStream

    suspend fun resize(rows: Int, cols: Int): Result<Unit>

    /**
     * Deliver [signal] to the process.
     *
     * Only termination signals are wired through the current daemon protocol;
     * arbitrary signals need a protocol extension (the run0 seam).
     */
    suspend fun signal(signal: Int): Result<Unit>

    suspend fun waitFor(): Result<Int>
}

/**
 * Executes commands against one daemon target. Returned by
 * [io.furryr.file.core.DaemonManager.execService].
 */
class ExecService internal constructor(private val resolve: () -> DaemonConnection) {

    /** Start a process and return its live [ProcessChannel]. */
    suspend fun spawn(spec: CommandSpec): Result<ProcessChannel> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = resolve()
            val pty = connection.spawnPty(
                command = spec.command, args = spec.args, env = spec.env,
                termType = spec.termType, rows = spec.rows, cols = spec.cols,
                cwd = spec.cwd, useRoot = spec.escalate,
            ).getOrThrow()
            PtyProcessChannel(connection, pty.ptyId, pty.childPid, pty.fileDescriptor)
        }
    }

    /** Run [spec] to completion and buffer its output (non-interactive). */
    suspend fun run(spec: CommandSpec): Result<ExecResult> = withContext(Dispatchers.IO) {
        runCatching {
            resolve().exec(
                command = spec.command, args = spec.args, cwd = spec.cwd, env = spec.env,
            ).getOrThrow()
        }
    }
}

private const val SIGKILL = 9
private const val SIGTERM = 15

private class PtyProcessChannel(
    private val connection: DaemonConnection,
    override val id: Long,
    override val pid: Int,
    override val fileDescriptor: FileDescriptor,
) : ProcessChannel {
    override val input: InputStream = FileInputStream(fileDescriptor)
    override val output: OutputStream = FileOutputStream(fileDescriptor)

    override suspend fun resize(rows: Int, cols: Int): Result<Unit> =
        connection.resizePty(id, rows, cols)

    override suspend fun signal(signal: Int): Result<Unit> = when (signal) {
        SIGKILL, SIGTERM -> connection.killPty(id)
        else -> Result.failure(
            UnsupportedOperationException("signal $signal needs a daemon protocol extension")
        )
    }

    override suspend fun waitFor(): Result<Int> = connection.waitPty(id)

    override fun close() {
        runCatching { input.close() }
        runCatching { output.close() }
        runCatching { connection.closePty(id) }
    }
}
