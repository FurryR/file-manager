package io.furryr.file.agent

import io.furryr.file.daemon.DaemonTarget
import io.furryr.file.proto.agent.AgentApprovalRequest
import io.furryr.file.proto.agent.AgentEvent
import io.furryr.file.proto.agent.AgentFinished
import io.furryr.file.proto.agent.AgentMcpCall
import io.furryr.file.proto.agent.AgentToolNotice
import io.furryr.file.proto.agent.AgentUsage
import io.furryr.file.proto.hub.ChatEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** A live agent conversation attached to one hub connection. */
class AgentSession(
    val sessionId: String,
    val terminalId: String,
    val target: DaemonTarget,
    val targetName: String,
    val cwd: String,
    var model: String,
    var effort: String,
    private var emit: suspend (AgentEvent) -> Unit,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val runLock = Mutex()
    private val pendingMcp = ConcurrentHashMap<Long, CompletableDeferred<ToolResult>>()
    private val pendingApproval = ConcurrentHashMap<Long, CompletableDeferred<Boolean>>()
    private val nextMcp = AtomicLong(1)
    private val nextApproval = AtomicLong(1)
    private var messages = JSONArray()
    private var job: Job? = null
    private var mcpDefinitions = JSONArray()

    init {
        val stored = runCatching { AgentEnvironment.sessionLoad?.invoke(sessionId) }.getOrNull()
        if (!stored.isNullOrBlank()) {
            messages = runCatching { JSONArray(stored) }.getOrElse { JSONArray() }
            repairDanglingToolCalls(messages)
        }
    }

    /** Register the MCP host's tool definitions once it finishes initialization. */
    fun setEmitter(sink: suspend (AgentEvent) -> Unit) {
        emit = sink
    }

    fun setMcpTools(definitionsJson: String) {
        mcpDefinitions = runCatching { JSONArray(definitionsJson) }.getOrElse { JSONArray() }
    }

    fun startPrompt(text: String): Job {
        job?.cancel()
        val next = scope.launch {
            runLock.withLock { run(text) }
        }
        job = next
        return next
    }

    /** Replace the conversation with a model-written handoff summary. */
    suspend fun compact(model: String, effort: String): AgentOutcome =
        runLock.withLock { compactLocked(model, effort) }

    private suspend fun compactLocked(model: String, effort: String): AgentOutcome {
        if (messages.length() == 0) error("nothing to compact")
        val transcript = transcriptText()
        val summary = AiService.complete(
            model = model,
            effort = effort,
            system = "Compress the conversation below into a concise handoff summary. Keep " +
                "decisions, constraints, file paths, code changes, tool results and open " +
                "questions. Reply with only the summary.",
            history = JSONArray().put(
                JSONObject().put("role", "user").put("content", transcript),
            ),
            toolsJson = "[]",
            emit = {},
        )?.content?.trim().orEmpty()
        if (summary.isEmpty()) error("the model returned an empty summary")
        val compacted = JSONObject()
            .put("role", "user")
            .put("content", "Summary of the earlier conversation (compacted):\n$summary")
        messages = JSONArray().put(compacted)
        persist()
        emit(
            AgentEvent.newBuilder().setFinished(
                AgentFinished.newBuilder()
                    .setMessagesJson(messages.toString())
                    .setAnswer(summary)
                    .build(),
            ).build(),
        )
        emit(AgentEvent.newBuilder().setDone(true).build())
        return AgentOutcome(messages.toString(), summary)
    }

    private fun transcriptText(): String = buildString {
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            val role = message.optString("role")
            append(
                when (role) {
                    "user" -> "User:\n"
                    "assistant" -> "Assistant:\n"
                    "tool" -> "Tool result:\n"
                    else -> "$role:\n"
                },
            )
            val content = message.optString("content")
            if (content.isNotBlank()) append(content.trimEnd()).append('\n')
            val reasoning = message.optString("reasoning_content")
            if (reasoning.isNotBlank()) {
                append("[reasoning]\n").append(reasoning.trimEnd()).append('\n')
            }
            val calls = message.optJSONArray("tool_calls")
            if (calls != null) {
                for (callIndex in 0 until calls.length()) {
                    val call = calls.optJSONObject(callIndex) ?: continue
                    val function = call.optJSONObject("function") ?: continue
                    append("[tool call] ").append(function.optString("name"))
                        .append('(').append(function.optString("arguments")).append(")\n")
                }
            }
            val id = message.optString("tool_call_id")
            if (id.isNotBlank()) append("[tool call id: ").append(id).append("]\n")
            append('\n')
        }
    }.trimEnd()

    fun cancel() {
        job?.cancel()
        pendingApproval.values.forEach { it.complete(false) }
        pendingApproval.clear()
        pendingMcp.values.forEach { it.complete(ToolResult(false, "agent cancelled")) }
        pendingMcp.clear()
    }

    suspend fun cancelAndWait() {
        val current = job ?: return
        current.cancelAndJoin()
        job = null
    }

    fun completeMcp(requestId: Long, ok: Boolean, content: String) {
        pendingMcp.remove(requestId)?.complete(ToolResult(ok, content))
    }

    fun resolveApproval(approvalId: Long, allow: Boolean) {
        pendingApproval.remove(approvalId)?.complete(allow)
    }

    private suspend fun run(prompt: String) {
        try {
            append(JSONObject().put("role", "user").put("content", prompt))
            persist()
            maybeTitle(prompt)
            while (true) {
                var toolCalls = JSONArray()
                var error: String? = null
                val result = AiService.complete(
                    model = model,
                    effort = effort,
                    system = SystemPrompt.build(target, targetName, cwd),
                    history = messages,
                    toolsJson = toolsJson(),
                ) { event -> mapChatEvent(event, onToolCalls = { toolCalls = it }, onError = { error = it }) }
                    ?: run {
                        throw IllegalStateException(error ?: "the model returned no reply")
                    }
                if (toolCalls.length() > 0) {
                    val assistant = JSONObject().put("role", "assistant")
                    if (result.content.isNotBlank()) assistant.put("content", result.content)
                    if (result.reasoning.isNotBlank()) assistant.put("reasoning_content", result.reasoning)
                    assistant.put("tool_calls", toolCalls)
                    append(assistant)
                    persist()
                    for (index in 0 until toolCalls.length()) {
                        val call = toolCalls.optJSONObject(index) ?: continue
                        val id = call.optString("id")
                        val function = call.optJSONObject("function") ?: continue
                        val name = function.optString("name")
                        val arguments = function.optString("arguments")
                        val info = AgentTools.info(name, arguments)
                        emit(
                            AgentEvent.newBuilder().setToolCall(
                                AgentToolNotice.newBuilder()
                                    .setCallId(id).setName(name)
                                    .setDetail(info.detail)
                                    .setDescription(info.description.orEmpty())
                                    .setCommand(info.command.orEmpty())
                                    .setArrow(info.arrow)
                                    .build(),
                            ).build(),
                        )
                        val resultTool = executeTool(id, name, arguments)
                        emit(AgentEvent.newBuilder().setToolResultOk(resultTool.ok).build())
                        append(
                            JSONObject()
                                .put("role", "tool")
                                .put("tool_call_id", id)
                                .put("content", resultTool.content),
                        )
                        persist()
                    }
                    continue
                }
                val answer = result.content.trim()
                if (answer.isEmpty()) error("the model returned an empty reply")
                append(
                    JSONObject().put("role", "assistant").put("content", answer).apply {
                        if (result.reasoning.isNotBlank()) put("reasoning_content", result.reasoning)
                    },
                )
                persist()
                emit(
                    AgentEvent.newBuilder().setFinished(
                        AgentFinished.newBuilder()
                            .setMessagesJson(messages.toString())
                            .setAnswer(answer)
                            .build(),
                    ).build(),
                )
                emit(AgentEvent.newBuilder().setDone(true).build())
                return
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            repairDanglingToolCalls(messages)
            persist()
            throw cancelled
        } catch (error: Exception) {
            repairDanglingToolCalls(messages)
            persist()
            emit(
                AgentEvent.newBuilder()
                    .setError(error.message ?: "agent run failed")
                    .build(),
            )
            emit(AgentEvent.newBuilder().setDone(true).build())
        }
    }

    private suspend fun executeTool(
        callId: String,
        name: String,
        arguments: String,
    ): ToolResult {
        val isBuiltin = name in BUILTINS
        val args = runCatching { JSONObject(arguments.ifBlank { "{}" }) }.getOrNull() ?: JSONObject()
        if (isBuiltin) {
            val reason = AgentPolicy.approvalReason(name, args, cwd)
            if (reason != null) {
                val allowed = requestApproval(callId, name, reason)
                if (!allowed) {
                    return ToolResult(false, "Tool call denied by the user.")
                }
            }
            return AgentTools.execute(
                name,
                arguments,
                ToolContext(target, targetName, cwd, terminalId, emit),
            )
        }
        val result = callMcp(name, arguments)
        return if (result.ok) result.copy(content = AgentTools.bound(result.content)) else result
    }

    private suspend fun requestApproval(callId: String, name: String, reason: String): Boolean {
        val id = nextApproval.getAndIncrement()
        val deferred = CompletableDeferred<Boolean>()
        pendingApproval[id] = deferred
        emit(
            AgentEvent.newBuilder().setApproval(
                AgentApprovalRequest.newBuilder()
                    .setApprovalId(id).setCallId(callId).setName(name).setReason(reason)
                    .build(),
            ).build(),
        )
        return try {
            withTimeoutOrNull(APPROVAL_TIMEOUT_MS) { deferred.await() } ?: false
        } finally {
            pendingApproval.remove(id)
        }
    }

    private suspend fun callMcp(name: String, arguments: String): ToolResult {
        val id = nextMcp.getAndIncrement()
        val deferred = CompletableDeferred<ToolResult>()
        pendingMcp[id] = deferred
        emit(
            AgentEvent.newBuilder().setMcpCall(
                AgentMcpCall.newBuilder()
                    .setRequestId(id).setName(name).setArguments(arguments).build(),
            ).build(),
        )
        return try {
            withTimeoutOrNull(MCP_TIMEOUT_MS) { deferred.await() }
                ?: ToolResult(false, "MCP call timed out")
        } finally {
            pendingMcp.remove(id)
        }
    }

    private fun toolsJson(): String {
        val array = JSONArray()
        for (index in 0 until AgentTools.definitions.length()) {
            AgentTools.definitions.opt(index)?.let(array::put)
        }
        for (index in 0 until mcpDefinitions.length()) {
            mcpDefinitions.opt(index)?.let(array::put)
        }
        return array.toString()
    }

    private suspend fun mapChatEvent(
        event: ChatEvent,
        onToolCalls: (JSONArray) -> Unit,
        onError: (String) -> Unit,
    ) {
        when {
            event.hasReasoningDelta() -> emit(
                AgentEvent.newBuilder().setReasoningDelta(event.reasoningDelta).build(),
            )
            event.hasContentDelta() -> emit(
                AgentEvent.newBuilder().setContentDelta(event.contentDelta).build(),
            )
            event.toolCalling -> emit(AgentEvent.newBuilder().setToolCalling(true).build())
            event.hasUsage() -> emit(
                AgentEvent.newBuilder().setUsage(
                    AgentUsage.newBuilder()
                        .setPromptTokens(event.usage.promptTokens)
                        .setCompletionTokens(event.usage.completionTokens)
                        .build(),
                ).build(),
            )
            event.hasToolCallsJson() -> runCatching { JSONArray(event.toolCallsJson) }
                .onSuccess(onToolCalls)
            event.hasError() -> onError(event.error)
        }
    }

    private fun append(message: JSONObject) {
        messages.put(message)
    }

    private fun persist() {
        runCatching { AgentEnvironment.sessionSave?.invoke(sessionId, messages.toString()) }
    }

    private fun maybeTitle(prompt: String) {
        if (messages.length() != 1) return
        val titleModel = model.ifBlank {
            runCatching { AgentEnvironment.aiConfigProvider?.invoke()?.model }.getOrNull().orEmpty()
        }
        scope.launch {
            val title = AiService.titleFor(titleModel, prompt) ?: return@launch
            AgentEnvironment.sessionAutoTitle?.invoke(sessionId, title)
        }
    }

    private fun repairDanglingToolCalls(messages: JSONArray) {
        var position = -1
        for (index in messages.length() - 1 downTo 0) {
            val message = messages.optJSONObject(index) ?: continue
            if (message.optString("role") == "assistant" && message.has("tool_calls")) {
                position = index
                break
            }
        }
        if (position < 0) return
        val assistant = messages.optJSONObject(position) ?: return
        val calls = assistant.optJSONArray("tool_calls") ?: return
        if (calls.length() == 0) return
        val answered = HashSet<String>()
        for (index in position + 1 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            if (message.optString("role") != "tool") return
            answered.add(message.optString("tool_call_id"))
        }
        for (index in 0 until calls.length()) {
            val call = calls.optJSONObject(index) ?: continue
            val id = call.optString("id")
            if (id in answered) continue
            messages.put(
                JSONObject()
                    .put("role", "tool")
                    .put("tool_call_id", id)
                    .put("content", "This call was cancelled before it ran."),
            )
        }
    }

    fun dispose() {
        job?.cancel()
        scope.cancel()
        pendingMcp.values.forEach { it.complete(ToolResult(false, "agent detached")) }
        pendingApproval.values.forEach { it.complete(false) }
        pendingMcp.clear()
        pendingApproval.clear()
    }

    companion object {
        private val BUILTINS = setOf("read", "write", "copy", "glob", "exec", "list_targets")
        private const val APPROVAL_TIMEOUT_MS = 120_000L
        private const val MCP_TIMEOUT_MS = 600_000L
    }
    data class AgentOutcome(val messagesJson: String, val answer: String)
}
