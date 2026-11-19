package io.furryr.file.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import io.furryr.file.RuntimeForegroundService
import io.furryr.file.provider.terminal.TerminalProcess
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import java.io.Closeable

/**
 * Singleton managing all active terminal sessions.
 *
 * A session is just a backend-supplied [TerminalProcess] wrapped in Termux's
 * [TerminalSession]; its owner is the provider that created it, so this registry
 * stores no session type. Thread safety is provided via [synchronized] on
 * [mutex] for all map access.
 *
 * Used by [TermuxTerminalTab] to create and destroy the PTY behind a tab.
 */
object SessionManager {
    private const val TAG = "SessionManager"

    /** Max scrollback rows for each [TerminalSession]. */
    private const val DEFAULT_TRANSCRIPT_ROWS = 2000

    /** Default environment variable for terminal sessions. */
    private val DEFAULT_ENV = arrayOf("TERM=xterm-256color", "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/system/bin:/system/xbin:/vendor/bin")

    // -- internal state -----------------------------------------------

    /** Active sessions keyed by UUID string. */
    private val sessions = mutableMapOf<String, TerminalSession>()
    private val mutex = Any()
    private val terminalTargets = mutableMapOf<String, MutableList<Pair<String, String>>>()

    /** Container each session's shell runs in; absent for host and SSH shells. */
    private val sessionContainers = mutableMapOf<String, String>()

    /** Listeners notified when a session's content changes (needs redraw). */
    private val textChangedListeners = mutableSetOf<(TerminalSession) -> Unit>()

    /** Listeners notified when a session's terminal title changes. */
    private val titleChangedListeners = mutableSetOf<(TerminalSession) -> Unit>()

    /** Listeners notified when a session finishes. */
    private val sessionFinishedListeners = mutableSetOf<(TerminalSession) -> Unit>()

    /** Listeners notified when the session map changes (create/destroy). */
    private val sessionChangeListeners = mutableSetOf<() -> Unit>()

    /** Application context for clipboard access; set on first session creation. */
    private var appContext: Context? = null

    // =================================================================
    //  Public API
    // =================================================================

    fun addTextChangedListener(listener: (TerminalSession) -> Unit) {
        synchronized(mutex) { textChangedListeners.add(listener) }
    }

    fun removeTextChangedListener(listener: (TerminalSession) -> Unit) {
        synchronized(mutex) { textChangedListeners.remove(listener) }
    }

    fun addTitleChangedListener(listener: (TerminalSession) -> Unit) {
        synchronized(mutex) { titleChangedListeners.add(listener) }
    }

    fun removeTitleChangedListener(listener: (TerminalSession) -> Unit) {
        synchronized(mutex) { titleChangedListeners.remove(listener) }
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

    /**
     * Copy the listeners before calling out: a callback may add or remove a
     * listener (a finished session destroys itself), and iterating the live
     * set under [mutex] would throw ConcurrentModificationException.
     */
    private inline fun <T> notifyListeners(
        listeners: MutableSet<T>,
        dispatch: (T) -> Unit,
    ) {
        val snapshot = synchronized(mutex) { listeners.toList() }
        snapshot.forEach(dispatch)
    }

    fun notifyTerminalUpdated(session: TerminalSession) {
        notifyListeners(textChangedListeners) { it(session) }
    }

    /**
     * Register a provider-supplied [TerminalProcess] and build the Termux
     * session around it. The process token is the only thing Termux carries;
     * I/O goes through [TerminalBridge].
     */
    suspend fun createSession(
        process: TerminalProcess,
        context: Context,
        sessionId: String = UUID.randomUUID().toString(),
    ): Result<TerminalSession> =
        withContext(Dispatchers.Main) {
            if (appContext == null) appContext = context.applicationContext
            try {
                val token = TerminalBridge.register(process)
                val session = TerminalSession(
                    TerminalBridge.commandFor(token),
                    context.filesDir.absolutePath,
                    null,
                    DEFAULT_ENV,
                    DEFAULT_TRANSCRIPT_ROWS,
                    SessionClient,
                )
                synchronized(mutex) {
                    sessions[sessionId] = session
                    process.containerName?.let { sessionContainers[sessionId] = it }
                }
                notifyListeners(sessionChangeListeners) { it() }
                appContext?.let { RuntimeForegroundService.start(it) }
                Result.success(session)
            } catch (e: Exception) {
                Log.e(TAG, "createSession(process) failed", e)
                Result.failure(e)
            }
        }

    fun destroySession(sessionId: String): Result<Unit> = runCatching {
        Log.d(TAG, "destroySession: id=$sessionId")

        val removed = synchronized(mutex) {
            val session = sessions.remove(sessionId)
            sessionContainers.remove(sessionId)
            terminalTargets.remove(sessionId)
            session
        } ?: return@runCatching
        notifyListeners(sessionChangeListeners) { it() }

        // Terminate the shell process inside the session.
        if (removed.getPid() > 0) {
            runCatching { removed.finishIfRunning() }
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
            sessionContainers.filterValues { it == containerName }.keys.toList()
        }
        ids.forEach { runCatching { destroySession(it) } }
    }

    /**
     * Look up an active session by [sessionId].
     *
     * @return The [TerminalSession] or `null` if no session with that id exists.
     */
    fun getSession(sessionId: String): TerminalSession? = synchronized(mutex) {
        sessions[sessionId]
    }

    /** Id of an active [session], for callers that only hold the Termux object. */
    fun sessionIdOf(session: TerminalSession): String? = synchronized(mutex) {
        sessions.entries.firstOrNull { it.value === session }?.key
    }

    /**
     * Return a snapshot of all active terminal sessions.
     *
     * The returned list is a copy -- iteration is safe without holding the lock.
     */
    fun getAllSessions(): List<TerminalSession> = synchronized(mutex) {
        sessions.values.toList()
    }

    /** Nested run0 commands temporarily change the filesystem accepting input. */
    fun trackTerminalTarget(sessionId: String, target: String): Closeable {
        val token = UUID.randomUUID().toString()
        synchronized(mutex) {
            if (sessionId in sessions) terminalTargets.getOrPut(sessionId) { mutableListOf() }.add(token to target)
        }
        return Closeable {
            synchronized(mutex) {
                terminalTargets[sessionId]?.let { targets ->
                    targets.removeAll { it.first == token }
                    if (targets.isEmpty()) terminalTargets.remove(sessionId)
                }
            }
        }
    }

    /**
     * Target of a nested hub exec currently owning input in [sessionId], or
     * null when the terminal's own provider decides where dropped paths go.
     */
    fun terminalTarget(sessionId: String): String? = synchronized(mutex) {
        terminalTargets[sessionId]?.lastOrNull()?.second
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

        // Paranoia: ensure the maps are empty.
        synchronized(mutex) {
            sessions.clear()
            sessionContainers.clear()
            terminalTargets.clear()
        }

        Log.d(TAG, "destroyAll: done")
    }

    // =================================================================
    //  Internal helpers
    // =================================================================

    private val SessionClient = object : TerminalSessionClient {
        override fun onTextChanged(session: TerminalSession) {
            notifyListeners(textChangedListeners) { it(session) }
        }
        override fun onTitleChanged(session: TerminalSession) {
            notifyListeners(titleChangedListeners) { it(session) }
        }
        override fun onSessionFinished(session: TerminalSession) {
            notifyListeners(sessionFinishedListeners) { it(session) }
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
