package io.furryr.file.daemon

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

    /**
     * Every container id known to the app (`container-<name>`), including
     * stopped ones. The hub merges this with live daemons for ListTargets.
     */
    @Volatile
    var containerIds: (() -> List<String>)? = null

    /**
     * Every user-visible file location (FTP, SFTP, SAF, AList, ...) exposed by
     * the app. File tools address these by their canonical root URI.
     */
    @Volatile
    var locationTargets: (() -> List<HubLocation>)? = null

    /** A lease for the foreground run0 target; closing it restores its parent. */
    @Volatile
    var onTerminalTarget: ((String, String) -> Closeable)? = null
}

data class HubCallerRoot(val containerName: String?, val hostPath: String)

data class HubLocation(
    val kind: String,
    val label: String,
    val root: String,
)
