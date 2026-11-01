package io.furryr.file.provider.remote

import com.jcraft.jsch.ChannelShell
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * An interactive shell channel on a session leased from [SshConnections].
 *
 * The lease is transient: closing the shell disconnects that session instead of
 * recycling it, so a terminal never inherits a session that a stalled transfer
 * left half-used. File operations keep pooling their own sessions meanwhile.
 *
 * JSch is not safe for concurrent writes, and the terminal resizes its PTY from
 * the UI thread while the relay feeds keystrokes from its own thread. Input and
 * window changes therefore share one lock, and resizes are coalesced onto a
 * dedicated thread. A window change straight from the UI thread would otherwise
 * corrupt the channel's packet stream mid-write, which surfaces as the terminal
 * session dying on every size change (for example while the sheet animates).
 */
class SshShell internal constructor(
    private val channel: ChannelShell,
    private val lease: SshLease,
    val input: InputStream,
    private val output: OutputStream,
) : Closeable {
    val exitStatus: Int get() = channel.exitStatus

    private val writeLock = Any()
    private val closed = AtomicBoolean(false)
    private val pendingSize = AtomicReference<Pair<Int, Int>?>(null)
    private val draining = AtomicBoolean(false)
    private val resizer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SshShellResize").apply { isDaemon = true }
    }

    /** Relay entry point; serialized with [resize] so packets never interleave. */
    fun write(bytes: ByteArray, offset: Int, length: Int) {
        check(!closed.get()) { "SSH shell is closed" }
        synchronized(writeLock) {
            output.write(bytes, offset, length)
            output.flush()
        }
    }

    /**
     * Apply a PTY size off the caller's thread, keeping only the latest request.
     * The terminal sheet animates its height, so every frame reports a new size.
     */
    fun resize(rows: Int, cols: Int) {
        if (closed.get()) return
        pendingSize.set(rows to cols)
        if (draining.compareAndSet(false, true)) {
            try {
                resizer.execute(::drainResize)
            } catch (rejected: RejectedExecutionException) {
                draining.set(false)
            }
        }
    }

    private fun drainResize() {
        while (true) {
            val size = pendingSize.getAndSet(null) ?: break
            synchronized(writeLock) {
                if (!closed.get()) {
                    runCatching { channel.setPtySize(size.second, size.first, 0, 0) }
                }
            }
        }
        draining.set(false)
        // A resize can arrive between the last drain and the flag reset.
        if (pendingSize.get() != null && draining.compareAndSet(false, true)) drainResize()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        resizer.shutdownNow()
        synchronized(writeLock) { runCatching { channel.disconnect() } }
        lease.close()
    }
}

object SshTerminals {
    fun find(id: String): RemoteLocation = RemoteLocationStore.find(id).also {
        require(it.kind == RemoteKind.SSH) { "Remote connection is not SSH" }
    }

    fun open(location: RemoteLocation, startPath: String?, rows: Int, cols: Int): SshShell {
        require(location.kind == RemoteKind.SSH) { "Remote connection is not SSH" }
        val lease = SshConnections.terminal(location)
        try {
            val channel = lease.connection.open("shell") { shell: ChannelShell ->
                shell.setPty(true)
                shell.setPtyType("xterm-256color", cols, rows, 0, 0)
                shell.setPtySize(cols, rows, 0, 0)
                shell.connect(30_000)
            }
            val input: InputStream
            val output: OutputStream
            try {
                input = channel.inputStream
                output = channel.outputStream
                if (!startPath.isNullOrBlank()) {
                    val path = normalizeRemotePath(startPath)
                    output.write("cd -- ${shellQuote(path)}\n".toByteArray(Charsets.UTF_8))
                    output.flush()
                }
            } catch (failure: Exception) {
                channel.disconnect()
                throw failure
            }
            return SshShell(channel, lease, input, output)
        } catch (failure: Exception) {
            lease.close()
            throw failure
        }
    }

    private fun shellQuote(value: String): String {
        require('\u0000' !in value)
        return "'" + value.replace("'", "'\\''") + "'"
    }
}
