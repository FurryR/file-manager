package io.furryr.file.agent

/**
 * App-owned services the agent needs. The app installs every callback at
 * startup; the agent never reads Android preferences directly.
 */
object AgentEnvironment {
    /** Read the current provider settings for each request; never cache credentials. */
    @Volatile
    var aiConfigProvider: (() -> AgentAiConfig)? = null

    /** Persist the model and reasoning effort chosen by the agent. */
    @Volatile
    var aiSelectionSaver: ((String, String) -> Unit)? = null

    /** Load durable message JSON for a session. */
    @Volatile
    var sessionLoad: ((String) -> String?)? = null

    /** Store durable message JSON and return the effective title. */
    @Volatile
    var sessionSave: ((String, String) -> String?)? = null

    /** Read a persisted session's display title. */
    @Volatile
    var sessionTitle: ((String) -> String?)? = null

    /** User-level remote MCP servers configured in the app. */
    @Volatile
    var globalMcpServers: (() -> List<AgentMcpServer>)? = null

    /** Whether trusted workspace `.agent/mcp.json` files may be loaded. */
    @Volatile
    var workspaceMcpEnabled: (() -> Boolean)? = null

    /** List persisted conversation sessions. */
    @Volatile
    var sessionList: (() -> List<AgentSessionInfo>)? = null

    /** Delete a persisted conversation session. */
    @Volatile
    var sessionDelete: ((String) -> Unit)? = null

    /** Rename a persisted conversation session. */
    @Volatile
    var sessionRename: ((String, String) -> Unit)? = null

    /** Apply an AI-generated title unless the session already has one. */
    @Volatile
    var sessionAutoTitle: ((String, String) -> String?)? = null

    /** Active session id for a terminal. */
    @Volatile
    var activeSession: ((String) -> String?)? = null

    /** Persist the active session id for a terminal. */
    @Volatile
    var activeSessionSave: ((String, String) -> Unit)? = null
}

data class AgentAiConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val effort: String,
    val provider: String,
    val titleModel: String = "",
)

data class AgentSessionInfo(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val messageCount: Int,
)

/** One configured remote (Streamable HTTP) MCP server. */
data class AgentMcpServer(
    val name: String,
    val url: String = "",
    val headers: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
    val source: String = "global",
)
