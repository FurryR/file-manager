package io.furryr.file.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import io.furryr.file.RuntimeForegroundService
import io.furryr.file.container.ContainerManager
import io.furryr.file.core.ContainerUris
import io.furryr.file.pulse.PulseManager
import io.furryr.file.wayland.WaylandManager
import io.furryr.file.daemon.DaemonManager
import io.furryr.file.hub.Hub
import io.furryr.file.provider.remote.SshTerminals
import io.furryr.file.util.ProcessHelper
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import java.io.Closeable

/**
 * Singleton managing all active terminal sessions.
 *
 * Supports three session types:
 * - [SessionType.AppShell] -- local shell inside Termux's own PTY
 * - [SessionType.Container] -- proot container process run directly as
 *   the PTY shell; no external bridge needed
 * - [SessionType.Ssh] -- remote shell on a connection dedicated to the terminal
 *
 * Thread safety is provided via [synchronized] on [mutex] for all map access.
 *
 * Used by [TermuxTerminalTab] to create and destroy the PTY behind a tab.
 */
/** Terminal-address profiles: the one place that formats/parses SSH shell markers. */
object TerminalProfiles {
    fun sshAddress(profile: SessionManager.SshProfile): String =
        io.furryr.file.core.Path.of(
            scheme = "ssh",
            host = profile.authority.ifEmpty { profile.locationId },
        ).toString()

    fun isSsh(address: String): Boolean =
        runCatching { io.furryr.file.core.Path.parse(address).scheme }.getOrNull() == "ssh"

    /** The authority/id carried by an SSH shell marker, or null. */
    fun sshAuthority(address: String): String? {
        val parsed = runCatching { io.furryr.file.core.Path.parse(address) }.getOrNull() ?: return null
        if (parsed.scheme != "ssh") return null
        return authorityOf(parsed)
    }

    /** `user@host[:port]`, matching [io.furryr.file.provider.remote.RemoteLocation.pathAuthority]. */
    private fun authorityOf(parsed: io.furryr.file.core.Path): String? {
        val host = parsed.host?.removeSurrounding("[", "]") ?: return null
        return buildString {
            parsed.userInfo?.takeIf { it.isNotEmpty() }?.let { append(it).append('@') }
            if (':' in host) append('[').append(host).append(']') else append(host)
            val port = parsed.port
            if (port != null && port != 22) append(':').append(port)
        }
    }

    /** The root address of an SSH shell marker. */
    fun sshRoot(authority: String): String =
        io.furryr.file.core.Path.of(scheme = "ssh", host = authority).toString()

