package io.furryr.file.agent

import io.furryr.file.proto.hub.ChatEvent
import io.furryr.file.proto.hub.TokenUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * OpenAI-compatible chat gateway. Provider credentials stay in the app; the
 * agent only sees normalized Chat events. Future provider adapters implement
 * the same normalized provider-event contract behind this service.
 */
object AiService {
    private const val MAX_METADATA = 4 * 1024 * 1024

    suspend fun complete(
        model: String,
        effort: String,
        system: String,
        history: JSONArray,
        toolsJson: String,
        emit: suspend (ChatEvent) -> Unit,
    ): ChatResult? {
        val config = requireNotNull(AgentEnvironment.aiConfigProvider) {
            "AI configuration is unavailable"
        }.invoke()
        return perform(config, model, effort, system, history, toolsJson, emit)
    }

    private suspend fun perform(
        config: AgentAiConfig,
        model: String,
        effort: String,
        system: String,
        history: JSONArray,
        toolsJson: String,
        emit: suspend (ChatEvent) -> Unit,
    ): ChatResult? {
        val url = endpoint(config.baseUrl)
        val body = JSONObject().apply {
            put("model", model)
            put("stream", true)
            put("stream_options", JSONObject().put("include_usage", true))
            put("messages", providerMessages(system, history))
            if (effort.isNotBlank() && effort != "auto") {
                put("reasoning_effort", effort)
            }
            val tools = runCatching { JSONArray(toolsJson) }.getOrNull()
            if (tools != null && tools.length() > 0) {
                put("tools", tools)
                put("tool_choice", "auto")
            }
        }
        return withContext(Dispatchers.IO) {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 600_000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "text/event-stream")
                if (config.apiKey.isNotEmpty()) {
                    setRequestProperty("Authorization", "Bearer ${config.apiKey}")
                }
            }
            currentCoroutineContext()[Job]?.invokeOnCompletion {
                runCatching { connection.disconnect() }
            }
            try {
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
                val status = connection.responseCode
                if (status !in 200..299) {
                    emitError(emit, "API returned HTTP $status; check the model, API key and provider limits.")
                    return@withContext null
                }
                val reader = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8))
                val calls = ArrayList<CallBuilder>()
                val content = StringBuilder()
                val reasoning = StringBuilder()
                val emitEvent: suspend (ChatEvent) -> Unit = { event ->
                    if (event.hasContentDelta()) content.append(event.contentDelta)
                    if (event.hasReasoningDelta()) reasoning.append(event.reasoningDelta)
                    emit(event)
                }
                var toolCalling = false
                var done = false
                var received = 0
                reader.use { input ->
                    while (!done) {
                        currentCoroutineContext().ensureActive()
                        val line = input.readLine() ?: break
                        received += line.length
                        if (received > 8 * 1024 * 1024) {
                            emitError(emitEvent, "API response is too large.")
                            return@withContext null
                        }
                        done = applyLine(line, calls, emitEvent) {
                            if (!toolCalling) {
                                toolCalling = true
                                true
                            } else {
                                false
                            }
                        }
                    }
                }
                val toolCalls = buildToolCalls(calls)
                if (toolCalls.length() > 0) {
                    emitEvent(ChatEvent.newBuilder().setToolCallsJson(toolCalls.toString()).build())
                }
                emitEvent(ChatEvent.newBuilder().setDone(true).build())
                ChatResult(content.toString(), reasoning.toString(), toolCalls)
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                emitError(emit, e.message ?: "Could not read the API response.")
                null
            } finally {
                runCatching { connection.disconnect() }
            }
        }
    }

    private fun generateTitle(model: String, firstUser: String): String? {
        val config = runCatching { AgentEnvironment.aiConfigProvider?.invoke() }.getOrNull()
            ?: return null
        val url = runCatching { endpoint(config.baseUrl) }.getOrNull() ?: return null
        val body = JSONObject().apply {
            put("model", config.titleModel.ifBlank { model.ifBlank { config.model } })
            put("stream", false)
            put("max_tokens", 256)
            put("messages", JSONArray().apply {
                put(
                    JSONObject().put("role", "system").put(
                        "content",
                        "Create a short title (at most 8 words) for the conversation that starts " +
                            "with the user message below. Reply with only the title, without quotes.",
                    ),
                )
                put(
                    JSONObject().put("role", "user").put("content", firstUser.take(2000)),
                )
            })
        }
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            instanceFollowRedirects = false
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            if (config.apiKey.isNotEmpty()) {
                setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            }
        }
        return try {
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            if (connection.responseCode !in 200..299) return null
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            val content = JSONObject(text)
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                .orEmpty()
            content
                .replace(Regex("\\s+"), " ")
                .trim()
                .trim('"', '\'', '`', '“', '”')
                .trim()
                .take(60)
                .takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    suspend fun titleFor(model: String, firstUser: String): String? = generateTitle(model, firstUser)

    suspend fun catalog(): String {
        val config = requireNotNull(AgentEnvironment.aiConfigProvider) {
            "AI configuration is unavailable"
        }.invoke()
        val chatUrl = endpoint(config.baseUrl)
        val catalog = Catalog()
        for (pricingUrl in pricingEndpoints(chatUrl)) {
            val json = getJson(pricingUrl, config.apiKey) ?: continue
            catalog.applyPricing(json)
            if (catalog.info.isNotEmpty()) break
        }
        modelsEndpoint(chatUrl)?.let { url ->
            getJson(url, config.apiKey)?.let(catalog::applyModels)
        }
        if (catalog.models.isEmpty()) {
            catalog.models.addAll(catalog.info.keys.sorted())
        }
        return catalog.toJson()
    }

    private fun providerMessages(system: String, history: JSONArray): JSONArray {
        val array = JSONArray()
        if (system.isNotBlank()) {
            array.put(JSONObject().put("role", "system").put("content", system))
        }
        for (index in 0 until history.length()) {
            history.optJSONObject(index)?.let { array.put(it) }
        }
        return array
    }

    private suspend fun emitError(emit: suspend (ChatEvent) -> Unit, message: String) {
        emit(ChatEvent.newBuilder().setError(message).build())
        emit(ChatEvent.newBuilder().setDone(true).build())
    }

    /**
     * One SSE payload, in either wire format providers use: Chat Completions
     * chunks (`choices[0].delta`) or the typed events of the OpenAI Responses
     * API. Both normalize into the same reasoning/content/tool-call
     * accumulators.
     */
    private suspend fun applyLine(
        line: String,
        calls: MutableList<CallBuilder>,
        emit: suspend (ChatEvent) -> Unit,
        toolCalling: () -> Boolean,
    ): Boolean {
        val trimmed = line.trim()
        if (!trimmed.startsWith("data:")) return false
        val data = trimmed.removePrefix("data:").trim()
        if (data.isEmpty()) return false
        if (data == "[DONE]") return true
        val chunk = runCatching { JSONObject(data) }.getOrNull() ?: return false
        val type = chunk.optString("type")
        return if (type.startsWith("response.") || type == "error") {
            applyResponsesEvent(chunk, type, calls, emit, toolCalling)
        } else {
            applyChatChunk(chunk, calls, emit, toolCalling)
        }
    }

    /** Chat Completions chunk: `choices[0].delta` carries the fragments. */
    private suspend fun applyChatChunk(
        chunk: JSONObject,
        calls: MutableList<CallBuilder>,
        emit: suspend (ChatEvent) -> Unit,
        toolCalling: () -> Boolean,
    ): Boolean {
        chunk.optJSONObject("usage")?.let { usage -> emit(usageEvent(usage)) }
        val choice = chunk.optJSONArray("choices")?.optJSONObject(0) ?: return false
        val delta = choice.optJSONObject("delta") ?: return false
        val reasoning = delta.optString("reasoning_content")
            .ifBlank { delta.optString("reasoning") }
        if (reasoning.isNotEmpty()) {
            emit(ChatEvent.newBuilder().setReasoningDelta(reasoning).build())
        }
        val content = delta.optString("content")
        if (content.isNotEmpty()) {
            emit(ChatEvent.newBuilder().setContentDelta(content).build())
        }
        val toolCalls = delta.optJSONArray("tool_calls")
        if (toolCalls != null && toolCalls.length() > 0) {
            if (toolCalling()) {
                emit(ChatEvent.newBuilder().setToolCalling(true).build())
            }
            for (index in 0 until toolCalls.length()) {
                val call = toolCalls.optJSONObject(index) ?: continue
                val builder = builderFor(calls, call)
                call.optString("id").takeIf { it.isNotEmpty() }?.let { builder.id = it }
                call.optString("type").takeIf { it.isNotEmpty() }?.let { builder.type = it }
                call.optJSONObject("function")?.let { function ->
                    val name = function.optString("name").takeIf { it.isNotEmpty() }
                    val arguments =
                        function.optString("arguments").takeIf { it.isNotEmpty() }
                    if (name != null && builder.name.endsWith(name)) {
                        // A repeated name means this chunk carries the call as
                        // built so far, not a fragment: keep the longer form
                        // instead of appending a second copy.
                        if (arguments != null && arguments.length > builder.arguments.length) {
                            builder.arguments = arguments
                        }
                    } else {
                        name?.let { builder.name += it }
                        arguments?.let { builder.arguments += it }
                    }
                }
            }
        }
        return false
    }

    /** One typed event of the OpenAI Responses API. */
    private suspend fun applyResponsesEvent(
        event: JSONObject,
        type: String,
        calls: MutableList<CallBuilder>,
        emit: suspend (ChatEvent) -> Unit,
        toolCalling: () -> Boolean,
    ): Boolean {
        when (type) {
            "response.output_item.added" -> functionItem(event.optJSONObject("item"))?.let { item ->
                if (toolCalling()) {
                    emit(ChatEvent.newBuilder().setToolCalling(true).build())
                }
                val builder = itemBuilder(calls, item.itemId)
                if (builder.id.isEmpty()) builder.id = item.callId
                if (builder.name.isEmpty()) builder.name = item.name
            }

            "response.function_call_arguments.delta" ->
                itemBuilder(calls, event.optString("item_id"))
                    .arguments += event.optString("delta")

            "response.function_call_arguments.done" ->
                itemBuilder(calls, event.optString("item_id"))
                    .arguments = event.optString("arguments")

            "response.output_item.done" ->
                functionItem(event.optJSONObject("item"))?.let { finalizeItem(calls, it) }

            "response.output_text.delta" -> {
                val delta = event.optString("delta")
                if (delta.isNotEmpty()) {
                    emit(ChatEvent.newBuilder().setContentDelta(delta).build())
                }
            }

            "response.reasoning_text.delta",
            "response.reasoning_summary_text.delta" -> {
                val delta = event.optString("delta")
                if (delta.isNotEmpty()) {
                    emit(ChatEvent.newBuilder().setReasoningDelta(delta).build())
                }
            }

            "response.completed", "response.incomplete" -> {
                val response = event.optJSONObject("response")
                response?.optJSONObject("usage")?.let { emit(usageEvent(it)) }
                response?.optJSONArray("output")?.let { output ->
                    for (index in 0 until output.length()) {
                        functionItem(output.optJSONObject(index))?.let { finalizeItem(calls, it) }
                    }
                }
                return true
            }

            "response.failed" -> {
                val message = event.optJSONObject("response")
                    ?.optJSONObject("error")
                    ?.optString("message")
                    .orEmpty()
                if (message.isNotEmpty()) {
                    emit(ChatEvent.newBuilder().setError(message).build())
                }
                return true
            }

            "error" -> {
                val message = event.optJSONObject("error")?.optString("message")
                    ?.ifBlank { event.optString("message") }
                    .orEmpty()
                if (message.isNotEmpty()) {
                    emit(ChatEvent.newBuilder().setError(message).build())
                }
                return true
            }
        }
        return false
    }

    private fun endpoint(base: String): String {
        val trimmed = base.trim().trimEnd('/')
        val address = if (trimmed.endsWith("/chat/completions")) trimmed
        else "$trimmed/chat/completions"
        val url = URL(address)
        require(url.protocol == "http" || url.protocol == "https") {
            "AI base URL must be HTTP(S)"
        }
        require(url.host.isNotBlank()) { "AI base URL has no host" }
        return address
    }

    private fun pricingEndpoints(chat: String): List<String> {
        val url = URL(chat)
        val path = url.path.removeSuffix("/chat/completions").removeSuffix("/v1")
        val derived = URL(url.protocol, url.host, url.port, "$path/api/pricing".replace("//", "/"))
        val origin = URL(url.protocol, url.host, url.port, "/api/pricing")
        return if (derived.toString() == origin.toString()) listOf(derived.toString())
        else listOf(derived.toString(), origin.toString())
    }

    private fun modelsEndpoint(chat: String): String? {
        val url = URL(chat)
        val path = url.path.removeSuffix("/chat/completions")
        return URL(url.protocol, url.host, url.port, "$path/models".replace("//", "/")).toString()
    }

    private fun getJson(address: String, key: String): JSONObject? {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = false
            connectTimeout = 20_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/json")
            if (key.isNotEmpty()) setRequestProperty("Authorization", "Bearer $key")
        }
        return try {
            if (connection.responseCode !in 200..299) return null
            val length = connection.contentLengthLong
            if (length > MAX_METADATA) return null
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            if (text.toByteArray().size > MAX_METADATA) null else runCatching { JSONObject(text) }.getOrNull()
        } catch (_: Exception) {
            null
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    private class CallBuilder {
        var id: String = ""

        // Responses API item id (`fc_...`) used to route argument deltas; the
        // id above is the `call_id` the caller must answer.
        var itemId: String = ""
        var type: String = ""
        var name: String = ""
        var arguments: String = ""

        fun build(): JSONObject = JSONObject().apply {
            put("id", id)
            put("type", type.ifBlank { "function" })
            put(
                "function",
                JSONObject().put("name", name).put("arguments", arguments),
            )
        }
    }

    private class FunctionItem(
        val itemId: String,
        val callId: String,
        val name: String,
        val arguments: String,
    )

    private fun functionItem(item: JSONObject?): FunctionItem? {
        if (item == null || item.optString("type") != "function_call") return null
        val itemId = item.optString("id")
        return FunctionItem(
            itemId = itemId,
            callId = item.optString("call_id").ifBlank { itemId },
            name = item.optString("name"),
            arguments = item.optString("arguments"),
        )
    }

    /**
     * Chat Completions streams tag every fragment with the call's index;
     * some compatible providers omit it, or resend the full call per chunk.
     * Match by index, then id, then the newest call, so one logical call is
     * never split into duplicates.
     */
    private fun builderFor(calls: MutableList<CallBuilder>, call: JSONObject): CallBuilder {
        if (call.has("index")) {
            val position = call.optInt("index").coerceAtLeast(0)
            while (calls.size <= position) calls.add(CallBuilder())
            return calls[position]
        }
        val id = call.optString("id")
        if (id.isNotEmpty()) {
            return calls.lastOrNull { it.id == id } ?: CallBuilder().also(calls::add)
        }
        return calls.lastOrNull() ?: CallBuilder().also(calls::add)
    }

    /** Find (or start) the builder of one Responses API function-call item. */
    private fun itemBuilder(calls: MutableList<CallBuilder>, itemId: String): CallBuilder {
        if (itemId.isNotEmpty()) {
            calls.lastOrNull { it.itemId == itemId || (it.id.isNotEmpty() && it.id == itemId) }
                ?.let { return it }
            return CallBuilder().apply { this.itemId = itemId }.also(calls::add)
        }
        return calls.lastOrNull() ?: CallBuilder().also(calls::add)
    }

    /** Apply the authoritative complete form of a function-call item. */
    private fun finalizeItem(calls: MutableList<CallBuilder>, item: FunctionItem) {
        val builder = itemBuilder(calls, item.itemId)
        builder.itemId = item.itemId
        if (item.callId.isNotEmpty()) builder.id = item.callId
        if (item.name.isNotEmpty()) builder.name = item.name
        if (item.arguments.isNotEmpty()) builder.arguments = item.arguments
    }

    /**
     * One logical call per id: providers that resend a complete call after
     * its fragments must not produce duplicate ids, which the API rejects on
     * the next request. The last (most complete) copy wins.
     */
    private fun buildToolCalls(calls: List<CallBuilder>): JSONArray {
        val merged = ArrayList<CallBuilder>()
        val positions = HashMap<String, Int>()
        for (builder in calls) {
            if (builder.name.isBlank()) continue
            if (builder.id.isNotEmpty()) {
                val existing = positions[builder.id]
                if (existing != null) {
                    merged[existing] = builder
                    continue
                }
                positions[builder.id] = merged.size
            }
            merged.add(builder)
        }
        val array = JSONArray()
        merged.forEach { array.put(it.build()) }
        return array
    }

    // Chat Completions reports prompt/completion tokens; the Responses API
    // reports input/output tokens.
    private fun usageEvent(usage: JSONObject): ChatEvent {
        val prompt = usage.optLong("prompt_tokens")
            .takeIf { it > 0 } ?: usage.optLong("input_tokens")
        val completion = usage.optLong("completion_tokens")
            .takeIf { it > 0 } ?: usage.optLong("output_tokens")
        val total = usage.optLong("total_tokens")
        // Reasoning tokens are sometimes reported as a subset of completion
        // tokens and sometimes as a separate bucket. Prefer the billed total
        // when available, then fall back to the larger explicit count, so CoT
        // is not silently excluded (and not double-counted when included).
        val reasoning = sequenceOf("completion_tokens_details", "output_tokens_details")
            .mapNotNull { usage.optJSONObject(it)?.optLong("reasoning_tokens") }
            .firstOrNull { it > 0 }
            ?: usage.optLong("reasoning_tokens").takeIf { it > 0 }
            ?: 0L
        val fromTotal = if (total > prompt) total - prompt else 0L
        val billed = maxOf(completion, fromTotal)
        val completionWithReasoning = if (reasoning > billed) billed + reasoning else billed
        return ChatEvent.newBuilder()
            .setUsage(
                TokenUsage.newBuilder()
                    .setPromptTokens(prompt)
                    .setCompletionTokens(completionWithReasoning),
            )
            .build()
    }

    private class Catalog {
        val models = ArrayList<String>()
        val info = LinkedHashMap<String, JSONObject>()

        fun applyPricing(json: JSONObject) {
            val group = json.optJSONObject("group_ratio")?.let { groups ->
                groups.optDouble("default").takeIf { !it.isNaN() }
                    ?: groups.keys().asSequence().mapNotNull { key ->
                        groups.optDouble(key).takeIf { !it.isNaN() }
                    }.firstOrNull()
            } ?: 1.0
            val entries = json.optJSONArray("data") ?: return
            for (index in 0 until entries.length()) {
                val entry = entries.optJSONObject(index) ?: continue
                val name = entry.optString("model_name").takeIf { it.isNotEmpty() } ?: continue
                val info = info.getOrPut(name) { JSONObject() }
                if (!info.has("context_window")) {
                    contextOf(entry)?.let { info.put("context_window", it) }
                }
                val quotaType = entry.optLong("quota_type")
                val modelPrice = entry.optDouble("model_price", 0.0)
                if (quotaType == 1L && modelPrice > 0.0) {
                    info.put("request_price", modelPrice * group)
                    continue
                }
                val ratio = entry.optDouble("model_ratio", 0.0)
                if (ratio <= 0.0) continue
                val input = ratio * group * 2.0
                val completion = entry.optDouble("completion_ratio", 1.0).coerceAtLeast(0.0)
                info.put("input_per_million", input)
                info.put("output_per_million", input * completion)
            }
        }

        fun applyModels(json: JSONObject) {
            val entries = json.optJSONArray("data") ?: return
            for (index in 0 until entries.length()) {
                val entry = entries.optJSONObject(index) ?: continue
                val id = entry.optString("id").ifBlank { entry.optString("model") }
                if (id.isEmpty()) continue
                if (id !in models) models.add(id)
                contextOf(entry)?.let {
                    info.getOrPut(id) { JSONObject() }.put("context_window", it)
                }
            }
        }

        fun toJson(): String {
            val modelArray = JSONArray()
            models.forEach { modelArray.put(it) }
            val infoObject = JSONObject()
            info.forEach { (name, value) -> infoObject.put(name, value) }
            return JSONObject()
                .put("models", modelArray)
                .put("info", infoObject)
                .toString()
        }

        private fun contextOf(value: JSONObject): Long? {
            val keys = listOf(
                "context_length", "max_prompt_tokens", "max_model_len",
                "context_window", "max_context_length", "max_input_tokens",
                "input_token_limit", "context_size",
            )
            for (key in keys) {
                positiveLong(value.opt(key))?.let { return it }
            }
            for (key in listOf("meta", "metadata", "top_provider", "limits", "capabilities")) {
                value.optJSONObject(key)?.let { nested ->
                    contextOf(nested)?.let { return it }
                }
            }
            return null
        }

        private fun positiveLong(value: Any?): Long? {
            val number = when (value) {
                is Number -> value.toDouble()
                is String -> value.toDoubleOrNull()
                else -> null
            } ?: return null
            return number.toLong().takeIf { number > 0.0 }
        }
    }
    data class ChatResult(
        val content: String,
        val reasoning: String,
        val toolCalls: JSONArray,
    )
}
