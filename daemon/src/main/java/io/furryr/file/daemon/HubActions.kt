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

    /** Read the current settings for each request; never cache credentials. */
    @Volatile
    var aiConfigProvider: (() -> HubAiConfig)? = null

    /** Persist the model and reasoning effort chosen by an agent. */
    @Volatile
    var aiSelectionSaver: ((String, String) -> Unit)? = null

    /** Load the durable message JSON for a chat session. */
    @Volatile
    var sessionLoad: ((String) -> String?)? = null

    /** Store the durable message JSON for a chat session and return its title. */
    @Volatile
    var sessionSave: ((String, String) -> String?)? = null

    /** Read a persisted session's display title. */
    @Volatile
    var sessionTitle: ((String) -> String?)? = null

    /** User-level MCP servers configured in the app. */
    @Volatile
    var globalMcpServers: (() -> List<HubMcpServer>)? = null

    /** Whether trusted workspace `.agent/mcp.json` files may be loaded. */
    @Volatile
    var workspaceMcpEnabled: (() -> Boolean)? = null

    /** List persisted conversation sessions. */
    @Volatile
    var sessionList: (() -> List<HubSessionInfo>)? = null

    /** Delete a persisted conversation session. */
    @Volatile
    var sessionDelete: ((String) -> Unit)? = null

    /** Rename a persisted conversation session. */
    @Volatile
    var sessionRename: ((String, String) -> Unit)? = null

    /**
     * Apply an AI-generated title unless the session already has one, returning
     * the effective title (which may be an existing manual title).
     */
    @Volatile
    var sessionAutoTitle: ((String, String) -> String?)? = null

    /** Active session id for a terminal. */
    @Volatile
    var activeSession: ((String) -> String?)? = null

    /** Persist the active session id for a terminal. */
    @Volatile
    var activeSessionSave: ((String, String) -> Unit)? = null
}

data class HubSessionInfo(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val messageCount: Int,
)

data class HubMcpServer(
    val name: String,
    val url: String = "",
    val headers: Map<String, String> = emptyMap(),
    val command: String = "",
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val cwd: String = "",
    val enabled: Boolean = true,
    val source: String = "global",
    val transport: String = "",
)

data class HubAiConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val effort: String,
    val provider: String,
    val titleModel: String = "",
)

data class HubCallerRoot(val containerName: String?, val hostPath: String)

data class HubLocation(
    val kind: String,
    val label: String,
    val root: String,
)
