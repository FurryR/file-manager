package io.furryr.file.terminal

import io.furryr.file.provider.terminal.TerminalProcess
import java.io.FileDescriptor
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The one place Termux's patched [com.termux.terminal.TerminalSession] reaches
 * its [TerminalProcess].
 *
 * The ASM rewrite replaces `JNI.createSubprocess` with [createSubprocess],
 * `JNI.read/write` (the session's reader/writer threads) with [read]/[write],
 * `JNI.setPtyWindowSize` with [resize], `JNI.waitFor` with [awaitExit] and
 * `JNI.close` with [close]. Every method takes the integer token that
 * `createSubprocess` returned, never a raw fd.
 */
object TerminalBridge {
    private val processes = ConcurrentHashMap<Int, TerminalProcess>()
    private val nextToken = AtomicInteger(1)

    /** Register [process] and return the token Termux carries as its "fd". */
    fun register(process: TerminalProcess): Int {
        val token = nextToken.getAndIncrement()
        processes[token] = process
        return token
    }

    fun unregister(token: Int) {
        processes.remove(token)
    }

    fun process(token: Int): TerminalProcess? = processes[token]

    /**
     * ASM entry point for `JNI.createSubprocess`. The tab has already
     * registered its process; the token travels in the command as
     * `terminal:<token>`, so no spawning happens here.
     */
    @JvmStatic
    fun createSubprocess(
        cmd: String?,
        cwd: String?,
        args: Array<String>?,
        envVars: Array<String>?,
        processId: IntArray,
        rows: Int,
        columns: Int,
    ): Int {
        val token = tokenOf(cmd, envVars)
        val process = processes[token]
            ?: throw IllegalStateException("terminal process $token is not registered")
        processId[0] = token
        process.resize(rows, columns)
        return token
    }

    @JvmStatic
    fun read(token: Int, buffer: ByteArray, offset: Int, length: Int): Int =
        processes[token]?.read(buffer, offset, length) ?: -1

    @JvmStatic
    fun write(token: Int, buffer: ByteArray, offset: Int, length: Int) {
        processes[token]?.write(buffer, offset, length)
    }

    @JvmStatic
    fun setPtyWindowSize(token: Int, rows: Int, cols: Int) {
        processes[token]?.resize(rows, cols)
    }

    @JvmStatic
    fun waitFor(token: Int): Int = processes[token]?.awaitExit() ?: -1

    @JvmStatic
    fun close(token: Int) {
        processes.remove(token)?.close()
    }

    /** The tab hands the token through the shell marker `terminal:<token>`. */
    fun commandFor(token: Int): String = "terminal:$token"

    /**
     * ASM replacement for `new FileInputStream(fd)` in Termux's reader thread.
     * It returns a real FileInputStream subtype so the class's existing frames
     * and call sites stay valid; only the descriptor carries the token.
     */
    @JvmStatic
    fun inputStream(fd: FileDescriptor): java.io.FileInputStream {
        val token = tokenOf(fd)
        return object : java.io.FileInputStream(fd) {
            override fun read(): Int {
                val one = ByteArray(1)
                val count = read(one, 0, 1)
                return if (count <= 0) -1 else one[0].toInt() and 0xff
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                processes[token]?.read(buffer, offset, length) ?: -1

            override fun close() = Unit
        }
    }

    /** ASM replacement for `new FileOutputStream(fd)` in the writer thread. */
    @JvmStatic
    fun outputStream(fd: FileDescriptor): java.io.FileOutputStream {
        val token = tokenOf(fd)
        return object : java.io.FileOutputStream(fd) {
            override fun write(value: Int) {
                write(byteArrayOf(value.toByte()), 0, 1)
            }

            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                processes[token]?.write(buffer, offset, length)
            }

            override fun close() = Unit
        }
    }

    private fun tokenOf(fd: FileDescriptor): Int {
        val field = FileDescriptor::class.java.getDeclaredField("descriptor")
        field.isAccessible = true
        return field.getInt(fd)
    }

    private fun tokenOf(cmd: String?, envVars: Array<String>?): Int {
        val fromCommand = cmd?.removePrefix("terminal:")?.toIntOrNull()
        if (fromCommand != null) return fromCommand
        val fromEnv = envVars
            ?.firstOrNull { it.startsWith("TERMINAL_PROCESS=") }
            ?.substringAfter('=')
            ?.toIntOrNull()
        return requireNotNull(fromEnv) { "terminal command carries no process token: $cmd" }
    }
}
