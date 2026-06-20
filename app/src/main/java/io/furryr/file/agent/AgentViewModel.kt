package io.furryr.file.agent

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

// ═══════════════════════════════════════════════════════════════════════════════
// AgentViewModelImpl — concrete state controller for the agent screen
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Concrete implementation of [AgentViewModel] that connects all agent subsystems:
 * block modelling, terminal session lifecycle, AI streaming, tool routing, and
 * confirmation handling.
 *
 * ## Subsystem wiring
 *
 * ```
 * AgentInputBar ──onSendCommand──→ [execute] ──→ CommandBlock → OutputBlock → ExitStatusBlock
 * AgentInputBar ──onAskAi───────→ AIClient ──stream──→ AiBlock (live update)
 *                                          └──ToolCall──→ AgentToolRouter ──→ tool result block
 * AgentScreen   ──onConfirmAction──→ pending tool dispatch → result → AiBlock
 * ```
 *
 * State is backed by Compose [mutableStateListOf] / [mutableStateOf] for
 * automatic recomposition when the [AgentScreen] composable reads it.
 *
 * @param androidContext Application context for [SessionManager] and [AgentToolRouter].
 * @param aiClient       Optional LLM client; when `null`, AI features are disabled.
 */
class AgentViewModelImpl(
    private val androidContext: Context,
    private val aiClient: AIClient? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : AgentViewModel() {

    // ── Coroutine scope ───────────────────────────────────────────────────

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ═══════════════════════════════════════════════════════════════════════
    // Observable state
    // ═══════════════════════════════════════════════════════════════════════

    private val _blocks = mutableStateListOf<Block>()
    override val blocks: List<Block> get() = _blocks

    private var _terminalSession by mutableStateOf<TerminalSession?>(null)
    override val terminalSession: TerminalSession? get() = _terminalSession

    private var _currentMode by mutableStateOf(ExecutionMode.APP_UID)
    override val currentMode: ExecutionMode get() = _currentMode

    /** Whether a command or AI request is currently in flight. */
    var isProcessing by mutableStateOf(false)
        private set

    /** Active container name when [currentMode] is [ExecutionMode.CONTAINER]. */
    var currentContainer by mutableStateOf<String?>(null)
        private set

    // ═══════════════════════════════════════════════════════════════════════
    // Internal state
    // ═══════════════════════════════════════════════════════════════════════

    /** Accumulated conversation turns sent to the LLM. */
    private val conversationHistory = mutableListOf<ChatMessage>()

    /** Active terminal session id from [SessionManager], if any. */
    private var activeSessionId: String? = null

    /** Pending tool call awaiting user confirmation. */
    private var pendingToolCall: ToolCallData? = null

    /** [AgentContext] captured at the moment the pending tool call was made. */
    private var pendingAgentContext: AgentContext? = null

    /** Block id for the AiBlock that initiated the pending tool call. */
    private var pendingToolBlockId: String? = null

    // ═══════════════════════════════════════════════════════════════════════
    // Block management helpers
    // ═══════════════════════════════════════════════════════════════════════

    /** Append a block to the timeline and trigger Compose recomposition. */
    private fun addBlock(block: Block) {
        _blocks.add(block)
    }

    /**
     * Locate a block by [id] and replace it with the result of [transform].
     * No-op when the id is not found.
     */
    private fun updateBlock(id: String, transform: (Block) -> Block) {
        val idx = _blocks.indexOfFirst { it.id == id }
        if (idx >= 0) {
            _blocks[idx] = transform(_blocks[idx])
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Command flow
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Execute a shell command and render the result as a sequence of blocks:
     * [CommandBlock] → [OutputBlock] → [ExitStatusBlock].
     *
     * ## Flow
     * 1. Create [CommandBlock] with [CommandStatus.RUNNING].
     * 2. Dispatch execution to [AgentToolRouter.executeShellCommand].
     * 3. Capture output in an [OutputBlock].
     * 4. Append [ExitStatusBlock] with exit code and wall-time duration.
     * 5. Update [CommandBlock.status] to SUCCESS or FAILED.
     */
    override fun onSendCommand(command: String, mode: ExecutionMode) {
        val cmdId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val startTime = now

        val cmdBlock = CommandBlock(
            command = command,
            cwd = "",
            mode = mode,
            status = CommandStatus.RUNNING,
            exitCode = null,
            duration = null,
            id = cmdId,
            timestamp = now
        )
        addBlock(cmdBlock)
        isProcessing = true

        scope.launch {
            try {
                val agentCtx = AgentContext(
                    executionMode = mode,
                    currentContainer = currentContainer
                )

                val result = withContext(ioDispatcher) {
                    AgentToolRouter.executeShellCommand(
                        command = command,
                        cwd = null,
                        timeoutMs = DEFAULT_COMMAND_TIMEOUT_MS,
                        agentContext = agentCtx
                    )
                }

                // ── Output block ─────────────────────────────────────────
                val outputId = UUID.randomUUID().toString()
                val outputText = result.data ?: ""
                addBlock(
                    OutputBlock(
                        sessionId = activeSessionId ?: outputId,
                        isFullscreen = false,
                        byteOffset = 0L,
                        byteLength = outputText.length.toLong(),
                        id = outputId,
                        timestamp = System.currentTimeMillis()
                    )
                )

                // ── Parse exit code ──────────────────────────────────────
                var parsedExitCode: Int
                var parsedOutput: String
                try {
                    val json = JSONObject(result.data ?: "{}")
                    parsedExitCode = json.optInt("exit_code", if (result.success) 0 else 1)
                    parsedOutput = json.optString("output", "")
                } catch (_: Exception) {
                    parsedExitCode = if (result.success) 0 else 1
                    parsedOutput = ""
                }

                val duration = System.currentTimeMillis() - startTime

                // ── Update command block ─────────────────────────────────
                updateBlock(cmdId) { block ->
                    (block as CommandBlock).copy(
                        status = if (result.success) CommandStatus.SUCCESS else CommandStatus.FAILED,
                        exitCode = parsedExitCode,
                        duration = duration
                    )
                }

                // ── Exit status block ────────────────────────────────────
                addBlock(
                    ExitStatusBlock(
                        exitCode = parsedExitCode,
                        duration = duration,
                        id = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis()
                    )
                )
            } catch (e: Exception) {
                // ── Failure path ─────────────────────────────────────────
                val duration = System.currentTimeMillis() - startTime
                updateBlock(cmdId) { block ->
                    (block as CommandBlock).copy(
                        status = CommandStatus.FAILED,
                        exitCode = -1,
                        duration = duration
                    )
                }
                addBlock(
                    ExitStatusBlock(
                        exitCode = -1,
                        duration = duration,
                        id = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis()
                    )
                )
                addBlock(
                    AiBlock(
                        content = "Command error: ${e.message}",
                        role = Role.SYSTEM,
                        toolCalls = null,
                        status = BlockStatus.FAILED,
                        id = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis()
                    )
                )
            } finally {
                isProcessing = false
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // AI flow
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Send a natural-language prompt to the LLM and stream the response into
     * an [AiBlock] with live content updates.
     *
     * ## Flow
     * 1. Append user [AiBlock] (role=USER, status=COMPLETE).
     * 2. Append assistant [AiBlock] (content="", status=STREAMING).
     * 3. Push user message into [conversationHistory].
     * 4. Call [AIClient.sendMessage] with tool definitions.
     * 5. On [AiEvent.Content] → append text to the streaming block in-place.
     * 6. On [AiEvent.ToolCall] → collect [ToolCallData] for post-stream execution.
     * 7. On [AiEvent.Done] → finalise the block, execute collected tool calls.
     * 8. On [AiEvent.Error] → mark the block FAILED with the error message.
     *
     * When [aiClient] is `null` a system error block is appended instead.
     */
    override fun onAskAi(prompt: String) {
        if (prompt.isBlank()) return

        if (aiClient == null) {
            addBlock(
                AiBlock(
                    content = "AI client is not configured.\nSet an API key in Settings to enable AI features.",
                    role = Role.SYSTEM,
                    toolCalls = null,
                    status = BlockStatus.FAILED,
                    id = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis()
                )
            )
            return
        }

        // ── 1. User block ────────────────────────────────────────────────
        addBlock(
            AiBlock(
                content = prompt,
                role = Role.USER,
                toolCalls = null,
                status = BlockStatus.COMPLETE,
                id = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis()
            )
        )

        // ── 2. Streaming assistant block ──────────────────────────────────
        val aiBlockId = UUID.randomUUID().toString()
        addBlock(
            AiBlock(
                content = "",
                role = Role.ASSISTANT,
                toolCalls = null,
                status = BlockStatus.STREAMING,
                id = aiBlockId,
                timestamp = System.currentTimeMillis()
            )
        )

        // ── 3. Push user message to history ───────────────────────────────
        conversationHistory.add(ChatMessage(role = "user", content = prompt))
        isProcessing = true

        scope.launch {
            try {
                // Accumulated tool calls extracted from the streaming response.
                val extractedToolCalls = mutableListOf<ToolCallData>()
                var hasError = false

                val sendResult = aiClient.sendMessage(
                    messages = conversationHistory.toList(),
                    tools = ToolDefinitions.allToolDefinitions()
                ) { event ->
                    when (event) {
                        is AiEvent.Content -> {
                            updateBlock(aiBlockId) { block ->
                                val b = block as AiBlock
                                b.copy(content = b.content + event.text)
                            }
                        }
                        is AiEvent.ToolCall -> {
                            extractedToolCalls.add(
                                ToolCallData(
                                    id = event.id,
                                    name = event.name,
                                    arguments = event.arguments
                                )
                            )
                        }
                        is AiEvent.Error -> {
                            hasError = true
                            updateBlock(aiBlockId) { block ->
                                val b = block as AiBlock
                                b.copy(
                                    content = b.content + "\n\n[Error: ${event.message}]",
                                    status = BlockStatus.FAILED
                                )
                            }
                        }
                        AiEvent.Done -> {
                            if (!hasError) {
                                updateBlock(aiBlockId) { block ->
                                    val b = block as AiBlock
                                    b.copy(
                                        status = BlockStatus.COMPLETE,
                                        toolCalls = extractedToolCalls.toBlockToolCalls().ifEmpty { null }
                                    )
                                }
                            }
                        }
                    }
                }

                // ── 4. Record assistant message in history ────────────────
                if (!hasError) {
                    val finalContent = (_blocks.find { it.id == aiBlockId } as? AiBlock)?.content ?: ""
                    conversationHistory.add(
                        ChatMessage(
                            role = "assistant",
                            content = finalContent,
                            toolCalls = extractedToolCalls.ifEmpty { null }
                        )
                    )

                    // ── 5. Execute collected tool calls ────────────────────
                    for (tc in extractedToolCalls) {
                        executeSingleToolCall(tc)
                    }
                }
            } catch (e: Exception) {
                updateBlock(aiBlockId) { block ->
                    val b = block as AiBlock
                    b.copy(
                        content = "${b.content}\n\n[Transport error: ${e.message}]",
                        status = BlockStatus.FAILED
                    )
                }
            } finally {
                isProcessing = false
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Tool execution
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Execute a single tool call via [AgentToolRouter] and append the result
     * to the timeline.
     *
     * When the tool result requires confirmation (based on [AgentContext.confirmationPolicy])
     * the tool call is parked in [pendingToolCall] and a [ConfirmBlock] is rendered.
     * The actual execution happens later in [onConfirmAction].
     */
    private suspend fun executeSingleToolCall(tc: ToolCallData) {
        val toolBlockId = UUID.randomUUID().toString()

        // ── Tool-running block ────────────────────────────────────────────
        addBlock(
            AiBlock(
                content = "",
                role = Role.SYSTEM,
                toolCalls = null,
                status = BlockStatus.STREAMING,
                id = toolBlockId,
                timestamp = System.currentTimeMillis()
            )
        )

        try {
            val agentCtx = AgentContext(
                executionMode = currentMode,
                currentContainer = currentContainer
            )

            val result = AgentToolRouter.execute(tc, agentCtx)

            if (result.requiresConfirmation) {
                // ── Park for confirmation ─────────────────────────────────
                pendingToolCall = tc
                pendingAgentContext = agentCtx
                pendingToolBlockId = toolBlockId

                updateBlock(toolBlockId) { block ->
                    (block as AiBlock).copy(
                        content = "Tool `$tc.name` requires confirmation.",
                        status = BlockStatus.COMPLETE
                    )
                }

                addBlock(
                    ConfirmBlock(
                        action = tc.name,
                        description = result.data ?: "Execute `$tc.name`?",
                        callback = { /* handled by onConfirmAction */ },
                        id = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis()
                    )
                )
            } else {
                // ── Execute immediately ────────────────────────────────────
                finaliseToolResult(tc, result, toolBlockId)
            }
        } catch (e: Exception) {
            updateBlock(toolBlockId) { block ->
                (block as AiBlock).copy(
                    content = "Tool `$tc.name` failed: ${e.message}",
                    status = BlockStatus.FAILED
                )
            }
        }
    }

    /**
     * Render the tool result into the target block and feed the outcome
     * back to the LLM for a follow-up response.
     */
    private fun finaliseToolResult(
        tc: ToolCallData,
        result: ToolResult,
        blockId: String
    ) {
        val resultText = if (result.success) {
            result.data ?: "(empty result)"
        } else {
            "Error: ${result.error ?: "unknown"}"
        }

        updateBlock(blockId) { block ->
            (block as AiBlock).copy(
                content = "`$tc.name` → $resultText",
                status = if (result.success) BlockStatus.COMPLETE else BlockStatus.FAILED
            )
        }

        // Feed tool result into conversation history
        conversationHistory.add(
            ChatMessage(role = "tool", content = resultText)
        )

        // Continue the AI conversation so the LLM can incorporate the result
        continueAiConversation()
    }

    /**
     * Send the updated [conversationHistory] (now including tool results)
     * back to the LLM to produce a follow-up assistant message.
     */
    private fun continueAiConversation() {
        if (aiClient == null) return

        val followUpBlockId = UUID.randomUUID().toString()
        addBlock(
            AiBlock(
                content = "",
                role = Role.ASSISTANT,
                toolCalls = null,
                status = BlockStatus.STREAMING,
                id = followUpBlockId,
                timestamp = System.currentTimeMillis()
            )
        )

        scope.launch {
            try {
                val followUpToolCalls = mutableListOf<ToolCallData>()
                var hasError = false

                aiClient.sendMessage(
                    messages = conversationHistory.toList(),
                    tools = ToolDefinitions.allToolDefinitions()
                ) { event ->
                    when (event) {
                        is AiEvent.Content -> {
                            updateBlock(followUpBlockId) { block ->
                                val b = block as AiBlock
                                b.copy(content = b.content + event.text)
                            }
                        }
                        is AiEvent.ToolCall -> {
                            followUpToolCalls.add(
                                ToolCallData(
                                    id = event.id,
                                    name = event.name,
                                    arguments = event.arguments
                                )
                            )
                        }
                        is AiEvent.Error -> {
                            hasError = true
                            updateBlock(followUpBlockId) { block ->
                                val b = block as AiBlock
                                b.copy(
                                    content = b.content + "\n\n[Error: ${event.message}]",
                                    status = BlockStatus.FAILED
                                )
                            }
                        }
                        AiEvent.Done -> {
                            if (!hasError) {
                                updateBlock(followUpBlockId) { block ->
                                    val b = block as AiBlock
                                    b.copy(
                                        status = BlockStatus.COMPLETE,
                                        toolCalls = followUpToolCalls.toBlockToolCalls().ifEmpty { null }
                                    )
                                }
                            }
                        }
                    }
                }

                if (!hasError) {
                    val followUpContent = (_blocks.find { it.id == followUpBlockId } as? AiBlock)?.content ?: ""
                    conversationHistory.add(
                        ChatMessage(
                            role = "assistant",
                            content = followUpContent,
                            toolCalls = followUpToolCalls.ifEmpty { null }
                        )
                    )

                    // Recursively execute any follow-up tool calls (with depth
                    // bounded by the LLM's own stop condition — max 5 rounds).
                    val toolCallRounds = conversationHistory.count { it.role == "tool" }
                    if (toolCallRounds < 5) {
                        for (tc in followUpToolCalls) {
                            executeSingleToolCall(tc)
                        }
                    }
                }
            } catch (e: Exception) {
                updateBlock(followUpBlockId) { block ->
                    val b = block as AiBlock
                    b.copy(
                        content = "${b.content}\n\n[Transport error: ${e.message}]",
                        status = BlockStatus.FAILED
                    )
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Suggestion handling
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Record that the user selected a suggestion.
     *
     * The actual text insertion into the input bar is handled by the UI
     * composable; this method provides a hook for logging or analytics.
     */
    override fun onInsertSuggestion(text: String) {
        // Suggestion insertion is handled by the AgentInputBar composable;
        // this callback exists for potential server-side logging or analytics.
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Confirmation flow
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Handle a confirm/deny decision on a [ConfirmBlock].
     *
     * - **Confirmed**: executes the parked [pendingToolCall] via [AgentToolRouter]
     *   and feeds the result back to the LLM.
     * - **Denied**: appends a system [AiBlock] recording the denial and clears
     *   pending state.
     */
    override fun onConfirmAction(confirmed: Boolean, block: ConfirmBlock) {
        if (confirmed && pendingToolCall != null && pendingAgentContext != null) {
            val tc = pendingToolCall!!
            val agentCtx = pendingAgentContext!!

            // Clear pending state before execution so a second confirm is harmless.
            pendingToolCall = null
            pendingAgentContext = null
            val toolBlockId = pendingToolBlockId
            pendingToolBlockId = null

            scope.launch {
                try {
                    val result = AgentToolRouter.execute(tc, agentCtx)
                    if (toolBlockId != null) {
                        finaliseToolResult(tc, result, toolBlockId)
                    }
                } catch (e: Exception) {
                    if (toolBlockId != null) {
                        updateBlock(toolBlockId) { block ->
                            (block as AiBlock).copy(
                                content = "Tool `$tc.name` failed after confirmation: ${e.message}",
                                status = BlockStatus.FAILED
                            )
                        }
                    }
                }
            }
        } else {
            // ── Denied ────────────────────────────────────────────────────
            addBlock(
                AiBlock(
                    content = "Action denied: $block.action",
                    role = Role.SYSTEM,
                    toolCalls = null,
                    status = BlockStatus.COMPLETE,
                    id = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis()
                )
            )
            // Discard pending state
            pendingToolCall = null
            pendingAgentContext = null
            pendingToolBlockId = null
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Mode switching
    // ═══════════════════════════════════════════════════════════════════════

     /**
      * Switch the active [ExecutionMode] and append a system annotation block.
      *
      * When switching to [ExecutionMode.CONTAINER] and no container is selected,
      * picks the first available container from [ContainerManager.listContainers].
      * Falls back to `null` when no containers exist.
      */
    override fun onModeChanged(mode: ExecutionMode) {
        _currentMode = mode

        currentContainer = when (mode) {
            ExecutionMode.CONTAINER -> currentContainer ?: ContainerManager.listContainers(androidContext)
                .getOrNull()
                ?.firstOrNull()
                ?.name
            else -> null
        }

        addBlock(
            AiBlock(
                content = "Context: $mode${currentContainer?.let { " ($it)" } ?: ""}",
                role = Role.SYSTEM,
                toolCalls = null,
                status = BlockStatus.COMPLETE,
                id = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis()
            )
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Session lifecycle (testing hook)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Ensure a terminal session exists for the current [ExecutionMode].
     *
     * Creates a session via [SessionManager.createSession] when no active
     * session is present; returns the existing session otherwise.
     */
    suspend fun ensureTerminalSession(): Result<TerminalSession> {
        val existing = activeSessionId?.let { SessionManager.getSession(it) }
        if (existing != null) return Result.success(existing)

        val sessionType = when (currentMode) {
            ExecutionMode.APP_UID -> SessionManager.SessionType.AppShell()
            ExecutionMode.ROOT_SHELL -> SessionManager.SessionType.RootPty(useShizuku = false)
            ExecutionMode.SHIZUKU -> SessionManager.SessionType.RootPty(useShizuku = true)
            ExecutionMode.CONTAINER -> {
                val containerName = currentContainer ?: ContainerManager.listContainers(androidContext)
                    .getOrNull()
                    ?.firstOrNull()
                    ?.name
                    ?: return Result.failure(
                        IllegalStateException("No container selected and no containers available")
                    )
                SessionManager.SessionType.Container(containerName = containerName)
            }
        }

        return SessionManager.createSession(sessionType, androidContext).onSuccess { session ->
            activeSessionId = null // SessionManager assigns its own id
            _terminalSession = session
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════════════════════════════════════════

    /** Clear all blocks, conversation history, and pending state. */
    fun clearBlocks() {
        _blocks.clear()
        conversationHistory.clear()
        pendingToolCall = null
        pendingAgentContext = null
        pendingToolBlockId = null
    }

    /**
     * Tear down all resources: cancel coroutines, destroy terminal session,
     * and clear state.  Call when the agent screen is permanently dismissed.
     */
    fun destroy() {
        scope.cancel()
        activeSessionId?.let { runCatching { SessionManager.destroySession(it) } }
        _blocks.clear()
        conversationHistory.clear()
        pendingToolCall = null
        pendingAgentContext = null
        pendingToolBlockId = null
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Private utilities
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Convert a list of [ToolCallData] to [BlockModels.ToolCall] for the
     * [AiBlock.toolCalls] field.  JSON arguments are parsed into
     * [Map<String, Any?>].
     */
    private fun List<ToolCallData>.toBlockToolCalls(): List<ToolCall> = mapNotNull { tc ->
        val argsMap: Map<String, Any?> = try {
            val json = JSONObject(tc.arguments)
            json.keys().asSequence().associateWith { key -> json.get(key) }
        } catch (_: Exception) {
            emptyMap()
        }
        ToolCall(id = tc.id, name = tc.name, arguments = argsMap)
    }

    companion object {
        /** Default timeout for command execution (30 seconds). */
        private const val DEFAULT_COMMAND_TIMEOUT_MS = 30_000
    }
}
