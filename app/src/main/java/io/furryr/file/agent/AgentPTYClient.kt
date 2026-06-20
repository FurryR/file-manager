package io.furryr.file.agent

import io.furryr.file.daemon.DaemonConnection
import io.furryr.file.daemon.DaemonLauncher
import io.furryr.file.daemon.PtyFd
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

data class PtyConnection(
    val ptyId: Long,
    val inputStream: InputStream,
    val outputStream: OutputStream
)

object AgentPTYClient {
    private const val TAG = "AgentPTYClient"

    suspend fun spawnPty(
        command: String = "/system/bin/sh",
        args: List<String> = emptyList(),
        env: Map<String, String> = emptyMap(),
        termType: String = "xterm-256color",
        rows: Int = 24,
        cols: Int = 80,
        cwd: String? = null,
        useRoot: Boolean = false
    ): Result<PtyConnection> = withContext(Dispatchers.IO) {
        runCatching {
            Log.d(TAG, "spawnPty: command=$command rows=$rows cols=$cols useRoot=$useRoot")
            val conn = DaemonLauncher.getConnection(appDaemon = true)
            val ptyFd: PtyFd = conn.spawnPty(
                command = command, args = args, env = env,
                termType = termType, rows = rows, cols = cols, cwd = cwd, useRoot = useRoot
            ).getOrThrow()
            wrapPtyFd(ptyFd)
        }
    }

    suspend fun resizePty(ptyId: Long, rows: Int, cols: Int): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                Log.d(TAG, "resizePty: ptyId=$ptyId rows=$rows cols=$cols")
                DaemonLauncher.getConnection(appDaemon = true).resizePty(ptyId, rows, cols).getOrThrow()
            }
        }

    suspend fun closePty(ptyId: Long): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                Log.d(TAG, "closePty: ptyId=$ptyId")
                DaemonLauncher.getConnection(appDaemon = true).closePty(ptyId).getOrThrow()
            }
        }

    private fun wrapPtyFd(ptyFd: PtyFd): PtyConnection {
        val fd: FileDescriptor = ptyFd.fileDescriptor
        return PtyConnection(
            ptyId = ptyFd.ptyId,
            inputStream = FileInputStream(fd),
            outputStream = FileOutputStream(fd)
        )
    }
}
