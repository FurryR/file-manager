package io.furryr.file.provider.remote

import com.jcraft.jsch.ChannelExec
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.min

/**
 * One long-lived read worker per SSH location.
 *
 * A file read used to open an `exec` channel and start `tail` for every
 * non-sequential offset, so a zip index or an APK inspection paid a channel
 * handshake plus the server-side `wc -c` for each random jump. This worker keeps
 * one `sh` process alive and answers `READ` commands on it. Replying with a
 * length header keeps the protocol binary-safe (unlike the metadata channel's
 * end marker), and each request is bounded to one chunk, so the metadata
 * channel never waits behind a whole file.
 */
internal class SshWorker(private val location: SshLocation) {
    private val lock = Any()
    private var lease: SshLease? = null
    private var channel: ChannelExec? = null
    private var stdout: InputStream? = null
    private var stdin: OutputStream? = null
    private var sequence = 0L

    /** Read at most [length] bytes at [offset]; fewer only at EOF. */
    fun read(path: String, offset: Long, length: Int): ByteArray = synchronized(lock) {
        require(offset >= 0 && length >= 0)
        if (length == 0) return ByteArray(0)
        ensureOpenLocked()
        val out = stdout ?: throw SshException(SshError.CHANNEL)
        val sink = stdin ?: throw SshException(SshError.CHANNEL)
        val id = (++sequence).toString()
        val command = "READ $id $offset $length ${token(path)}\n"
        try {
            sink.write(command.toByteArray(Charsets.US_ASCII))
            sink.flush()
        } catch (failure: Exception) {
            closeLocked()
            throw SshException(SshError.CHANNEL, cause = failure)
        }
        try {
            val header = readLine(out)
            val parts = header.split(' ')
            if (parts.size != 3 || parts[1] != id) throw damaged()
            if (parts[0] == "E") throw SshException(SshError.COMMAND, detail = "read failed")
            if (parts[0] != "R") throw damaged()
            val count = parts[2].toIntOrNull()?.takeIf { it in 0..length } ?: throw damaged()
            readBytes(out, count)
        } catch (failure: Throwable) {
            // A cancelled or timed-out reply leaves unread payload in the
            // channel; recycling this worker would misfile the next reply.
            // Drop the shell and let the next caller dial a fresh one.
            closeLocked()
            throw failure
        }
    }

    fun close() = synchronized(lock) { closeLocked() }

    private fun ensureOpenLocked() {
        channel?.let { if (!it.isClosed) return }
        closeLocked()
        val fresh = SshConnections.open(location)
        try {
            val exec = fresh.connection.open("exec") { ch: ChannelExec ->
                ch.setPty(false)
                ch.setCommand("sh")
                ch.setErrStream(Discard, true)
                ch.connect(30_000)
            }
            lease = fresh
            channel = exec
            val input = exec.inputStream
            val output = exec.outputStream
            stdout = input
            stdin = output
            output.write((SCRIPT + "\n").toByteArray(Charsets.UTF_8))
            output.flush()
            if (readLine(input) != HANDSHAKE) throw SshException(SshError.RESPONSE)
        } catch (failure: Throwable) {
            runCatching { fresh.close() }
            closeLocked()
            throw failure
        }
    }

    private fun closeLocked() {
        runCatching { channel?.disconnect() }
        channel = null
        stdout = null
        stdin = null
        runCatching { lease?.close() }
        lease = null
    }

    /** A framing failure cannot be recovered: the next reply would be misfiled. */
    private fun damaged(): SshException {
        closeLocked()
        return SshException(SshError.RESPONSE)
    }

    private fun readLine(input: InputStream): String {
        val line = StringBuilder()
        var lastActivity = System.nanoTime()
        while (true) {
            if (Thread.currentThread().isInterrupted) throw SshException(SshError.CANCELLED)
            val available = input.available()
            if (available <= 0) {
                if (System.nanoTime() - lastActivity > TIMEOUT_NS) throw SshException(SshError.TIMEOUT)
                Thread.sleep(2)
                continue
            }
            val value = input.read()
            if (value < 0) throw SshException(SshError.CHANNEL)
            lastActivity = System.nanoTime()
            if (value == '\n'.code) return line.toString()
            if (line.length >= 64) throw damaged()
            line.append(value.toChar())
        }
    }

