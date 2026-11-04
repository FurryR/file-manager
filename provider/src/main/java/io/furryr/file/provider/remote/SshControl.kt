package io.furryr.file.provider.remote

import com.jcraft.jsch.ChannelExec
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * One long-lived non-interactive `sh` per SSH location.
 *
 * Metadata work used to open, run and close a channel for every command, so a
 * single directory tap paid several channel handshakes before the remote shell
 * even read the script. This control channel keeps one `sh` process alive and
 * frames each command with a unique end marker: one write, then read until the
 * marker. stdout and stderr stay separate because JSch exposes both streams on
 * an exec channel, so `find` warnings never mix into the NUL-separated
 * metadata.
 *
 * Commands run inside a subshell, so `exit` in a script only ends that command
 * and cannot kill the shared shell. Streaming transfers (upload, file read)
 * keep their own dedicated channels; this one is for short metadata commands.
 */
internal class SshControl(private val location: RemoteLocation) {
    private val lock = Any()
    private var lease: SshLease? = null
    private var channel: ChannelExec? = null
    private var input: InputStream? = null
    private var stdin: OutputStream? = null
    private val stderrSink = StderrSink()

    fun run(script: String): ByteArray = synchronized(lock) {
        ensureOpen()
        val stdout = input ?: throw IOException("SSH control channel is not open")
        val sink = stdin ?: throw IOException("SSH control channel is not open")
        val token = UUID.randomUUID().toString()
        stderrSink.reset()
        discardPending(stdout)
        val command = "(\n$script\n); printf '\\n__FME:$token:%d__\\n' \"$?\"\n"
        try {
            sink.write(command.toByteArray(Charsets.UTF_8))
            sink.flush()
        } catch (failure: IOException) {
            closeLocked()
            throw IOException("SSH control channel failed; retry the operation", failure)
        }
        val reply = readReply(stdout, token)
        if (reply.status != 0) {
            // stderr may still be in flight when the marker arrives.
            Thread.sleep(10)
            val details = stderrSink.take().toString(Charsets.UTF_8)
                .filter { !it.isISOControl() || it == '\n' }.take(1024)
            var safe = details
            listOf(location.password, location.passphrase, location.privateKey).filter { it.isNotEmpty() }
                .forEach { safe = safe.replace(it, "[redacted]") }
            throw IOException(
                "SSH command failed (${reply.status})${if (safe.isBlank()) "" else ": $safe"}"
            )
        }
        reply.bytes
    }

    fun close() = synchronized(lock) { closeLocked() }

    private fun ensureOpen() {
        channel?.let { if (!it.isClosed) return }
        closeLocked()
        val fresh = SshConnections.open(location)
        try {
            val exec = fresh.connection.open("exec") { ch: ChannelExec ->
                ch.setPty(false)
                ch.setCommand("sh")
                ch.setErrStream(stderrSink, true)
                ch.connect(30_000)
            }
            lease = fresh
            channel = exec
            input = exec.inputStream
            stdin = exec.outputStream
            discardPending(input ?: throw IOException("SSH control channel has no stdout"))
        } catch (failure: Throwable) {
            runCatching { fresh.close() }
            throw failure
        }
    }

    private fun closeLocked() {
        runCatching { channel?.disconnect() }
        channel = null
        input = null
        stdin = null
        runCatching { lease?.close() }
        lease = null
        stderrSink.reset()
    }

    private fun discardPending(stdout: InputStream) {
        repeat(1024) {
            val available = stdout.available()
            if (available <= 0) return
            val buffer = ByteArray(minOf(available, 8192))
            if (stdout.read(buffer) < 0) return
        }
    }

    private class Reply(val bytes: ByteArray, val status: Int)

    private fun readReply(stdout: InputStream, token: String): Reply {
        val marker = "\n__FME:$token:".toByteArray(Charsets.US_ASCII)
        val output = ByteArrayOutputStream()
        var matched = 0
        var status = -1
        var digits = 0
        var underscores = 0
        var phase = 0 // 0 = scanning stdout, 1 = reading status, 2 = done
        var lastActivity = System.nanoTime()
        while (phase != 2) {
            if (Thread.currentThread().isInterrupted) throw IOException("Operation cancelled")
            val available = stdout.available()
            if (available <= 0) {
                if (System.nanoTime() - lastActivity > TIMEOUT_NS) throw IOException("SSH operation timed out")
                Thread.sleep(2)
                continue
            }
            val buffer = ByteArray(minOf(available, 64 * 1024))
            val read = stdout.read(buffer)
            if (read < 0) throw IOException("SSH control channel closed")
            lastActivity = System.nanoTime()
            for (index in 0 until read) {
                val value = buffer[index]
                when (phase) {
                    0 -> {
                        if (value == marker[matched]) {
                            matched++
                            if (matched == marker.size) {
                                matched = 0
                                phase = 1
                            }
                        } else {
                            if (matched > 0) {
                                output.write(marker, 0, matched)
                                matched = 0
                            }
                            if (value == marker[0]) matched = 1 else output.write(value.toInt() and 0xFF)
                            if (output.size() > MAX_OUTPUT) throw IOException("SSH response is too large")
                        }
                    }
                    1 -> {
                        if (value == '_'.code.toByte()) {
                            underscores++
                            if (underscores == 2) phase = 2
                        } else if (underscores == 0 && value in '0'.code.toByte()..'9'.code.toByte()) {
                            status = if (status < 0) value - '0'.code else status * 10 + (value - '0'.code)
                            digits++
                            if (digits > 6) throw IOException("Invalid SSH command status")
                        } else {
                            throw IOException("Invalid SSH command response")
                        }
                    }
                }
            }
        }
        return Reply(output.toByteArray(), status.coerceAtLeast(0))
    }

    private class StderrSink : OutputStream() {
        private val buffer = ByteArrayOutputStream()
        @Synchronized override fun write(value: Int) {
            if (buffer.size() < STDERR_LIMIT) buffer.write(value)
        }
        @Synchronized override fun write(bytes: ByteArray, offset: Int, length: Int) {
            val room = STDERR_LIMIT - buffer.size()
            if (room > 0) buffer.write(bytes, offset, minOf(length, room))
        }
        @Synchronized fun reset() = buffer.reset()
        @Synchronized fun take(): ByteArray = buffer.toByteArray().also { buffer.reset() }
    }

    private companion object {
        const val TIMEOUT_NS = 120_000_000_000L
        const val MAX_OUTPUT = 32 * 1024 * 1024
        const val STDERR_LIMIT = 16 * 1024
    }
}

/** Process-wide registry of control channels, one per SSH location. */
internal object SshControls {
    private val controls = HashMap<String, SshControl>()

    @Synchronized
    fun get(location: RemoteLocation): SshControl =
        controls.getOrPut(location.id) { SshControl(location) }

    @Synchronized fun close(id: String) {
        controls.remove(id)?.close()
    }

    @Synchronized fun closeAll() {
        controls.values.forEach { runCatching { it.close() } }
        controls.clear()
    }
}
