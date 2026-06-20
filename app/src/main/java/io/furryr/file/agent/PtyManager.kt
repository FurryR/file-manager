package io.furryr.file.agent

import android.util.Log
import java.io.InputStream
import java.io.OutputStream

/**
 * Singleton managing all active PTY sessions.
 *
 * Tracks [PtyConnection] instances by [PtyConnection.ptyId], provides
 * thread-safe access for creation, teardown, resizing, and bulk cleanup.
 *
 * Used by:
 * - [io.furryr.file.SessionManager] — assigns PTYs to terminal sessions
 * - [io.furryr.file.AgentViewModel] — lifecycle management
 */
object PtyManager {
    private const val TAG = "PtyManager"

    /** Active PTY connections keyed by PTY id. */
    private val activePtys = mutableMapOf<Long, PtyConnection>()

    /** Lock object for thread-safe map access. */
    private val mutex = Any()

    // ── public API ──────────────────────────────────────────────────

    /**
     * Create a new PTY session via [AgentPTYClient.spawnPty] and track it.
     *
     * @param command  Path to the executable (default: `/system/bin/sh`).
     * @param args     Additional arguments.
     * @param env      Environment variable overrides.
     * @param termType TERM variable value (default: `"xterm-256color"`).
     * @param rows     Initial terminal height in rows (default: 24).
     * @param cols     Initial terminal width in columns (default: 80).
     * @param cwd      Working directory for the child process.
     * @param useRoot  When `true` the daemon escalates privileges.
     * @return [Result] containing the [PtyConnection] on success.
     */
    suspend fun createPty(
        command: String = "/system/bin/sh",
        args: List<String> = emptyList(),
        env: Map<String, String> = emptyMap(),
        termType: String = "xterm-256color",
        rows: Int = 24,
        cols: Int = 80,
        cwd: String? = null,
        useRoot: Boolean = false
    ): Result<PtyConnection> {
        Log.d(TAG, "createPty: command=$command rows=$rows cols=$cols useRoot=$useRoot")
        return AgentPTYClient.spawnPty(
            command = command,
            args = args,
            env = env,
            termType = termType,
            rows = rows,
            cols = cols,
            cwd = cwd,
            useRoot = useRoot
        ).map { connection ->
            synchronized(mutex) {
                activePtys[connection.ptyId] = connection
            }
            Log.d(TAG, "createPty: stored ptyId=${connection.ptyId}")
            connection
        }
    }

    /**
     * Close an active PTY session.
     *
     * Closes I/O streams, removes from the active map, and tells the
     * daemon to tear down the PTY via [AgentPTYClient.closePty].
     */
    suspend fun closePty(ptyId: Long): Result<Unit> {
        Log.d(TAG, "closePty: ptyId=$ptyId")

        // Look up and remove from map under the lock.
        val connection: PtyConnection? = synchronized(mutex) {
            activePtys.remove(ptyId)
        }

        if (connection == null) {
            Log.w(TAG, "closePty: ptyId=$ptyId not found — already closed?")
            return Result.success(Unit)
        }

        // Close I/O streams best-effort.
        runCatching { connection.inputStream.close() }
        runCatching { connection.outputStream.close() }

        // Tell the daemon to kill the child and release the master fd.
        return AgentPTYClient.closePty(ptyId)
    }

    /**
     * Resize an active PTY terminal window.
     *
     * Delegates to [AgentPTYClient.resizePty] which sends a
     * [io.furryr.file.proto.ResizePTYRequest] to the daemon.
     */
    suspend fun resizePty(ptyId: Long, rows: Int, cols: Int): Result<Unit> {
        Log.d(TAG, "resizePty: ptyId=$ptyId rows=$rows cols=$cols")
        return AgentPTYClient.resizePty(ptyId, rows, cols)
    }

    /**
     * Return a snapshot of all active PTY ids.
     *
     * The returned list is a copy — it is safe to iterate without holding
     * the lock.
     */
    fun getActivePtys(): List<Long> = synchronized(mutex) {
        activePtys.keys.toList()
    }

    /**
     * Close every active PTY session and clear the map.
     *
     * Intended for disconnect / teardown scenarios (e.g. daemon
     * disconnection, app shutdown).
     */
    suspend fun cleanupAll() {
        Log.d(TAG, "cleanupAll: closing ${activePtys.size} active PTY(s)")

        val ids: List<Long> = synchronized(mutex) {
            activePtys.keys.toList()
        }

        // Close each PTY sequentially; a single failure should not
        // prevent the remaining cleanups.
        for (id in ids) {
            runCatching { closePty(id) }
        }

        synchronized(mutex) {
            activePtys.clear()
        }
        Log.d(TAG, "cleanupAll: done")
    }

    /**
     * Look up a [PtyConnection] by [ptyId], or `null` if not active.
     */
    fun getPty(ptyId: Long): PtyConnection? = synchronized(mutex) {
        activePtys[ptyId]
    }

    /**
     * Check whether a PTY session with the given [ptyId] is active.
     */
    fun hasPty(ptyId: Long): Boolean = synchronized(mutex) {
        activePtys.containsKey(ptyId)
    }
}
