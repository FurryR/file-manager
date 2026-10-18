package io.furryr.file

import android.content.Context
import io.furryr.file.agent.ContainerPaths
import io.furryr.file.agent.SessionManager
import io.furryr.file.core.HubActions
import io.furryr.file.util.Path
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

/** Converts borrowed hub descriptors into app-owned navigation requests. */
object HereNavigation {
    data class Request(val directory: String, val selectedPath: String?)

    // Retain requests across activity recreation; only the current UI consumes them.
    private val pending = Channel<Request>(Channel.CONFLATED)
    val requests = pending.receiveAsFlow()

    fun install(context: Context) {
        val appContext = context.applicationContext
        HubActions.resolveRoot = { ContainerPaths.callerRoot(appContext, it) }
        HubActions.resolveCwd = { fd, root, target -> ContainerPaths.workingDirectory(appContext, fd, root, target) }
        HubActions.onTerminalTarget = SessionManager::trackTerminalTarget
        HubActions.onHere = { fd, root ->
            val selected = ContainerPaths.revealPath(appContext, fd, root)
            val path = Path.parse(selected)
            val request = when {
                selected == "/" -> Request("/", null)
                path.scheme == "container" && path.isRoot -> Request("container:///", "container://${path.host}")
                else -> Request(requireNotNull(path.parent()).toFullUri(), selected)
            }
            check(pending.trySend(request).isSuccess) { "file navigation is unavailable" }
        }
    }
}
