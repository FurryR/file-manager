package io.furryr.file.util

import android.system.Os
import android.util.Log
import io.furryr.file.daemon.DaemonLauncher
import java.io.FileDescriptor

object ProcessHelper {
    private const val TAG = "ProcessHelper"

    private data class PtyInfo(val ptyId: Long, val childPid: Int)

    private val fdToPty = HashMap<Int, PtyInfo>()
    private val pidToPtyId = HashMap<Int, Long>()

    private val mapLock = Any()

    @JvmStatic
    fun createSubprocess(
        cmd: String, cwd: String?, args: Array<String>?, envVars: Array<String>?,
        processId: IntArray, rows: Int, columns: Int
    ): Int {
        val env = envVars?.associate {
            val eq = it.indexOf('=')
            if (eq >= 0) it.substring(0, eq) to it.substring(eq + 1) else it to ""
        } ?: emptyMap()
        val conn = DaemonLauncher.getConnection(appDaemon = true)
        val ptyFd = conn.spawnPty(
            command = cmd, args = args?.toList() ?: emptyList(), env = env,
            rows = rows, cols = columns, cwd = cwd
        ).getOrThrow()

        val rawFd = getRawFd(ptyFd.fileDescriptor)
        processId[0] = ptyFd.childPid

        synchronized(mapLock) {
            fdToPty[rawFd] = PtyInfo(ptyFd.ptyId, ptyFd.childPid)
            pidToPtyId[ptyFd.childPid] = ptyFd.ptyId
        }

        Log.d(TAG, "createSubprocess: fd=$rawFd pid=${ptyFd.childPid} ptyId=${ptyFd.ptyId}")
        return rawFd
    }

    @JvmStatic
    fun setPtyWindowSize(fd: Int, rows: Int, cols: Int) {
        val info: PtyInfo?
        synchronized(mapLock) { info = fdToPty[fd] }
        info ?: return
        DaemonLauncher.getConnection(appDaemon = true).resizePty(info.ptyId, rows, cols)
            .getOrThrow()
    }

    @JvmStatic
    fun waitFor(processId: Int): Int {
        val ptyId: Long?
        synchronized(mapLock) { ptyId = pidToPtyId[processId] }
        ptyId ?: return -1
        val exitCode = DaemonLauncher.getConnection(appDaemon = true).waitPty(ptyId)
            .getOrThrow()
        Log.d(TAG, "waitFor: pid=$processId exitCode=$exitCode")
        return exitCode
    }

    @JvmStatic
    fun close(fileDescriptor: Int) {
        val info: PtyInfo?
        synchronized(mapLock) {
            info = fdToPty.remove(fileDescriptor)
            if (info != null) pidToPtyId.remove(info.childPid)
        }
        if (info != null) {
            runCatching {
                DaemonLauncher.getConnection(appDaemon = true).closePty(info.ptyId)
            }
        }
        closeRawFd(fileDescriptor)
    }

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
