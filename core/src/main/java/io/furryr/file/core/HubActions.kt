package io.furryr.file.core

import java.io.Closeable

/** App-owned actions exposed through the hub, without app/provider dependencies. */
object HubActions {
    /**
     * Invoked on the hub's IO thread. [containerId] is `host`, `app`, or
     * `container-<name>`. Throw to return a failure to the client.
     */
    @Volatile
    var resolveRoot: ((String) -> HubCallerRoot)? = null

    /** Resolve a caller-relative path to a path in the selected target. */
    @Volatile
    var resolveCwd: ((HubCallerRoot, String, String) -> String)? = null

    /** Reveal [path], expressed in the caller's namespace. */
    @Volatile
    var onHere: ((String, HubCallerRoot) -> Unit)? = null

    /** A lease for the foreground run0 target; closing it restores its parent. */
    @Volatile
    var onTerminalTarget: ((String, String) -> Closeable)? = null

    /** Read the current settings for each request; never cache credentials. */
    @Volatile
    var aiConfigProvider: (() -> HubAiConfig)? = null
}

data class HubAiConfig(val baseUrl: String, val apiKey: String, val model: String)

data class HubCallerRoot(val containerName: String?, val hostPath: String)
