package io.furryr.file.agent

import android.content.Context
import android.util.Log
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Singleton managing all active terminal sessions.
 *
 * Supports three session types:
 * - [SessionType.AppShell] -- local shell inside Termux's own PTY
 * - [SessionType.RootPty] -- daemon-spawned PTY with optional root escalation
 * - [SessionType.Container] -- proot container process run directly as
 *   the PTY shell; no external bridge needed
 *
 * Thread safety is provided via [synchronized] on [mutex] for all map access.
 *
 * Used by [AgentScreen] (Task 27) for block-model terminal sessions.
 */
object SessionManager {
    private const val TAG = "SessionManager"

    /** Max scrollback rows for each [TerminalSession]. */
    private const val DEFAULT_TRANSCRIPT_ROWS = 2000

    /** Default environment variable for terminal sessions. */
    private val DEFAULT_ENV = arrayOf("TERM=xterm-256color", "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/system/bin:/system/xbin:/vendor/bin")

    // -- internal state -----------------------------------------------

    /** Active sessions keyed by UUID string. */
    private val sessions = mutableMapOf<String, SessionInfo>()
    private val mutex = Any()

    /** Listeners notified when a session's content changes (needs redraw). */
    private val textChangedListeners = mutableSetOf<(TerminalSession) -> Unit>()

    /** Listeners notified when a session finishes. */
    private val sessionFinishedListeners = mutableSetOf<(TerminalSession) -> Unit>()

    // =================================================================
    //  Public types
    // =================================================================

    /**
     * Discriminated union identifying how a terminal session was created
     * and how its I/O is wired.
     */
    sealed class SessionType {
        /** Local shell via Termux's built-in PTY. */
        data class AppShell(val command: String = "/system/bin/sh", val cwd: String? = null) : SessionType()

        /** Proot container process run directly as the PTY shell. */
        data class Container(val containerName: String) : SessionType()

        /** Daemon-spawned PTY with optional root/shizuku escalation. */
        data class RootPty(val useShizuku: Boolean = false) : SessionType()
    }

    /**
     * Full descriptor for an active session.
     *
     * @property id            UUID assigned at creation time.
     * @property session       The Termux [TerminalSession] for display/input.
     * @property type          How the session was created and wired.
     * @property ptyBridge     Non-null when [type] is [SessionType.RootPty].
     * @property createdAt     Unix-epoch millis when the session was created.
     */
    data class SessionInfo(
        val id: String,
        val session: TerminalSession,
        val type: SessionType,
        val ptyBridge: PtyBridge?,
        val createdAt: Long
    )

    // =================================================================
    //  Public API
    // =================================================================

    fun addTextChangedListener(listener: (TerminalSession) -> Unit) {
        synchronized(mutex) { textChangedListeners.add(listener) }
    }

    fun removeTextChangedListener(listener: (TerminalSession) -> Unit) {
        synchronized(mutex) { textChangedListeners.remove(listener) }
    }

    fun addSessionFinishedListener(listener: (TerminalSession) -> Unit) {
        synchronized(mutex) { sessionFinishedListeners.add(listener) }
    }

    fun removeSessionFinishedListener(listener: (TerminalSession) -> Unit) {
        synchronized(mutex) { sessionFinishedListeners.remove(listener) }
    }

    fun notifyTerminalUpdated(session: TerminalSession) {
        synchronized(mutex) {
            textChangedListeners.forEach { it(session) }
        }
    }

