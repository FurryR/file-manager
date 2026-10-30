package io.furryr.file.provider.remote

import com.jcraft.jsch.ChannelShell
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

/**
 * An interactive shell channel on the shared SSH connection of [RemoteLocation].
 *
 * The app bridges this channel to the terminal's own file descriptor, so the
 * session stays reusable for file operations while the shell is open.
 */
class SshShell internal constructor(
    private val channel: ChannelShell,
    val input: InputStream,
    val output: OutputStream,
) : Closeable {
    val exitStatus: Int get() = channel.exitStatus

    fun resize(rows: Int, cols: Int) {
        runCatching { channel.setPtySize(cols, rows, 0, 0) }
    }

    override fun close() {
        runCatching { channel.disconnect() }
    }
}

object SshTerminals {
    fun find(id: String): RemoteLocation = RemoteLocationStore.find(id).also {
        require(it.kind == RemoteKind.SSH) { "Remote connection is not SSH" }
    }

    fun open(location: RemoteLocation, startPath: String?, rows: Int, cols: Int): SshShell {
        require(location.kind == RemoteKind.SSH) { "Remote connection is not SSH" }
        val channel = SshConnections.get(location).open("shell") { shell: ChannelShell ->
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
        return SshShell(channel, input, output)
    }

    private fun shellQuote(value: String): String {
        require('\u0000' !in value)
        return "'" + value.replace("'", "'\\''") + "'"
    }
}
