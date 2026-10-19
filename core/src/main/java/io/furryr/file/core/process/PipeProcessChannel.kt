package io.furryr.file.core.process

import com.google.protobuf.ByteString
import io.furryr.file.core.ipc.DaemonConnection
import io.furryr.file.proto.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** A connection-owned process. Losing its daemon connection ends all three streams. */
interface PipeProcessChannel : Closeable {
    val id: Long
    val pid: Int
    val input: InputStream
    val error: InputStream
    val output: OutputStream
    suspend fun waitFor(): Result<Int>
    suspend fun signal(signal: Int): Result<Unit>
}

internal class DaemonPipeProcessChannel private constructor(
    private val connection: DaemonConnection,
    override val id: Long,
    override val pid: Int,
) : PipeProcessChannel {
    companion object {
        fun open(connection: DaemonConnection, spec: CommandSpec): PipeProcessChannel {
            val response = connection.request(Request.newBuilder().setSpawnPipe(
                SpawnPipeRequest.newBuilder().setCommand(spec.command).addAllArgs(spec.args)
                    .setCwd(spec.cwd.orEmpty()).putAllEnv(spec.env),
            ).build()).response.pipeSpawn
            return DaemonPipeProcessChannel(connection, response.processId, response.pid)
        }
    }
    private val closed = AtomicBoolean(false)
    override val input: InputStream = Reader(false)
    override val error: InputStream = Reader(true)
    override val output: OutputStream = object : OutputStream() {
        private var stdinClosed = false
        override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
        @Synchronized override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (offset < 0 || length < 0 || offset > bytes.size - length) throw IndexOutOfBoundsException()
            if (closed.get() || stdinClosed) throw IOException("process stdin is closed")
            var position = offset
            while (position < offset + length) {
                val count = minOf(64 * 1024, offset + length - position)
                connection.request(Request.newBuilder().setWritePipe(WritePipeRequest.newBuilder()
                    .setProcessId(id).setData(ByteString.copyFrom(bytes, position, count))).build())
                position += count
            }
        }
        @Synchronized override fun close() {
            if (!stdinClosed && !closed.get()) {
                stdinClosed = true
                connection.request(Request.newBuilder().setWritePipe(WritePipeRequest.newBuilder()
                    .setProcessId(id).setCloseStdin(true)).build())
            }
        }
    }
    private inner class Reader(private val stderr: Boolean) : InputStream() {
        private var eof = false
        override fun read(): Int {
            val bytes = ByteArray(1)
            return if (read(bytes, 0, 1) < 0) -1 else bytes[0].toInt() and 255
        }
        @Synchronized override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            if (offset < 0 || length < 0 || offset > bytes.size - length) throw IndexOutOfBoundsException()
            if (length == 0) return 0
            if (eof || closed.get()) return -1
            val response = connection.request(Request.newBuilder().setReadPipe(ReadPipeRequest.newBuilder()
                .setProcessId(id).setStderr(stderr).setLength(minOf(length, 64 * 1024))).build()).response.pipeRead
            val data = response.data.toByteArray()
            eof = response.eof
            if (data.isEmpty()) return -1
            data.copyInto(bytes, offset)
            return data.size
        }
        override fun close() { eof = true }
    }
    override suspend fun waitFor(): Result<Int> = withContext(Dispatchers.IO) {
        runCatching { connection.request(Request.newBuilder().setWaitPipe(WaitPipeRequest.newBuilder().setProcessId(id)).build()).response.exitCode }
    }
    override suspend fun signal(signal: Int): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { connection.request(Request.newBuilder().setSignalPipe(SignalPipeRequest.newBuilder().setProcessId(id).setSignal(signal)).build()); Unit }
    }
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            // Independent request: wakes concurrent stdout/stderr reads and a
            // blocked stdin write before the process group is reaped.
            connection.request(Request.newBuilder().setClosePipe(ClosePipeRequest.newBuilder().setProcessId(id)).build(), 20_000L)
        }
    }
}
