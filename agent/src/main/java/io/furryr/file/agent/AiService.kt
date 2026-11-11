package io.furryr.file.agent

import io.furryr.file.proto.hub.ChatEvent
import io.furryr.file.proto.hub.TokenUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
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

    private data class Resolved(
        val provider: AgentAiProvider,
        val model: String,
    )

    /** A qualified model is `Provider/model`; the longest provider name wins. */
    private fun resolve(config: AgentAiConfig, model: String): Resolved {
        val providers = config.providers.ifEmpty {
            listOf(
                AgentAiProvider(
                    name = config.provider.ifBlank { "Default" },
                    baseUrl = config.baseUrl,
                    apiKey = config.apiKey,
                    kind = config.kind,
                ),
            )
        }
        val matched = providers
            .filter { model.startsWith("${it.name}/") }
            .maxByOrNull { it.name.length }
        if (matched != null) {
            return Resolved(matched, model.removePrefix("${matched.name}/"))
        }
        val preferred = providers.firstOrNull { it.name == config.provider }
            ?: providers.first()
        return Resolved(preferred, model)
    }

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
        val resolved = resolve(config, model)
        val provider = resolved.provider
        val responses = provider.kind == AGENT_PROVIDER_RESPONSES
        val url = if (responses) responsesEndpoint(provider.baseUrl) else endpoint(provider.baseUrl)
        val body = if (responses) {
            responsesBody(resolved.model, effort, system, history, toolsJson)
        } else {
            JSONObject().apply {
                put("model", resolved.model)
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
                if (provider.apiKey.isNotEmpty()) {
                    setRequestProperty("Authorization", "Bearer ${provider.apiKey}")
                }
            }
            val cancelWatcher = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    // Cancellation must close the socket immediately: readLine()
                    // on a silent stream would otherwise ignore the cancel until
                    // the read timeout, hanging Esc/cancel in the agent terminal.
                    runCatching { connection.disconnect() }
                }
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
                cancelWatcher.cancel()
                runCatching { connection.disconnect() }
            }
        }
    }

    private fun generateTitle(model: String, firstUser: String): String? {
        val config = runCatching { AgentEnvironment.aiConfigProvider?.invoke() }.getOrNull()
            ?: return null
        val selected = config.titleModel.ifBlank { model.ifBlank { config.model } }
        val resolved = resolve(config, selected)
        val responses = resolved.provider.kind == AGENT_PROVIDER_RESPONSES
        val url = runCatching {
            if (responses) responsesEndpoint(resolved.provider.baseUrl)
            else endpoint(resolved.provider.baseUrl)
        }.getOrNull() ?: return null
        val prompt = "Create a short title (at most 8 words) for the conversation that starts " +
            "with the user message below. Reply with only the title, without quotes."
        val body = JSONObject().apply {
            put("model", resolved.model)
            put("stream", false)
            if (responses) {
                put("instructions", prompt)
                put("input", firstUser.take(2000))
                put("max_output_tokens", 256)
            } else {
                put("max_tokens", 256)
                put("messages", JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", prompt))
                    put(JSONObject().put("role", "user").put("content", firstUser.take(2000)))
                })
            }
        }
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            instanceFollowRedirects = false
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            if (resolved.provider.apiKey.isNotEmpty()) {
                setRequestProperty("Authorization", "Bearer ${resolved.provider.apiKey}")
            }
        }
        return try {
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            if (connection.responseCode !in 200..299) return null
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(text)
            val content = if (responses) {
                root.optString("output_text").ifBlank {
                    root.optJSONArray("output")
                        ?.optJSONObject(0)
                        ?.optJSONArray("content")
                        ?.optJSONObject(0)
                        ?.optString("text")
                        .orEmpty()
                }
            } else {
                root.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                    .orEmpty()
            }
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
        val providers = config.providers.ifEmpty {
            listOf(
                AgentAiProvider(
                    name = config.provider.ifBlank { "Default" },
                    baseUrl = config.baseUrl,
                    apiKey = config.apiKey,
                    kind = config.kind,
                ),
            )
        }
        val catalog = Catalog()
        for (provider in providers) {
            val chatUrl = runCatching { endpoint(provider.baseUrl) }.getOrNull()
            if (chatUrl != null && provider.kind != AGENT_PROVIDER_RESPONSES) {
                for (pricingUrl in pricingEndpoints(chatUrl)) {
                    val json = getJson(pricingUrl, provider.apiKey) ?: continue
                    catalog.applyPricing(json, provider.name)
                    if (catalog.hasModels(provider.name)) break
                }
            }
            val live = chatUrl?.let { modelsEndpoint(it) }
                ?.let { getJson(it, provider.apiKey) }
                ?.let(::modelEntries)
                .orEmpty()
            catalog.applyProvider(provider, live)
        }
        if (catalog.models.isEmpty()) {
            catalog.models.addAll(catalog.info.keys.sorted())
        }
        return catalog.toJson()
    }

    /** Model objects from either `{"data":[...]}` or a bare array. */
    private fun modelEntries(json: JSONObject): List<JSONObject> {
        val array = json.optJSONArray("data")
            ?: json.optJSONArray("models")
            ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }

    private fun providerMessages(system: String, history: JSONArray): JSONArray {
        val array = JSONArray()
        if (system.isNotBlank()) {
            array.put(JSONObject().put("role", "system").put("content", system))
        }
        for (index in 0 until history.length()) {
            val message = history.optJSONObject(index) ?: continue
            // `compaction_summary` is transcript metadata, not wire format.
            val clean = JSONObject()
            message.keys().forEach { key ->
                if (key != "compaction_summary") clean.put(key, message.opt(key))
            }
            array.put(clean)
        }
        return array
    }

    /** Responses API request: `instructions` + typed input items. */
    private fun responsesBody(
        model: String,
        effort: String,
        system: String,
        history: JSONArray,
        toolsJson: String,
    ): JSONObject = JSONObject().apply {
        put("model", model)
        put("stream", true)
        if (system.isNotBlank()) put("instructions", system)
        val input = JSONArray()
        for (index in 0 until history.length()) {
            val message = history.optJSONObject(index) ?: continue
            when (message.optString("role")) {
                "assistant" -> {
                    val content = message.optString("content")
                    if (content.isNotBlank()) {
                        input.put(JSONObject().put("role", "assistant").put("content", content))
                    }
                    val calls = message.optJSONArray("tool_calls")
                    if (calls != null) {
                        for (callIndex in 0 until calls.length()) {
                            val call = calls.optJSONObject(callIndex) ?: continue
                            val function = call.optJSONObject("function") ?: continue
                            input.put(
                                JSONObject()
                                    .put("type", "function_call")
                                    .put("call_id", call.optString("id"))
                                    .put("name", function.optString("name"))
                                    .put("arguments", function.optString("arguments")),
                            )
                        }
                    }
                }
                "tool" -> input.put(
                    JSONObject()
                        .put("type", "function_call_output")
                        .put("call_id", message.optString("tool_call_id"))
                        .put("output", message.optString("content")),
                )
                else -> input.put(
                    JSONObject()
                        .put("role", message.optString("role").ifBlank { "user" })
                        .put("content", message.optString("content")),
                )
            }
        }
        put("input", input)
        if (effort.isNotBlank() && effort != "auto") {
            put("reasoning", JSONObject().put("effort", effort))
        }
        val tools = runCatching { JSONArray(toolsJson) }.getOrNull()
        if (tools != null && tools.length() > 0) {
            val converted = JSONArray()
            for (index in 0 until tools.length()) {
                val tool = tools.optJSONObject(index)?.optJSONObject("function") ?: continue
                converted.put(
                    JSONObject()
                        .put("type", "function")
                        .put("name", tool.optString("name"))
                        .put("description", tool.optString("description"))
                        .put("parameters", tool.optJSONObject("parameters") ?: JSONObject()),
                )
            }
            put("tools", converted)
            put("tool_choice", "auto")
        }
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

    private fun responsesEndpoint(base: String): String {
        val trimmed = base.trim().trimEnd('/')
        val address = if (trimmed.endsWith("/responses")) trimmed else "$trimmed/responses"
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

        fun hasModels(provider: String): Boolean =
            models.any { it.startsWith("$provider/") }

        fun applyPricing(json: JSONObject, provider: String) {
            val group = json.optJSONObject("group_ratio")?.let { groups ->
                groups.optDouble("default").takeIf { !it.isNaN() }
                    ?: groups.keys().asSequence().mapNotNull { key ->
                        groups.optDouble(key).takeIf { !it.isNaN() }
                    }.firstOrNull()
            } ?: 1.0
            val entries = json.optJSONArray("data") ?: return
            for (index in 0 until entries.length()) {
                val entry = entries.optJSONObject(index) ?: continue
                val id = entry.optString("model_name").takeIf { it.isNotEmpty() } ?: continue
                val qualified = "$provider/$id"
                if (qualified !in models) models.add(qualified)
                val info = info.getOrPut(qualified) { baseInfo(provider) }
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

        /**
         * Merge live `/models` entries with the models configured in the app.
         * Configured limits and prices win; capabilities and efforts are filled
         * in when the API or the model family exposes them.
         */
        fun applyProvider(provider: AgentAiProvider, live: List<JSONObject>) {
            val configured = provider.models.associateBy { it.id }
            for (model in provider.models) {
                add(provider, model.id, model, live.firstOrNull { idOf(it) == model.id })
            }
            for (entry in live) {
                val id = idOf(entry) ?: continue
                if (configured.containsKey(id)) continue
                add(provider, id, null, entry)
            }
        }

        private fun add(
            provider: AgentAiProvider,
            id: String,
            configured: AgentModelConfig?,
            live: JSONObject?,
        ) {
            val qualified = "${provider.name}/$id"
            if (qualified !in models) models.add(qualified)
            val info = info.getOrPut(qualified) { baseInfo(provider.name) }
            val context = configured?.contextWindow?.takeIf { it > 0 } ?: live?.let(::contextOf)
            if (context != null && !info.has("context_window")) {
                info.put("context_window", context)
            }
            val maxOutput = configured?.maxOutput?.takeIf { it > 0 }
                ?: live?.let(::maxOutputOf)
            if (maxOutput != null && !info.has("max_output")) info.put("max_output", maxOutput)
            if (configured != null) {
                if (configured.inputPerMillion > 0.0 && !info.has("input_per_million")) {
                    info.put("input_per_million", configured.inputPerMillion)
                }
                if (configured.outputPerMillion > 0.0 && !info.has("output_per_million")) {
                    info.put("output_per_million", configured.outputPerMillion)
                }
                if (configured.cacheReadPerMillion > 0.0 && !info.has("cache_read_per_million")) {
                    info.put("cache_read_per_million", configured.cacheReadPerMillion)
                }
                if (configured.cacheWritePerMillion > 0.0 && !info.has("cache_write_per_million")) {
                    info.put("cache_write_per_million", configured.cacheWritePerMillion)
                }
            }
            // OpenRouter-style per-token pricing and modalities.
            live?.optJSONObject("pricing")?.let { pricing ->
                val prompt = pricing.optDouble("prompt", 0.0)
                val completion = pricing.optDouble("completion", 0.0)
                val cacheRead = pricing.optDouble("input_cache_read", 0.0)
                val cacheWrite = pricing.optDouble("input_cache_write", 0.0)
                if (prompt > 0.0 && !info.has("input_per_million")) {
                    info.put("input_per_million", prompt * 1_000_000.0)
                }
                if (completion > 0.0 && !info.has("output_per_million")) {
                    info.put("output_per_million", completion * 1_000_000.0)
                }
                if (cacheRead > 0.0 && !info.has("cache_read_per_million")) {
                    info.put("cache_read_per_million", cacheRead * 1_000_000.0)
                }
                if (cacheWrite > 0.0 && !info.has("cache_write_per_million")) {
                    info.put("cache_write_per_million", cacheWrite * 1_000_000.0)
                }
            }
            // Capabilities come from the configured model or the provider's
            // own metadata. Never infer them from a model-name table.
            val thinking = configured?.thinking
                ?: live?.let { capability(it, "thinking", "reasoning") }
                ?: false
            val vision = configured?.vision
                ?: live?.let { capability(it, "vision") }
                ?: false
            val toolCall = configured?.toolCall
                ?: live?.let { capability(it, "tool_call", "tools", "function_calling") }
                ?: true
            info.put("thinking", thinking)
            info.put("vision", vision)
            info.put("tool_call", toolCall)
            if (!info.has("efforts")) {
                // TODO(effort discovery): most providers do not report their
                // accepted reasoning-effort values yet. Parse the generic keys
                // below when present; otherwise only configured values (or
                // `auto`) are offered. No model-name table, no fixed scale.
                val efforts = configured?.efforts?.filter { it.isNotBlank() }
                    ?.takeIf { it.isNotEmpty() }
                    ?: live?.let(::effortsOf)
                    ?: emptyList()
                info.put("efforts", JSONArray().apply { efforts.forEach(::put) })
            }
        }

        private fun baseInfo(provider: String) = JSONObject().put("provider", provider)

        private fun idOf(entry: JSONObject): String? =
            entry.optString("id").ifBlank { entry.optString("model") }
                .takeIf { it.isNotEmpty() }

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

        private fun maxOutputOf(value: JSONObject): Long? {
            val keys = listOf(
                "max_output_tokens", "max_completion_tokens", "output_token_limit",
                "max_tokens",
            )
            for (key in keys) {
                positiveLong(value.opt(key))?.let { return it }
            }
            for (key in listOf("top_provider", "limits")) {
                value.optJSONObject(key)?.let { nested ->
                    maxOutputOf(nested)?.let { return it }
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

    private fun effortsOf(entry: JSONObject): List<String> {
        val keys = listOf(
            "reasoning_efforts", "reasoning_effort", "supported_efforts",
            "effort_levels", "efforts",
        )
        for (key in keys) {
            entry.optJSONArray(key)?.let { values ->
                val list = (0 until values.length()).mapNotNull {
                    values.optString(it).trim().takeIf(String::isNotEmpty)
                }
                if (list.isNotEmpty()) return list
            }
            for (container in listOf("capabilities", "metadata", "meta", "reasoning")) {
                val nested = entry.optJSONObject(container) ?: continue
                effortsOf(nested).takeIf { it.isNotEmpty() }?.let { return it }
            }
        }
        return emptyList()
    }

    /** Read a capability flag from flat fields or a nested capabilities object. */
    private fun capability(entry: JSONObject, vararg names: String): Boolean {
        for (name in names) {
            if (entry.has(name)) return entry.optBoolean(name)
            for (container in listOf("capabilities", "metadata", "meta")) {
                val nested = entry.optJSONObject(container) ?: continue
                if (nested.has(name)) return nested.optBoolean(name)
            }
        }
        // OpenRouter exposes modalities and supported parameters as lists.
        entry.optJSONObject("architecture")?.optJSONArray("input_modalities")?.let { values ->
            for (index in 0 until values.length()) {
                val value = values.optString(index)
                if (names.any { value.contains(it, ignoreCase = true) } ||
                    (names.any { it == "vision" } && value.equals("image", true))
                ) {
                    return true
                }
            }
        }
        entry.optJSONArray("supported_parameters")?.let { values ->
            for (index in 0 until values.length()) {
                val value = values.optString(index)
                if (names.any { value.contains(it, ignoreCase = true) }) return true
            }
        }
        return false
    }
    data class ChatResult(
        val content: String,
        val reasoning: String,
        val toolCalls: JSONArray,
    )
}
