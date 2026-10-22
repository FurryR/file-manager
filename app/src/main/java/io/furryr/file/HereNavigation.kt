package io.furryr.file

import android.content.Context
import io.furryr.file.agent.ContainerManager
import io.furryr.file.agent.ContainerPaths
import io.furryr.file.agent.SessionManager
import io.furryr.file.core.DaemonManager
import io.furryr.file.core.DaemonTarget
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
        HubActions.resolveRoot = { containerId -> ContainerPaths.callerRoot(appContext, containerId) }
        HubActions.resolveCwd = { root, target, path ->
            ContainerPaths.workingDirectory(appContext, root, target, path)
        }
        HubActions.containerIds = {
            ContainerManager.listContainers(appContext).getOrDefault(emptyList())
                .map { DaemonManager.hubId(DaemonTarget.Named(it.name)) }
        }
        HubActions.onTerminalTarget = SessionManager::trackTerminalTarget
        HubActions.onHere = { path, root ->
            val selected = ContainerPaths.revealPath(appContext, path, root)
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
