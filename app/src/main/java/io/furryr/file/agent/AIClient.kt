package io.furryr.file.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

// ──────────────────────────────────────────────────────────────────────────────
// Public types
// ──────────────────────────────────────────────────────────────────────────────

/** Events emitted during a streaming LLM response. */
sealed class AiEvent {
    /** A text token from the assistant. */
    data class Content(val text: String) : AiEvent()

    /** A completed tool/function call extracted from the response. */
    data class ToolCall(val id: String, val name: String, val arguments: String) : AiEvent()

    /** A non-fatal or fatal error encountered during streaming. */
    data class Error(val message: String) : AiEvent()

    /** The stream has ended (may be emitted after a non-fatal error). */
    object Done : AiEvent()
}

/** Abstract client for an LLM provider. */
interface AIClient {
    /** Provider identifier — "openai", "anthropic", or "gemini". */
    val provider: String

    /**
     * Send a conversation turn to the LLM with optional tool definitions and stream
     * the response via [onEvent].
     *
     * @param messages  The conversation history.
     * @param tools     Tool/function definitions in OpenAI-compatible format.
     * @param onEvent   Callback invoked on each streaming event (content, tool call, error).
     * @return          [Result.success] wrapping the full accumulated response text,
     *                  or [Result.failure] on a transport-level error.
     */
    suspend fun sendMessage(
        messages: List<ChatMessage>,
        tools: List<Map<String, Any?>> = ToolDefinitions.allToolDefinitions(),
        onEvent: (AiEvent) -> Unit
    ): Result<String>
}

/** A single message in the conversation. */
data class ChatMessage(
    /** "user", "assistant", "system", or "tool". */
    val role: String,
    /** Message content (may be empty for assistant tool-call-only messages). */
    val content: String,
    /** Tool calls made by the assistant in this message. */
    val toolCalls: List<ToolCallData>? = null
)

/** A tool/function call attached to an assistant message. */
data class ToolCallData(
    val id: String,
    val name: String,
    /** JSON-encoded arguments string. */
    val arguments: String
)

// ──────────────────────────────────────────────────────────────────────────────
// Factory
// ──────────────────────────────────────────────────────────────────────────────

/** Create an [AIClient] for the OpenAI chat-completions API. */
fun openAiClient(apiKey: String): AIClient = OpenAIClient(apiKey)

// ──────────────────────────────────────────────────────────────────────────────
// OpenAI implementation
// ──────────────────────────────────────────────────────────────────────────────