    /**
     * Create a new terminal session of the given [type].
     *
     * For [SessionType.AppShell] the session runs a local shell directly
     * using Termux's built-in PTY -- no bridging required.
     *
     * For [SessionType.RootPty] a daemon-backed PTY is spawned via
     * [PtyManager.createPty] and bridged to the [TerminalSession].
     *
     * For [SessionType.Container] the proot binary is run directly as
     * the PTY shell process via [ProotRunner].
     *
     * @param type    The kind of session to create.
     * @param context Android [Context] (needed for container operations).
     * @return [Result] containing the created [TerminalSession] on success.
     */
    suspend fun createSession(type: SessionType, context: Context): Result<TerminalSession> =
        withContext(Dispatchers.Main) {
            try {
                val sessionId = UUID.randomUUID().toString()
                Log.d(TAG, "createSession: id=$sessionId type=$type")

                val (terminalSession, ptyBridge) = when (type) {
                    is SessionType.AppShell -> {
                        val ts = createTerminalSession(type.command, context, customCwd = type.cwd)
                        Pair(ts, null)
                    }

                    is SessionType.RootPty -> {
                        val ptyConn = PtyManager.createPty(
                            useRoot = true,
                            command = "/system/bin/sh"
                        ).getOrThrow()

                        val ts = createTerminalSession("/system/bin/sh", context)
                        val bridge = PtyBridge(ptyConn, ts).also { it.start() }
                        Pair(ts, bridge)
                    }

                    is SessionType.Container -> {
                        val container = ContainerManager.getContainer(context, type.containerName)
                            ?: throw IllegalStateException("Container '${type.containerName}' not found")
                        ProotInstaller.install(context).getOrThrow()
                        val binaryPath = ProotInstaller.expectedPath(context).absolutePath
                        val prootArgs = ProotRunner.buildProotArgs(context, container)
                        val ts = createTerminalSession(
                            shell = binaryPath,
                            context = context,
                            args = prootArgs,
                            customCwd = container.rootfsPath,
                        )
                        Pair(ts, null)
                    }
                }

                val info = SessionInfo(
                    id = sessionId,
                    session = terminalSession,
                    type = type,
                    ptyBridge = ptyBridge,
                    createdAt = System.currentTimeMillis()
                )

                synchronized(mutex) {
                    sessions[sessionId] = info
                }

                Log.d(TAG, "createSession: success id=$sessionId")
                Result.success(terminalSession)
            } catch (e: Exception) {
                Log.e(TAG, "createSession: failed type=$type", e)
                Result.failure(e)
            }
        }

    /**
     * Create or reuse a terminal session for the agent sheet.
     * If [currentPath] starts with `container://`, looks up that container's
     * session, creating one if needed. Otherwise starts a local shell.
     */
    suspend fun getOrCreateTerminal(context: Context, currentPath: String): TerminalSession {
        val containerName = if (currentPath.startsWith("container://")) {
            currentPath.removePrefix("container://").substringBefore('/')
        } else null

        val existing = synchronized(mutex) {
            if (containerName != null) {
                sessions.values.find {
                    it.type is SessionType.Container &&
                    (it.type as SessionType.Container).containerName == containerName
                }
            } else {
                sessions.values.find { it.type is SessionType.AppShell }
            }
        }
        if (existing != null) return existing.session

        val type: SessionType = if (containerName != null && containerName.isNotBlank()) {
            SessionType.Container(containerName)
        } else {
            SessionType.AppShell("/system/bin/sh")
        }

        return createSession(type, context).getOrThrow()
    }

    /**
     * Release a terminal session created by [getOrCreateTerminal].
     */
    fun releaseSession(session: TerminalSession) {
        val sessionId = synchronized(mutex) {
            val entry = sessions.entries.find { it.value.session === session }
            entry?.key
        }
        if (sessionId != null) destroySession(sessionId)
    }

    /**
     * Destroy an active terminal session.
     *
     * Stops any active I/O bridge ([PtyBridge]), finishes the underlying
     * [TerminalSession], and removes it from the internal map.
     *
     * @param sessionId UUID returned by [createSession].
     * @return [Result.success] on success, [Result.failure] if the session
     *         does not exist or cleanup fails.
     */
    fun destroySession(sessionId: String): Result<Unit> = runCatching {
        Log.d(TAG, "destroySession: id=$sessionId")

        val info: SessionInfo = synchronized(mutex) {
            sessions.remove(sessionId)
        } ?: throw IllegalArgumentException("Session not found: $sessionId")

        // Stop I/O bridge first.
        info.ptyBridge?.stop()

        // Terminate the shell process inside the session.
        if (info.session.getPid() > 0) {
            runCatching { info.session.finishIfRunning() }
        }

        Log.d(TAG, "destroySession: done id=$sessionId")
    }