    /** Resolve a file path into the SSH terminal session type it needs. */
    fun resolve(path: String): Pair<SessionManager.SessionType, String>? {
        val parsed = runCatching { io.furryr.file.core.Path.parse(path) }.getOrNull() ?: return null
        if (parsed.scheme != "ssh") return null
        val authority = authorityOf(parsed) ?: return null
        val location = SshTerminals.find(authority)
        val remotePath = "/" + parsed.segmentList.joinToString("/")
        val profile = SessionManager.SshProfile(
            locationId = location.id,
            locationName = location.name,
            startPath = remotePath,
            authority = location.pathAuthority,
        )
        return SessionManager.SessionType.Ssh(profile) to location.name
    }
}

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
    private val terminalTargets = mutableMapOf<String, MutableList<Pair<String, String>>>()

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
        data class Container(
            val containerName: String,
            /** Directory to enter once the container login profile runs. */
            val startPath: String? = null,
        ) : SessionType()

        /** Interactive shell on a saved SSH remote connection. */
        data class Ssh(val profile: SshProfile) : SessionType()
    }

    /** One SSH terminal profile; the single home for duplicated connection fields. */
    data class SshProfile(
        val locationId: String,
        val locationName: String,
        /** Remote directory to enter after the shell starts. */
        val startPath: String? = null,
        /** Readable `user@host` authority used in file paths: `[host]` for IPv6, `:port` when not 22. */
        val authority: String = "",
    )

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

    fun notifyTerminalUpdated(session: TerminalSession) {
        synchronized(mutex) {
            textChangedListeners.forEach { it(session) }
        }
    }

    /**
     * Create a new terminal session of the given [type].
     *
     * Local and container shells spawn on a daemon -- the app daemon for a
     * local shell, the container's own daemon for `container://` -- and
     * Termux's terminal owns the returned PTY fd directly. An SSH session
     * instead bridges that fd to a shell channel on a connection dedicated to
     * that terminal.
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
                        createTerminalSession(type.command, context, customCwd = type.cwd, terminalId = sessionId)

                    is SessionType.Container ->
                        createContainerTerminal(type.containerName, type.startPath, context, sessionId)

                    is SessionType.Ssh ->
                        createSshTerminal(type, context, sessionId)
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

                // Show the runtime foreground notification while any session
                // is alive (also keeps the process alive in the background).
                appContext?.let { RuntimeForegroundService.start(it) }

                Log.d(TAG, "createSession: success id=$sessionId")
                Result.success(terminalSession)
            } catch (e: Exception) {
                Log.e(TAG, "createSession: failed type=$type", e)
                Result.failure(e)
            }
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
            terminalTargets.remove(sessionId)
            if (removed != null) sessionChangeListeners.forEach { it() }
            removed
        } ?: return@runCatching

        // Terminate the shell process inside the session. SSH shells are
        // bridged in-process, so close that bridge instead of signalling a pid.
        if (info.type is SessionType.Ssh) {
            ProcessHelper.closeSshTerminal(info.id)
        } else if (info.session.getPid() > 0) {
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

    /** Id of an active [session], for callers that only hold the Termux object. */
    fun sessionIdOf(session: TerminalSession): String? = synchronized(mutex) {
        sessions.entries.firstOrNull { it.value.session === session }?.key
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

    fun terminalTarget(sessionId: String): String = synchronized(mutex) {
        terminalTargets[sessionId]?.lastOrNull()?.second ?: when (val type = sessions[sessionId]?.type) {
            is SessionType.Container -> "container-${type.containerName}"
            is SessionType.Ssh -> TerminalProfiles.sshAddress(type.profile)
            else -> "app"
        }
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
        startPath: String?,
        context: Context,
        terminalId: String,
    ): TerminalSession {
        // Fail early with a clear message. The shell itself is spawned by
        // ProcessHelper on the container's dedicated daemon via the marker
        // command, so the TerminalSession owns the container PTY fd directly
        // (input/output go straight to the container shell).
        ContainerManager.getContainer(context, containerName)
            ?: throw IllegalStateException("Container '$containerName' not found")
        // Warm the container daemon off the main thread so the synchronous
        // spawn inside TerminalSession is fast.
        withContext(Dispatchers.IO) { DaemonManager.warmNamed(containerName).getOrThrow() }
        return createTerminalSession(
            ContainerUris.command(containerName),
            context,
            customCwd = "/",
            startPath = startPath,
            terminalId = terminalId,
        )
    }

    /**
     * Create the terminal for a saved SSH remote connection.
     *
     * [ProcessHelper] owns the in-process bridge between the terminal fd and
     * the SSH shell channel. The shell runs on a session dedicated to this
     * terminal; file operations and AI exec keep using the shared connection.
     */
    private suspend fun createSshTerminal(
        type: SessionType.Ssh,
        context: Context,
        terminalId: String,
    ): TerminalSession {
        // Open the shell before the terminal attaches, so authentication and
        // channel errors reach createSession instead of the UI thread. The rows
        // and columns are corrected once Termux reports its real size.
        val shell = withContext(Dispatchers.IO) {
            val location = SshTerminals.find(type.profile.locationId)
            SshTerminals.open(location, type.profile.startPath, 24, 80)
        }
        ProcessHelper.prepareSshTerminal(terminalId, shell)
        return try {
            createTerminalSession(
                TerminalProfiles.sshAddress(type.profile),
                context,
                customCwd = "/",
                startPath = type.profile.startPath,
                terminalId = terminalId,
            )
        } catch (failure: Exception) {
            ProcessHelper.discardSshTerminal(terminalId)
            throw failure
        }
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
        startPath: String? = null,
        terminalId: String,
    ): TerminalSession {
        val cwd = customCwd ?: context.filesDir.absolutePath
        Hub.start(context)
        val env = DEFAULT_ENV.toMutableList().apply {
            if (ContainerUris.isContainer(shell)) {
                PulseManager.environment().forEach { (key, value) -> add("$key=$value") }
                WaylandManager.environment(context).forEach { (key, value) -> add("$key=$value") }
                startPath?.let { add("CONTAINER_START_PATH=$it") }
            }
            if (TerminalProfiles.isSsh(shell)) {
                add("SSH_TERMINAL_ID=$terminalId")
                startPath?.let { add("SSH_START_PATH=$it") }
            }
            // HUB_SOCKET is added by ProcessHelper once the target is known.
            add("HUB_TERMINAL=$terminalId")
            Hub.binDir?.let { dir ->
                val pathIndex = indexOfFirst { it.startsWith("PATH=") }
                if (pathIndex >= 0) {
                    this[pathIndex] =
                        "PATH=${this[pathIndex].removePrefix("PATH=")}:${dir.absolutePath}"
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
        override fun onTitleChanged(session: TerminalSession) {
            synchronized(mutex) {
                titleChangedListeners.forEach { it(session) }
            }
        }
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
