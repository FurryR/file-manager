package io.furryr.file.agent

/** A tool call within an AI block — represents an invoked tool/function. */
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: Map<String, Any?>
)

/** Role of the participant producing an AI block. */
enum class Role {
    USER,
    ASSISTANT,
    SYSTEM
}

/** Streaming / finalisation state of an AI block. */
enum class BlockStatus {
    STREAMING,
    COMPLETE,
    FAILED
}

/** Lifecycle state of a command block. */
enum class CommandStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED
}

/** Runtime execution mode for a command block. */
enum class ExecutionMode {
    APP_UID,
    CONTAINER,
    ROOT_SHELL,
    SHIZUKU
}

/**
 * Warp-style UI block model.
 *
 * Each subclass represents a distinct rendering unit in the agent chat timeline.
 */
sealed class Block {
    abstract val id: String
    abstract val timestamp: Long
}

/** AI-generated message with optional tool calls. */
data class AiBlock(
    val content: String,
    val role: Role,
    val toolCalls: List<ToolCall>?,
    val status: BlockStatus,
    override val id: String,
    override val timestamp: Long,
) : Block()

/** A shell command that was or will be executed. */
data class CommandBlock(
    val command: String,
    val cwd: String,
    val mode: ExecutionMode,
    val status: CommandStatus,
    val exitCode: Int?,
    val duration: Long?,
    override val id: String,
    override val timestamp: Long,
) : Block()

/** Captured output stream from a session (fullscreen or streaming). */
data class OutputBlock(
    val sessionId: String,
    val isFullscreen: Boolean,
    val byteOffset: Long,
    val byteLength: Long,
    override val id: String,
    override val timestamp: Long,
) : Block()

/** Terminal exit-status summary after a command completes. */
data class ExitStatusBlock(
    val exitCode: Int,
    val duration: Long,
    override val id: String,
    override val timestamp: Long,
) : Block()

/** Autocomplete / quick-action suggestions presented to the user. */
data class SuggestionBlock(
    val suggestions: List<String>,
    override val id: String,
    override val timestamp: Long,
) : Block()

/** User-facing confirmation prompt that invokes a callback with the user's decision. */
data class ConfirmBlock(
    val action: String,
    val description: String,
    val callback: (Boolean) -> Unit,
    override val id: String,
    override val timestamp: Long,
) : Block()
