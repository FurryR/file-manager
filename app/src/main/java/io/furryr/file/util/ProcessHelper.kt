package io.furryr.file.util

import android.system.Os
import android.util.Log
import io.furryr.file.daemon.DaemonConnection
import io.furryr.file.daemon.DaemonLauncher
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

    private data class PtyInfo(val ptyId: Long, val childPid: Int, val connection: DaemonConnection)

    private val fdToPty = HashMap<Int, PtyInfo>()
    private val pidToPty = HashMap<Int, PtyInfo>()

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
        val conn = if (containerName != null) {
            DaemonLauncher.connectContainerByName(containerName)
        } else {
            DaemonLauncher.getConnection(appDaemon = true)
        }

        val ptyFd = conn.spawnPty(
            command = if (containerName != null) "/bin/sh" else cmd,
            args = args?.toList() ?: emptyList(),
            env = env,
            rows = rows, cols = columns,
            cwd = if (containerName != null) "/" else cwd,
        ).getOrThrow()

        val rawFd = getRawFd(ptyFd.fileDescriptor)
        processId[0] = ptyFd.childPid

        val info = PtyInfo(ptyFd.ptyId, ptyFd.childPid, conn)
        synchronized(mapLock) {
            fdToPty[rawFd] = info
            pidToPty[ptyFd.childPid] = info
        }

        Log.d(TAG, "createSubprocess: fd=$rawFd pid=${ptyFd.childPid} ptyId=${ptyFd.ptyId} container=$containerName")
        rawFd
    }

    @JvmStatic
    fun setPtyWindowSize(fd: Int, rows: Int, cols: Int) {
        val info: PtyInfo?
        synchronized(mapLock) { info = fdToPty[fd] }
        info ?: return
        onIoThread { info.connection.resizePty(info.ptyId, rows, cols).getOrThrow() }
    }

    @JvmStatic
    fun waitFor(processId: Int): Int {
        val info: PtyInfo?
        synchronized(mapLock) { info = pidToPty[processId] }
        info ?: return -1
        val exitCode = onIoThread { info.connection.waitPty(info.ptyId).getOrThrow() }
        Log.d(TAG, "waitFor: pid=$processId exitCode=$exitCode")
        return exitCode
    }

    @JvmStatic
    fun close(fileDescriptor: Int) {
        val info: PtyInfo?
        synchronized(mapLock) {
            info = fdToPty.remove(fileDescriptor)
            if (info != null) pidToPty.remove(info.childPid)
        }
        if (info != null) {
            onIoThread { runCatching { info.connection.closePty(info.ptyId) } }
        }
        closeRawFd(fileDescriptor)
    }

    /**
     * The termux JNI contract calls into these methods synchronously, and the
     * terminal drives some of them straight from the main thread. The daemon
     * talks over a (local) UNIX socket, which StrictMode's `BlockGuardOs`
     * rejects with `NetworkOnMainThreadException` when the syscall runs on the
     * main thread — so hop onto an IO thread and block until it finishes.
     */
    private fun <T> onIoThread(block: () -> T): T = runBlocking(Dispatchers.IO) { block() }

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
