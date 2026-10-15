package io.furryr.file.agent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import io.furryr.file.TerminalForegroundService
import io.furryr.file.core.DaemonManager
import io.furryr.file.core.hub.Hub
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Singleton managing all active terminal sessions.
 *
 * Supports two session types:
 * - [SessionType.AppShell] -- local shell inside Termux's own PTY
 * - [SessionType.Container] -- proot container process run directly as
 *   the PTY shell; no external bridge needed
 *
 * Thread safety is provided via [synchronized] on [mutex] for all map access.
 *
 * Used by [io.furryr.file.TerminalSheet] to drive terminal sessions.
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

    /** Listeners notified when the session map changes (create/destroy). */
    private val sessionChangeListeners = mutableSetOf<() -> Unit>()

    /** Application context for clipboard access; set on first session creation. */
    private var appContext: Context? = null

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
    }

    /**
     * Full descriptor for an active session.
     *
     * @property id            UUID assigned at creation time.
     * @property session       The Termux [TerminalSession] for display/input.
     * @property type          How the session was created and wired.
     * @property createdAt     Unix-epoch millis when the session was created.
     */
    data class SessionInfo(
        val id: String,
        val session: TerminalSession,
        val type: SessionType,
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

    fun addSessionChangeListener(listener: () -> Unit) {
        synchronized(mutex) { sessionChangeListeners.add(listener) }
    }

    fun removeSessionChangeListener(listener: () -> Unit) {
        synchronized(mutex) { sessionChangeListeners.remove(listener) }
    }

    fun notifyTerminalUpdated(session: TerminalSession) {
        synchronized(mutex) {
            textChangedListeners.forEach { it(session) }
        }
    }

    /**
     * Create a new terminal session of the given [type].
     *
     * Both kinds spawn their shell on a daemon -- the app daemon for a local
     * shell, the container's own daemon for `container://` -- and Termux's
     * terminal then owns the returned PTY fd directly.
     *
     * @param type    The kind of session to create.
     * @param context Android [Context] (needed for container operations).
     * @return [Result] containing the created [TerminalSession] on success.
     */
    suspend fun createSession(type: SessionType, context: Context): Result<TerminalSession> =
        withContext(Dispatchers.Main) {
            if (appContext == null) appContext = context.applicationContext
            try {
                val sessionId = UUID.randomUUID().toString()
                Log.d(TAG, "createSession: id=$sessionId type=$type")

                val terminalSession = when (type) {
                    is SessionType.AppShell ->
                        createTerminalSession(type.command, context, customCwd = type.cwd)

                    is SessionType.Container -> createContainerTerminal(type.containerName, context)
                }

                val info = SessionInfo(
                    id = sessionId,
                    session = terminalSession,
                    type = type,
                    createdAt = System.currentTimeMillis()
                )

                synchronized(mutex) {
                    sessions[sessionId] = info
                    sessionChangeListeners.forEach { it() }
                }

                // Show the terminal foreground notification while any session
                // is alive (also keeps the process alive in the background).
                appContext?.let { TerminalForegroundService.start(it) }

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
     * Finishes the underlying [TerminalSession] and removes it from the
     * internal map.
     *
     * @param sessionId UUID returned by [createSession].
     * @return [Result.success] on success, [Result.failure] if the session
     *         does not exist or cleanup fails.
     */
    fun destroySession(sessionId: String): Result<Unit> = runCatching {
        Log.d(TAG, "destroySession: id=$sessionId")

        val info: SessionInfo = synchronized(mutex) {
            val removed = sessions.remove(sessionId)
            if (removed != null) sessionChangeListeners.forEach { it() }
            removed
        } ?: return@runCatching

        // Terminate the shell process inside the session.
        if (info.session.getPid() > 0) {
            runCatching { info.session.finishIfRunning() }
        }

        // The foreground service observes session changes and stops itself
        // once nothing is left running.

        Log.d(TAG, "destroySession: done id=$sessionId")
    }

    /**
     * Destroy every active session belonging to [containerName].
     *
     * Used by the sidebar "kill" action to close all terminals of a container.
     */
    fun destroyContainerSessions(containerName: String) {
        val ids = synchronized(mutex) {
            sessions.values
                .filter {
                    it.type is SessionType.Container &&
                        (it.type as SessionType.Container).containerName == containerName
                }
                .map { it.id }
        }
        ids.forEach { runCatching { destroySession(it) } }
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
     * Create the terminal for a container session.
     *
     * The daemon owns the container setup (mount namespace + chroot, or proot)
     * for both backends. The container's fixed backend determines which daemon
     * is used; chroot containers fail if root is unavailable.
     */
    private suspend fun createContainerTerminal(
        containerName: String,
        context: Context,
    ): TerminalSession {
        // Fail early with a clear message. The shell itself is spawned by
        // ProcessHelper on the container's dedicated daemon via the marker
        // command, so the TerminalSession owns the container PTY fd directly
        // (input/output go straight to the container shell).
        ContainerManager.getContainer(context, containerName)
            ?: throw IllegalStateException("Container '$containerName' not found")
        // Warm the container daemon off the main thread so the synchronous
        // spawn inside TerminalSession is fast.
        withContext(Dispatchers.IO) { DaemonManager.warmNamed(containerName) }
        return createTerminalSession("container://$containerName", context, customCwd = "/")
    }

    /**
     * Construct a [TerminalSession] with sensible defaults.
     *
     * The shell is spawned on the daemon by [io.furryr.file.util.ProcessHelper]
     * (the ASM-rewritten `com.termux.terminal.JNI` path); Termux then drives the
     * returned PTY fd directly.
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
            // Make `run0` reachable from the shell and point it at the hub.
            Hub.socketName?.let { add("${Hub.SOCKET_ENV}=$it") }
            Hub.binDir?.let { dir ->
                val pathIndex = indexOfFirst { it.startsWith("PATH=") }
                if (pathIndex >= 0) {
                    this[pathIndex] =
                        "PATH=${dir.absolutePath}:${this[pathIndex].removePrefix("PATH=")}"
                } else {
                    add("PATH=${dir.absolutePath}")
                }
            }
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
        override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
            val ctx = appContext ?: return
            val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("terminal", text))
        }
        override fun onPasteTextFromClipboard(session: TerminalSession) {
            val ctx = appContext ?: return
            val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val paste = clip.getItemAt(0).coerceToText(ctx)
                if (paste.isNotEmpty()) {
                    session.emulator?.paste(paste.toString())
                }
            }
        }
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