    private fun readBytes(input: InputStream, length: Int): ByteArray {
        val out = ByteArray(length)
        var filled = 0
        var lastActivity = System.nanoTime()
        while (filled < length) {
            if (Thread.currentThread().isInterrupted) throw SshException(SshError.CANCELLED)
            val available = input.available()
            if (available <= 0) {
                if (System.nanoTime() - lastActivity > TIMEOUT_NS) throw SshException(SshError.TIMEOUT)
                Thread.sleep(2)
                continue
            }
            val read = input.read(out, filled, min(available, length - filled))
            if (read < 0) throw SshException(SshError.CHANNEL)
            filled += read
            lastActivity = System.nanoTime()
        }
        return out
    }

    /** Octal-escape every byte so the line protocol survives spaces and quotes. */
    private fun token(value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val text = StringBuilder(bytes.size * 4)
        for (byte in bytes) {
            val code = byte.toInt() and 0xFF
            text.append('\\').append('0')
            text.append('0' + ((code shr 6) and 7))
            text.append('0' + ((code shr 3) and 7))
            text.append('0' + (code and 7))
        }
        return text.toString()
    }

    private object Discard : OutputStream() {
        override fun write(value: Int) = Unit
        override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
    }

    private companion object {
        const val HANDSHAKE = "FMW1"
        const val TIMEOUT_NS = 120_000_000_000L

        /**
         * Runs on the server for the worker's lifetime. Every command names one
         * bounded range; the reply is `R <id> <count>` followed by exactly
         * `count` bytes, so binary payloads need no marker scanning.
         */
        val SCRIPT = """
            printf '$HANDSHAKE\n'
            while IFS= read -r line; do
                set -- ${'$'}line
                verb=${'$'}1; id=${'$'}2; off=${'$'}3; len=${'$'}4; tok=${'$'}5
                [ "${'$'}verb" = READ ] || continue
                p=${'$'}(printf '%b' "${'$'}tok") || { printf 'E %s 0\n' "${'$'}id"; continue; }
                sz=${'$'}(wc -c < "${'$'}p" 2>/dev/null | tr -d ' ') || sz=0
                avail=$((sz - off))
                [ "${'$'}avail" -gt 0 ] || avail=0
                n=${'$'}len
                [ "${'$'}avail" -lt "${'$'}n" ] && n=${'$'}avail
                printf 'R %s %s\n' "${'$'}id" "${'$'}n"
                [ "${'$'}n" -gt 0 ] && tail -c +$((off + 1)) "${'$'}p" 2>/dev/null | head -c "${'$'}n"
            done
        """.trimIndent()
    }
}

/**
 * Read workers are pooled per location. A released worker keeps its shell warm
 * for the next file; past [MAX_IDLE] the extra shell is closed, and a worker
 * that dies is dropped by the next caller.
 */
internal object SshWorkers {
    private const val MAX_IDLE = 3

    private val idle = HashMap<String, ArrayDeque<SshWorker>>()

    fun acquire(location: SshLocation): SshWorker = synchronized(idle) {
        idle.getOrPut(location.id) { ArrayDeque() }.removeLastOrNull() ?: SshWorker(location)
    }

    fun release(location: SshLocation, worker: SshWorker) = synchronized(idle) {
        val pool = idle.getOrPut(location.id) { ArrayDeque() }
        if (pool.size >= MAX_IDLE) worker.close() else pool.addLast(worker)
    }

    @Synchronized
    fun close(id: String) {
        idle.remove(id)?.forEach { runCatching { it.close() } }
    }

    @Synchronized
    fun closeAll() {
        idle.values.forEach { pool -> pool.forEach { runCatching { it.close() } } }
        idle.clear()
    }
}
