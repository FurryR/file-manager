package io.furryr.file.terminal

import android.app.Activity
import android.content.Context
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.termux.terminal.TerminalSession
import io.furryr.file.R
import io.furryr.file.container.ContainerPaths
import io.furryr.file.container.PathUnavailableException
import io.furryr.file.core.ContainerUriResolver
import io.furryr.file.core.ContainerUris
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
    /** Shell to spawn; null is not used by this implementation. */
    val type: SessionManager.SessionType?,
    val label: String,
)

private fun sanitizedTitle(raw: String?): String? =
    raw?.filterNot { it.isISOControl() }?.trim()?.takeIf { it.isNotEmpty() }

private fun sessionLabel(type: SessionManager.SessionType): String = when (type) {
    is SessionManager.SessionType.Container -> type.containerName
    is SessionManager.SessionType.AppShell -> "Local"
    is SessionManager.SessionType.Ssh -> type.profile.locationName
}

private fun terminalSpecFor(path: String): TerminalSpec {
    val type = TerminalProfiles.resolve(path)?.first ?: if (ContainerUris.isContainer(path)) {
        val resolved = ContainerUriResolver.resolveContainerUri(path).getOrThrow()
        val name = resolved.containerName
        if (name != null) {
            SessionManager.SessionType.Container(name, resolved.containerPath)
        } else {
            SessionManager.SessionType.AppShell(cwd = "/sdcard")
        }
    } else if (path.startsWith("/")) {
        SessionManager.SessionType.AppShell(cwd = path)
    } else {
        SessionManager.SessionType.AppShell(cwd = "/sdcard")
    }
    return TerminalSpec(type, sessionLabel(type))
}

private fun terminalPathForTarget(
    context: Context,
    path: String,
    target: String,
    isDirectory: Boolean,
): String {
    val sshAuthority = TerminalProfiles.sshAuthority(target)
    if (sshAuthority != null) {
        // sshRoot always ends in '/'; appending another one would require a
        // double slash and reject every real entry under the connection.
        val root = TerminalProfiles.sshRoot(sshAuthority).trimEnd('/')
        if (path != root && !path.startsWith("$root/")) {
            throw IllegalArgumentException(
                context.getString(R.string.terminal_drop_wrong_connection),
            )
        }
        return path.removePrefix(root).ifEmpty { "/" }
    }
    return ContainerPaths.terminalPath(context, path, target, isDirectory)
}

/**
 * Termux's terminal view, extra-key row, IME ownership and drop target.
 *
 * One instance is owned by one tab and lives until [dispose]. It never removes
 * its own tab; the shell watches [alive] and closes it.
 */
internal class TermuxTerminalTab private constructor(
    private val context: Context,
    private val spec: TerminalSpec,
) : TerminalTab {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Completes when the current connect attempt succeeds or fails. */
    private var ready = CompletableDeferred<Unit>()

    /** One input state per tab; the TerminalView that reads it is per tab too. */
    private val inputState = TerminalInputState()

    /** Installed by enter(): the shell context that owns the drawer. */
    private var terminalContext: TerminalContext? = null

    override val id: String = UUID.randomUUID().toString()

    private var displayTitle by mutableStateOf<String?>(null)

    override val title: String
        get() = displayTitle ?: spec.label

    private var sessionId by mutableStateOf<String?>(null)
    private var connecting by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    private var session by mutableStateOf<TerminalSession?>(null)
    private var closed = false

    private val titleListener: (TerminalSession) -> Unit = { changed ->
        if (changed === session) displayTitle = sanitizedTitle(changed.title)
    }

    private val finishedListener: (TerminalSession) -> Unit = { finished ->
        if (finished === session) sessionId?.let { SessionManager.destroySession(it) }
    }

    /** Spawn [spec] in this tab. */
    private fun connect() {
        if (closed || connecting || session != null) return
        val type = spec.type ?: run {
            error = context.getString(R.string.terminal_no_shell)
            ready.complete(Unit)
            return
        }
        if (ready.isCompleted) ready = CompletableDeferred()
        connecting = true
        error = null
        scope.launch {
            SessionManager.createSession(type, context)
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

    override val alive: Boolean
        get() {
            if (closed) return false
            val id = sessionId ?: return true // still connecting
            return SessionManager.getSession(id) != null
        }

    override fun acceptDrop(entry: FileEntry): Boolean {
        val targetSession = session ?: return false
        val id = sessionId ?: return false
        if (!targetSession.isRunning) return false
        val target = SessionManager.terminalTarget(id)
        scope.launch {
            try {
                val path = withContext(Dispatchers.IO) {
                    terminalPathForTarget(context, entry.path, target, entry.isDirectory)
                }
                check(path.none { it.code < 32 || it.code == 127 }) {
                    context.getString(R.string.terminal_drop_invalid_path)
                }
                check(
                    session === targetSession && targetSession.isRunning &&
                        SessionManager.terminalTarget(id) == target,
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
        ctx.commit { enabled, modifier -> TerminalBody(enabled, modifier) }
        connect()
        ready.await()
    }

    override fun leave() {
        terminalContext = null
        inputState.leave()
    }

    // Legacy window resizing is still the correct IME mode for target SDK 28.
    @Suppress("DEPRECATION")
    @Composable
    private fun TerminalBody(enabled: Boolean, modifier: Modifier) {
        val activity = LocalContext.current as? Activity
        // The terminal is the only screen that holds the IME above its
        // content. The mode follows the composed tab instead of the sheet
        // flag, so it cannot leak to a tab that is not showing a terminal.
        DisposableEffect(activity, enabled) {
            if (enabled) {
                activity?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
            onDispose {
                if (enabled) {
                    activity?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
                }
            }
        }
        val current = session
        val failure = error
        if (current == null) {
            if (failure == null) {
                TerminalViewComposable(
                    session = null,
                    modifier = modifier,
                    enabled = false,
                    inputState = inputState,
                )
            } else {
                Column(
                    modifier = modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        Icons.Default.Terminal,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(48.dp),
                    )
                    Text(
                        failure,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    if (!connecting) {
                        TextButton(
                            onClick = { connect() },
                            modifier = Modifier.padding(top = 4.dp),
                        ) {
                            Text(stringResource(R.string.terminal_retry))
                        }
                    }
                }
            }
            return
        }
        Column(modifier.fillMaxSize()) {
            TerminalViewComposable(
                session = current,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                enabled = enabled,
                inputState = inputState,
            )
            TerminalExtraKeys(
                inputState = inputState,
                enabled = enabled,
                onOpenDrawer = { terminalContext?.requestOpenDrawer() },
            )
        }
    }

    override fun dispose() {
        if (closed) return
        closed = true
        leave()
        ready.complete(Unit)
        sessionId?.let { SessionManager.destroySession(it) }
        detach()
        scope.cancel()
    }

    companion object {
        /** Create a tab body that spawns the shell for [path] on first enter. */
        internal fun start(
            context: Context,
            path: String,
        ): TermuxTerminalTab =
            TermuxTerminalTab(
                context.applicationContext,
                terminalSpecFor(path),
            )
    }
}