    /**
     * Look up an active session by [sessionId].
     *
     * @return The [TerminalSession] or `null` if no session with that id exists.
     */
    fun getSession(sessionId: String): TerminalSession? = synchronized(mutex) {
        sessions[sessionId]?.session
    }

    /**
     * Check whether a container terminal session is currently active.
     */
    fun hasContainerSession(containerName: String): Boolean = synchronized(mutex) {
        sessions.values.any { it.type is SessionType.Container && (it.type as SessionType.Container).containerName == containerName }
    }

    /**
     * Return a snapshot of all active terminal sessions.
     *
     * The returned list is a copy -- iteration is safe without holding the lock.
     */
    fun getAllSessions(): List<TerminalSession> = synchronized(mutex) {
        sessions.values.map { it.session }.toList()
    }

    /**
     * Return a snapshot of all active [SessionInfo] records.
     */
    fun getAllSessionInfos(): List<SessionInfo> = synchronized(mutex) {
        sessions.values.toList()
    }

    /**
     * Destroy every active session and clear the session map.
     *
     * Intended for teardown scenarios (app shutdown, daemon disconnect).
     * Each session is destroyed individually; a single failure does not
     * prevent the remaining cleanups.
     */
    fun destroyAll() {
        Log.d(TAG, "destroyAll: destroying ${sessions.size} session(s)")

        val ids: List<String> = synchronized(mutex) {
            sessions.keys.toList()
        }

        for (id in ids) {
            runCatching { destroySession(id) }
        }

        // Paranoia: ensure the map is empty.
        synchronized(mutex) {
            sessions.clear()
        }

        Log.d(TAG, "destroyAll: done")
    }

    // =================================================================
    //  Internal helpers
    // =================================================================

    /**
     * Construct a [TerminalSession] with sensible defaults.
     *
     * The shell runs inside Termux's own PTY. For [SessionType.Container]
     * the proot binary is passed as [shell] with [args] containing proot
     * flags so the terminal session directly manages the container process.
     */
    private fun createTerminalSession(
        shell: String,
        context: Context,
        args: List<String>? = null,
        customCwd: String? = null,
    ): TerminalSession {
        val cwd = customCwd ?: context.filesDir.absolutePath
        val env = DEFAULT_ENV.toMutableList().apply {
            context.cacheDir.mkdirs()
            val tmpDir = context.cacheDir.absolutePath
            add("PROOT_TMP_DIR=$tmpDir")
        }
        Log.d(TAG, "Shell: $shell, Cwd: $cwd, Args: $args, Env: $env")
        return TerminalSession(
            shell,
            cwd,
            args?.toTypedArray(),
            env.toTypedArray(),
            DEFAULT_TRANSCRIPT_ROWS,
            SessionClient
        )
    }

    private val SessionClient = object : TerminalSessionClient {
        override fun onTextChanged(session: TerminalSession) {
            synchronized(mutex) {
                textChangedListeners.forEach { it(session) }
            }
        }
        override fun onTitleChanged(session: TerminalSession) {}
        override fun onSessionFinished(session: TerminalSession) {
            synchronized(mutex) {
                sessionFinishedListeners.forEach { it(session) }
            }
        }
        override fun onCopyTextToClipboard(session: TerminalSession, text: String) {}
        override fun onPasteTextFromClipboard(session: TerminalSession) {}
        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(session: TerminalSession) {}
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun getTerminalCursorStyle(): Int = 0

        override fun logError(tag: String, message: String) { Log.e(tag, message); Unit }
        override fun logWarn(tag: String, message: String) { Log.w(tag, message); Unit }
        override fun logInfo(tag: String, message: String) { Log.i(tag, message); Unit }
        override fun logDebug(tag: String, message: String) { Log.d(tag, message); Unit }
        override fun logVerbose(tag: String, message: String) { Log.v(tag, message); Unit }
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
            Log.e(tag, message, e)
            Unit
        }
        override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, "stacktrace", e); Unit }
    }
}
