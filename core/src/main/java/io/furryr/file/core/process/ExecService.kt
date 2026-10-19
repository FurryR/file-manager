package io.furryr.file.core.process

import io.furryr.file.core.ipc.DaemonConnection
import io.furryr.file.core.model.ExecResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * What to run. One type serves the terminal, container exec and the future
 * external client attach path.
 */
data class CommandSpec(
    val command: String,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val cwd: String? = null,
    val termType: String = "xterm-256color",
    val rows: Int = 24,
    val cols: Int = 80,
    /**
     * Run the process as this OS user (name or numeric UID). Null inherits the
     * daemon's credentials; a root daemon can switch to any user.
     */
    val user: String? = null,
    /** Nice level for the process; negative values require a root daemon. */
    val nice: Int? = null,
)

/**
 * A live child process wired back through a PTY master. The single abstraction
 * shared by the terminal (which owns [fileDescriptor]) and by command
 * execution, including external clients attaching to a running daemon.
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

    /** Deliver [signal] to the process group. */
    suspend fun signal(signal: Int): Result<Unit>

    suspend fun waitFor(): Result<Int>
}

/**
 * Executes commands against one daemon target. Returned by
 * [io.furryr.file.core.DaemonManager.execService].
 */
class ExecService internal constructor(private val resolve: () -> DaemonConnection) {

    /** Byte-transparent stdin/stdout/stderr; no PTY echo or newline conversion. */
    suspend fun spawnPipe(spec: CommandSpec): Result<PipeProcessChannel> {
        var opened: PipeProcessChannel? = null
        try {
            return withContext(Dispatchers.IO) {
                runCatching {
                    require(spec.user == null && spec.nice == null) { "pipe execution inherits the daemon's user and priority" }
                    DaemonPipeProcessChannel.open(resolve(), spec).also { opened = it }
                }
            }
        } catch (cancelled: CancellationException) {
            // withContext may discard a successfully opened channel when its
            // caller is cancelled during the dispatch back to the UI thread.
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { opened?.close() }.onFailure {
                    android.util.Log.w("ExecService", "Closing a cancelled pipe process", it)
                }
            }
            throw cancelled
        }
    }

    /** Start a process and return its live [ProcessChannel]. */
    suspend fun spawn(spec: CommandSpec): Result<ProcessChannel> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = resolve()
            val pty = connection.spawnPty(
                command = spec.command, args = spec.args, env = spec.env,
                termType = spec.termType, rows = spec.rows, cols = spec.cols,
                cwd = spec.cwd, user = spec.user, nice = spec.nice,
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

    override suspend fun signal(signal: Int): Result<Unit> =
        connection.signalPty(id, signal)

    override suspend fun waitFor(): Result<Int> = connection.waitPty(id)

    override fun close() {
        runCatching { input.close() }
        runCatching { output.close() }
        runCatching { connection.closePty(id) }
    }
}
