package io.furryr.file.util

import android.system.Os
import android.util.Log
import io.furryr.file.agent.ContainerDaemonLauncher
import io.furryr.file.core.DaemonManager
import io.furryr.file.core.DaemonTarget
import io.furryr.file.core.hub.Hub
import io.furryr.file.core.process.CommandSpec
import io.furryr.file.core.process.ProcessChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.FileDescriptor

/**
 * Daemon-backed replacement for Termux's native process helpers.
 *
 * `com.termux.terminal.JNI` is rewritten (see `app/build.gradle.kts`) to call
 * these methods, so a [com.termux.terminal.TerminalSession] owns the PTY fd
 * returned here and reads/writes it directly.
 *
 * A `cmd` of the form `container://<name>` spawns the shell on that container's
 * dedicated daemon instead of the app daemon, so the terminal *is* the
 * container's shell (no bridging).
 */
object ProcessHelper {
    private const val TAG = "ProcessHelper"
    private const val CONTAINER_PREFIX = "container://"

    private val fdToChannel = HashMap<Int, ProcessChannel>()
    private val pidToChannel = HashMap<Int, ProcessChannel>()
    private val mapLock = Any()

    @JvmStatic
    fun createSubprocess(
        cmd: String, cwd: String?, args: Array<String>?, envVars: Array<String>?,
        processId: IntArray, rows: Int, columns: Int
    ): Int = onIoThread {
        val env = envVars?.associate {
            val eq = it.indexOf('=')
            if (eq >= 0) it.substring(0, eq) to it.substring(eq + 1) else it to ""
        } ?: emptyMap()

        val containerName = cmd.removePrefix(CONTAINER_PREFIX)
            .takeIf { cmd.startsWith(CONTAINER_PREFIX) && it.isNotBlank() }
        val target = if (containerName != null) {
            DaemonTarget.Named(containerName)
        } else {
            DaemonTarget.App
        }

        // Initialize a clean target environment before an interactive container login.
        // Command execution through the hub bypasses login and its MOTD output.
        val spec = CommandSpec(
            command = if (containerName != null) "" else cmd,
            args = if (containerName != null) {
                listOf("-c", "exec /bin/login -p -f root")
            } else (args?.toList() ?: emptyList()),
            env = if (containerName != null) {
                env.filterKeys { it == "TERM" || it == "HUB_TERMINAL" } + Hub.environment() + buildMap {
                    put("PATH", ContainerDaemonLauncher.DEFAULT_PATH)
                    Hub.binDir?.let { put("HUB_PATH", it.absolutePath) }
                }
            } else env,
            cwd = if (containerName != null) "~" else cwd,
            user = if (containerName != null) "root" else null,
            rows = rows,
            cols = columns,
        )
        val channel = DaemonManager.execService(target).spawn(spec).getOrThrow()
        val fd = channel.fileDescriptor
            ?: throw IllegalStateException("daemon returned no PTY fd")

        val rawFd = getRawFd(fd)
        processId[0] = channel.pid

        synchronized(mapLock) {
            fdToChannel[rawFd] = channel
            pidToChannel[channel.pid] = channel
        }

        Log.d(TAG, "createSubprocess: fd=$rawFd pid=${channel.pid} container=$containerName")
        rawFd
    }

    @JvmStatic
    fun setPtyWindowSize(fd: Int, rows: Int, cols: Int) {
        val channel: ProcessChannel?
        synchronized(mapLock) { channel = fdToChannel[fd] }
        channel ?: return
        onIoThread { channel.resize(rows, cols) }
            .onFailure { Log.w(TAG, "setPtyWindowSize: fd=$fd is no longer available", it) }
    }

    @JvmStatic
    fun waitFor(processId: Int): Int {
        val channel: ProcessChannel?
        synchronized(mapLock) { channel = pidToChannel[processId] }
        channel ?: return -1
        // Termux calls this on its own thread without an exception handler.
        // A daemon reset/disconnect must finish the session, not crash the app.
        val exitCode = onIoThread { channel.waitFor() }.getOrElse {
            Log.w(TAG, "waitFor: pid=$processId lost its daemon", it)
            -1
        }
        Log.d(TAG, "waitFor: pid=$processId exitCode=$exitCode")
        return exitCode
    }

    @JvmStatic
    fun close(fileDescriptor: Int) {
        val channel: ProcessChannel?
        synchronized(mapLock) {
            channel = fdToChannel.remove(fileDescriptor)
            if (channel != null) pidToChannel.remove(channel.pid)
        }
        if (channel != null) {
            runCatching { channel.close() }
                .onFailure { Log.w(TAG, "close: fd=$fileDescriptor failed", it) }
        } else {
            closeRawFd(fileDescriptor)
        }
    }

    /**
     * The termux JNI contract calls into these methods synchronously, and the
     * terminal drives some of them straight from the main thread. The daemon
     * talks over a (local) UNIX socket, which StrictMode's `BlockGuardOs`
     * rejects with `NetworkOnMainThreadException` when the syscall runs on the
     * main thread — so hop onto an IO thread and block until it finishes.
     */
    private fun <T> onIoThread(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }

    private fun getRawFd(fd: FileDescriptor): Int {
        val field = FileDescriptor::class.java.getDeclaredField("descriptor")
        field.isAccessible = true
        return field.getInt(fd)
    }

    private fun closeRawFd(rawFd: Int) {
        try {
            val fdObj = FileDescriptor()
            val field = FileDescriptor::class.java.getDeclaredField("descriptor")
            field.isAccessible = true
            field.setInt(fdObj, rawFd)
            Os.close(fdObj)
        } catch (e: Exception) {
            Log.w(TAG, "closeRawFd($rawFd) failed", e)
        }
    }
}
