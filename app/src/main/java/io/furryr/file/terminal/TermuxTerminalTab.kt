package io.furryr.file.terminal

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.termux.terminal.TerminalSession
import io.furryr.file.R
import io.furryr.file.provider.terminal.ExecProvider
import io.furryr.file.provider.terminal.TerminalProcess
import io.furryr.file.container.ContainerPaths
import io.furryr.file.container.PathUnavailableException
import io.furryr.file.core.Path
import io.furryr.file.core.Vfs
import io.furryr.file.core.VFolder
import io.furryr.file.core.model.FileEntry
import io.furryr.file.daemon.shellQuote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

private const val TAG = "TermuxTerminal"

/** How a terminal tab is spawned; captured when the tab is created. */
private data class TerminalSpec(
    val label: String,
    val remote: Boolean,
    val provider: ExecProvider,
    val cwd: VFolder,
    val vfs: Vfs,
    val create: suspend (rows: Int, cols: Int) -> Result<TerminalProcess>,
)

private fun sanitizedTitle(raw: String?): String? =
    raw?.filterNot { it.isISOControl() }?.trim()?.takeIf { it.isNotEmpty() }

/**
 * Termux's terminal view, extra-key row, IME ownership and drop target.
 *
 * One instance is owned by one tab and lives until [dispose]. It removes its
 * own tab on a normal shell exit; an abnormal end keeps the buffer and lets
 * the user close the tab explicitly.
 */
internal class TermuxTerminalTab private constructor(
    private val context: Context,
    private val spec: TerminalSpec,
) : TerminalViewTab {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Completes when the current connect attempt succeeds or fails. */
    private var ready = CompletableDeferred<Unit>()

    /** One input state per tab; the shared terminal surface binds to it. */
    override val inputState = TerminalInputState()

    /** Installed by enter(): the shell context that owns the drawer. */
    private var terminalContext: TerminalContext? = null

    override val id: String = UUID.randomUUID().toString()

    private var displayTitle by mutableStateOf<String?>(null)

    override val title: String
        get() = displayTitle ?: spec.label

    private var sessionId by mutableStateOf<String?>(null)
    override var connecting by mutableStateOf(false)
        private set
    override var error by mutableStateOf<String?>(null)
        private set
    override var connectionLost by mutableStateOf(false)
        private set
    override val remote: Boolean = spec.remote
    override var session by mutableStateOf<TerminalSession?>(null)
        private set
    private var closed = false
    private var closing = false

    private val titleListener: (TerminalSession) -> Unit = { changed ->
        if (changed === session) displayTitle = sanitizedTitle(changed.title)
    }

    private val finishedListener: (TerminalSession) -> Unit = { finished ->
        if (finished === session && !closed) {
            if (finished.exitStatus == 0) {
                // A normal shell exit closes its own tab. Only an abnormal end
                // keeps the buffer and shows the connection-lost overlay.
                if (!closing) {
                    closing = true
                    scope.launch { Terminal.requestClose(id) }
                }
            } else {
                connectionLost = true
                sessionId?.let { SessionManager.destroySession(it) }
            }
        }
    }

    /** Spawn [spec] in this tab. */
    private fun connect() {
        if (closed || connecting || session != null) return
        if (ready.isCompleted) ready = CompletableDeferred()
        connecting = true
        connectionLost = false
        error = null
        scope.launch {
            spec.create(24, 80)
                .mapCatching { SessionManager.createSession(it, context).getOrThrow() }
                .onSuccess {
                    bind(it)
                    ready.complete(Unit)
                }
                .onFailure { failure ->
                    Log.e(TAG, "Failed to create terminal", failure)
                    if (!closed) {
                        error = failure.message
                            ?: failure::class.simpleName
                            ?: context.getString(R.string.terminal_failed)
                        connecting = false
                    }
                    ready.complete(Unit)
                }
        }
    }

    private fun bind(created: TerminalSession) {
        session = created
        sessionId = SessionManager.sessionIdOf(created)
        displayTitle = sanitizedTitle(created.title)
        connecting = false
        connectionLost = false
        error = null
        if (closed) {
            // The tab was closed while it was connecting; do not leave an
            // untracked PTY behind.
            sessionId?.let { SessionManager.destroySession(it) }
            return
        }
        SessionManager.addTitleChangedListener(titleListener)
        SessionManager.addSessionFinishedListener(finishedListener)
    }

    private fun detach() {
        SessionManager.removeTitleChangedListener(titleListener)
        SessionManager.removeSessionFinishedListener(finishedListener)
    }

    override fun acceptDrop(entry: FileEntry): Boolean {
        val targetSession = session ?: return false
        val id = sessionId ?: return false
        if (!targetSession.isRunning) return false
        val override = SessionManager.terminalTarget(id)
        scope.launch {
            try {
                val path = withContext(Dispatchers.IO) {
                    if (override != null) {
                        // A nested hub exec currently owns input in this tab, so
                        // the tab's own provider no longer describes the target.
                        ContainerPaths.terminalPath(context, entry.path, override, entry.isDirectory)
                    } else {
                        val node = spec.vfs.node(Path.parse(entry.path)).getOrThrow()
                        spec.provider.terminalPath(spec.cwd, node)
                            ?: throw IllegalArgumentException(
                                context.getString(R.string.terminal_drop_wrong_location),
                            )
                    }
                }
                check(path.none { it.code < 32 || it.code == 127 }) {
                    context.getString(R.string.terminal_drop_invalid_path)
                }
                check(
                    session === targetSession && targetSession.isRunning &&
                        SessionManager.terminalTarget(id) == override,
                ) { context.getString(R.string.terminal_drop_failed) }
                val completion = path + if (entry.isDirectory && !path.endsWith('/')) "/" else ""
                targetSession.emulator?.paste(shellQuote(completion) + " ")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Cannot insert dropped path", e)
                val message = when (e) {
                    is PathUnavailableException -> context.getString(
                        R.string.terminal_drop_unreachable,
                        e.containerName,
                    )
                    else -> e.message ?: context.getString(R.string.terminal_drop_failed)
                }
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
        return true
    }

    override suspend fun enter(ctx: TerminalContext) {
        terminalContext = ctx
        connect()
        ready.await()
    }

    override fun retry() {
        connect()
    }

    override fun requestOpenDrawer() {
        terminalContext?.requestOpenDrawer()
    }

    override fun requestClose() {
        terminalContext?.requestClose()
    }

    override fun blur() {
        terminalContext = null
        inputState.blur()
    }

    override fun dispose() {
        if (closed) return
        closed = true
        blur()
        ready.complete(Unit)
        sessionId?.let { SessionManager.destroySession(it) }
        detach()
        scope.cancel()
    }

    companion object {
        /** Create a tab body that starts the provider backend on first enter. */
        internal fun start(
            context: Context,
            label: String,
            provider: ExecProvider,
            cwd: VFolder,
            remote: Boolean = false,
            vfs: Vfs,
            create: suspend (rows: Int, cols: Int) -> Result<TerminalProcess>,
        ): TermuxTerminalTab =
            TermuxTerminalTab(
                context.applicationContext,
                TerminalSpec(
                    label = label,
                    remote = remote,
                    provider = provider,
                    cwd = cwd,
                    vfs = vfs,
                    create = create,
                ),
            )
    }
}
