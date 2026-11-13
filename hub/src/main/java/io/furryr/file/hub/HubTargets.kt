package io.furryr.file.hub

import android.util.Log
import io.furryr.file.daemon.DaemonManager
import io.furryr.file.daemon.HubActions
import io.furryr.file.proto.hub.ListTargetsResponse
import io.furryr.file.proto.hub.Target

/** The hub's target projection: execution catalog plus registered file locations. */
internal object HubTargets {
    private const val TAG = "HubTargets"

    fun list(): ListTargetsResponse {
        val execTargets = DaemonManager.targetCatalog().map { target ->
            Target.newBuilder()
                .setId(target.id)
                .setElevated(target.elevated)
                .setRunning(target.running)
                .setKind("exec")
                .setLabel(target.label)
                .setRoot(target.root)
                .build()
        }
        val locations = runCatching { HubActions.locationTargets?.invoke().orEmpty() }
            .onFailure { Log.w(TAG, "location enumeration failed", it) }
            .getOrDefault(emptyList())
        val fileTargets = locations
            .filter { it.root.isNotBlank() }
            .distinctBy { it.root }
            .map { location ->
                Target.newBuilder()
                    .setId(location.root)
                    .setKind(location.kind)
                    .setLabel(location.label)
                    .setRoot(location.root)
                    .setRunning(true)
                    .build()
            }
        return ListTargetsResponse.newBuilder()
            .addAllTargets(execTargets)
            .addAllTargets(fileTargets)
            .build()
    }
}
