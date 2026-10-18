package io.furryr.file.core

import java.io.FileDescriptor
import java.io.Closeable

/** App-owned actions exposed through the hub, without app/provider dependencies. */
object HubActions {
    /**
     * Invoked on the hub's IO thread. The borrowed descriptor is valid only for
     * this call; resolve it synchronously before queuing any UI work. Throw to
     * return a failure to the client.
     */
    @Volatile
    var onHere: ((FileDescriptor, HubCallerRoot) -> Unit)? = null

    @Volatile
    var resolveRoot: ((FileDescriptor) -> HubCallerRoot)? = null

    /** Resolve a borrowed cwd descriptor to a pathname in the selected target. */
    @Volatile
    var resolveCwd: ((FileDescriptor, HubCallerRoot, String) -> String)? = null

    /** A lease for the foreground run0 target; closing it restores its parent. */
    @Volatile
    var onTerminalTarget: ((String, String) -> Closeable)? = null

    /** Read the current settings for each request; never cache credentials. */
    @Volatile
    var aiConfigProvider: (() -> HubAiConfig)? = null
}

data class HubAiConfig(val baseUrl: String, val apiKey: String, val model: String)

data class HubCallerRoot(val containerName: String?, val hostPath: String)
