package io.furryr.file.provider.terminal

import io.furryr.file.provider.remote.SshShell

/**
 * An SSH shell channel exposed as a [TerminalProcess]. No local PTY, socketpair
 * or relay is involved: Termux reads and writes the channel streams directly.
 */
class SshTerminalProcess(
    private val shell: SshShell,
) : TerminalProcess {
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        shell.input.read(buffer, offset, length)

    override fun write(buffer: ByteArray, offset: Int, length: Int) =
        shell.write(buffer, offset, length)

    override fun resize(rows: Int, cols: Int) = shell.resize(rows, cols)

    override fun awaitExit(): Int = shell.awaitExit()

    override fun close() = shell.close()
}