private class OpenAIClient(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val baseUrl: String = DEFAULT_BASE_URL,
) : AIClient {

    override val provider = "openai"

    companion object {
        private const val DEFAULT_BASE_URL = "https://api.openai.com/v1/chat/completions"
        private const val DEFAULT_MODEL = "gpt-4o"
        private const val TIMEOUT_MS = 30_000
    }

    // ── Public API ────────────────────────────────────────────────────────

    override suspend fun sendMessage(
        messages: List<ChatMessage>,
        tools: List<Map<String, Any?>>,
        onEvent: (AiEvent) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = openConnection()
            writeRequestBody(connection, messages, tools)

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: ""
                val errorMsg = "HTTP $responseCode: $errorBody"
                onEvent(AiEvent.Error(errorMsg))
                return@withContext Result.failure(RuntimeException(errorMsg))
            }

            val result = readSseStream(connection, onEvent)
            Result.success(result)
        } catch (e: SocketTimeoutException) {
            val msg = "Request timed out after ${TIMEOUT_MS}ms"
            onEvent(AiEvent.Error(msg))
            Result.failure(e)
        } catch (e: Exception) {
            val msg = e.message ?: "Unknown error"
            onEvent(AiEvent.Error(msg))
            Result.failure(e)
        } finally {
            connection?.disconnect()
        }
    }

    // ── Connection setup ──────────────────────────────────────────────────

    private fun openConnection(): HttpURLConnection {
        return (URL(baseUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            doInput = true
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
        }
    }

    private fun writeRequestBody(
        connection: HttpURLConnection,
        messages: List<ChatMessage>,
        tools: List<Map<String, Any?>>,
    ) {
        val body = buildRequestBodyJson(messages, tools)
        OutputStreamWriter(connection.outputStream).use { writer ->
            writer.write(body.toString())
            writer.flush()
        }
    }

    private fun buildRequestBodyJson(
        messages: List<ChatMessage>,
        tools: List<Map<String, Any?>>,
    ): JSONObject {
        return JSONObject().apply {
            put("model", model)
            put("stream", true)

            val messagesArray = JSONArray()
            for (msg in messages) {
                messagesArray.put(chatMessageToJson(msg))
            }
            put("messages", messagesArray)

            if (tools.isNotEmpty()) {
                val toolsArray = JSONArray()
                for (tool in tools) {
                    toolsArray.put(JSONObject(tool))
                }
                put("tools", toolsArray)
            }
        }
    }

    // ── SSE parsing ───────────────────────────────────────────────────────

    /**
     * Read the SSE stream line-by-line, dispatching [AiEvent]s via [onEvent].
     * Returns the accumulated full response text.
     */
    private fun readSseStream(
        connection: HttpURLConnection,
        onEvent: (AiEvent) -> Unit,
    ): String {
        val contentBuilder = StringBuilder()
        // Tool calls are assembled across multiple chunks keyed by index.
        val toolBuilders = mutableMapOf<Int, ToolCallAccumulator>()

        BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val currentLine = line!!
                if (currentLine.isEmpty()) continue
                if (!currentLine.startsWith("data: ")) continue

                val data = currentLine.removePrefix("data: ")
                if (data == "[DONE]") break

                try {
                    val json = JSONObject(data)
                    val choices = json.optJSONArray("choices") ?: continue
                    for (i in 0 until choices.length()) {
                        val choice = choices.getJSONObject(i)
                        val delta = choice.optJSONObject("delta") ?: continue

                        // ── Content token ──
                        val content = delta.optString("content", "")
                        if (content.isNotEmpty()) {
                            contentBuilder.append(content)
                            onEvent(AiEvent.Content(content))
                        }

                        // ── Tool-call fragments ──
                        val toolCallsArr = delta.optJSONArray("tool_calls") ?: continue
                        for (j in 0 until toolCallsArr.length()) {
                            val tc = toolCallsArr.getJSONObject(j)
                            val index = tc.getInt("index")
                            val acc = toolBuilders.getOrPut(index) { ToolCallAccumulator() }
                            acc.feed(tc, onEvent)
                        }
                    }
                } catch (e: Exception) {
                    onEvent(AiEvent.Error("SSE parse error: ${e.message}"))
                }
            }
        }

        // Emit any outstanding completed tool calls
        for ((_, acc) in toolBuilders) {
            acc.emitIfComplete(onEvent)
        }

        onEvent(AiEvent.Done)
        return contentBuilder.toString()
    }

    // ── ChatMessage → JSON ────────────────────────────────────────────────

    private fun chatMessageToJson(msg: ChatMessage): JSONObject {
        return JSONObject().apply {
            put("role", msg.role)
            when {
                // Assistant message with tool calls
                msg.role == "assistant" && msg.toolCalls != null && msg.toolCalls.isNotEmpty() -> {
                    val toolCallsArray = JSONArray()
                    for (tc in msg.toolCalls) {
                        toolCallsArray.put(JSONObject().apply {
                            put("id", tc.id)
                            put("type", "function")
                            put("function", JSONObject().apply {
                                put("name", tc.name)
                                put("arguments", tc.arguments)
                            })
                        })
                    }
                    put("tool_calls", toolCallsArray)
                    if (msg.content.isNotEmpty()) {
                        put("content", msg.content)
                    }
                }
                // All other roles (user, system, tool, plain assistant)
                else -> {
                    put("content", msg.content)
                }
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Internal helpers
// ──────────────────────────────────────────────────────────────────────────────

/**
 * Accumulates tool-call fragments across multiple SSE delta chunks.
 *
 * The first chunk carries the id and function name; subsequent chunks deliver
 * the arguments JSON string piecemeal.
 */
private class ToolCallAccumulator {
    var id: String? = null
    var name: String? = null
    val argumentsBuilder = StringBuilder()

    private var emitted = false

    /**
     * Incorporate one tool-call delta object from an SSE event.
     * Emits [AiEvent.ToolCall] once the tool call is complete (all fragments
     * received and the id + name are known).
     */
    fun feed(tcJson: JSONObject, onEvent: (AiEvent) -> Unit) {
        if (emitted) return

        // The id field is only present in the first chunk
        if (tcJson.has("id") && !tcJson.isNull("id")) {
            id = tcJson.getString("id")
        }

        // Expand function sub-object
        val func = tcJson.optJSONObject("function") ?: return
        if (func.has("name") && !func.isNull("name")) {
            name = func.getString("name")
        }
        if (func.has("arguments") && !func.isNull("arguments")) {
            argumentsBuilder.append(func.getString("arguments"))
        }

        emitIfComplete(onEvent)
    }

    /** Emit the completed tool call if we have both an id and a name. */
    fun emitIfComplete(onEvent: (AiEvent) -> Unit) {
        if (emitted) return
        val resolvedId = id ?: return
        val resolvedName = name ?: return
        onEvent(AiEvent.ToolCall(resolvedId, resolvedName, argumentsBuilder.toString()))
        emitted = true
    }
}
