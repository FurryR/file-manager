package io.furryr.file.provider.terminal

import io.furryr.file.daemon.process.ProcessChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * A daemon-spawned PTY. [ProcessChannel] owns the fd, resize and exit status;
 * this adapter exposes the byte stream Termux needs.
 */
class DaemonTerminalProcess(
    private val channel: ProcessChannel,
    override val containerName: String? = null,
) : TerminalProcess {
    private val input = channel.input
    private val output = channel.output

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        input.read(buffer, offset, length)

    override fun write(buffer: ByteArray, offset: Int, length: Int) =
        output.write(buffer, offset, length)

    override fun resize(rows: Int, cols: Int) {
        runBlocking(Dispatchers.IO) { channel.resize(rows, cols) }
    }

    override fun awaitExit(): Int =
        runBlocking(Dispatchers.IO) { channel.waitFor().getOrElse { -1 } }

    override fun close() {
        channel.close()
    }
}
