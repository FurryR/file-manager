package io.furryr.file.hub

import io.furryr.file.daemon.HubActions
import io.furryr.file.proto.hub.ChatEvent
import io.furryr.file.proto.hub.ChatRequest
import io.furryr.file.proto.hub.TokenUsage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
 * the same [ChatRequest] -> event contract behind this service.
 */
internal object AiService {
    private const val MAX_METADATA = 4 * 1024 * 1024
    private val titleScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    suspend fun chat(request: ChatRequest, emit: suspend (ChatEvent) -> Unit) {
        val config = requireNotNull(HubActions.aiConfigProvider) {
            "AI configuration is unavailable"
        }.invoke()
        val url = endpoint(config.baseUrl)
        val history = effectiveHistory(request)
        if (!request.append && request.sessionId.isNotBlank() &&
            HubActions.sessionSave != null &&
            HubActions.sessionLoad?.invoke(request.sessionId) == null
        ) {
            HubActions.sessionSave?.invoke(request.sessionId, history.toString())
            firstUser(history)?.let { message ->
                launchTitle(request.sessionId, request.model, message)
            }
        }
        val body = JSONObject().apply {
            put("model", request.model)
            put("stream", true)
            put("stream_options", JSONObject().put("include_usage", true))
            put("messages", providerMessages(request.system, history))
            if (request.effort.isNotBlank() && request.effort != "auto") {
                put("reasoning_effort", request.effort)
            }
            val tools = runCatching { JSONArray(request.toolsJson) }.getOrNull()
            if (tools != null && tools.length() > 0) {
                put("tools", tools)
                put("tool_choice", "auto")
            }
        }
        withContext(Dispatchers.IO) {
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
                    return@withContext
                }
                val reader = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8))
                val calls = ArrayList<CallBuilder>()
                val content = StringBuilder()
                val emitEvent: suspend (ChatEvent) -> Unit = { event ->
                    if (event.hasContentDelta()) content.append(event.contentDelta)
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
                            return@withContext
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
                val toolCalls = JSONArray()
                calls.filter { it.name.isNotBlank() }.forEach { toolCalls.put(it.build()) }
                if (toolCalls.length() > 0) {
                    emitEvent(ChatEvent.newBuilder().setToolCallsJson(toolCalls.toString()).build())
                }
                emitEvent(ChatEvent.newBuilder().setDone(true).build())
                saveHistory(request.sessionId, history, content.toString(), toolCalls)
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                emitError(emit, e.message ?: "Could not read the API response.")
            } finally {
                runCatching { connection.disconnect() }
            }
        }
    }

    private fun launchTitle(sessionId: String, model: String, firstUser: String) {
        titleScope.launch {
            val title = generateTitle(model, firstUser) ?: return@launch
            HubActions.sessionAutoTitle?.invoke(sessionId, title)
        }
    }

    private fun generateTitle(model: String, firstUser: String): String? {
        val config = runCatching { HubActions.aiConfigProvider?.invoke() }.getOrNull()
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

    private fun firstUser(history: JSONArray): String? {
        for (index in 0 until history.length()) {
            val message = history.optJSONObject(index) ?: continue
            if (message.optString("role") != "user") continue
            val content = message.optString("content").trim()
            if (content.isNotEmpty()) return content
        }
        return null
    }

    suspend fun catalog(): String {
        val config = requireNotNull(HubActions.aiConfigProvider) {
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

    private fun effectiveHistory(request: ChatRequest): JSONArray {
        val incoming = runCatching { JSONArray(request.messagesJson) }.getOrElse { JSONArray() }
        if (!request.append) return incoming
        require(request.sessionId.isNotBlank()) { "append chat requires a session" }
        val stored = requireNotNull(HubActions.sessionLoad?.invoke(request.sessionId)) {
            "history mismatch: no stored session"
        }
        val history = runCatching { JSONArray(stored) }
            .getOrElse { error("history mismatch: invalid session") }
        if (history.length() != request.baseCount.toInt()) {
            error("history mismatch")
        }
        for (index in 0 until incoming.length()) {
            history.optJSONObject(index)?.let { history.put(it) }
        }
        return history
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

    private fun saveHistory(
        sessionId: String,
        history: JSONArray,
        content: String,
        toolCalls: JSONArray,
    ) {
        if (sessionId.isBlank()) return
        if (content.isEmpty() && toolCalls.length() == 0) return
        val assistant = JSONObject().put("role", "assistant")
        if (content.isNotEmpty()) assistant.put("content", content)
        if (toolCalls.length() > 0) assistant.put("tool_calls", toolCalls)
        history.put(assistant)
        runCatching { HubActions.sessionSave?.invoke(sessionId, history.toString()) }
    }

    private suspend fun emitError(emit: suspend (ChatEvent) -> Unit, message: String) {
        emit(ChatEvent.newBuilder().setError(message).build())
        emit(ChatEvent.newBuilder().setDone(true).build())
    }

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
        chunk.optJSONObject("usage")?.let { usage ->
            emit(
                ChatEvent.newBuilder().setUsage(
                    TokenUsage.newBuilder()
                        .setPromptTokens(usage.optLong("prompt_tokens"))
                        .setCompletionTokens(usage.optLong("completion_tokens")),
                ).build(),
            )
        }
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
                val position = call.optInt("index", calls.size)
                while (calls.size <= position) calls.add(CallBuilder())
                val builder = calls[position]
                call.optString("id").takeIf { it.isNotEmpty() }?.let { builder.id = it }
                call.optString("type").takeIf { it.isNotEmpty() }?.let { builder.type = it }
                call.optJSONObject("function")?.let { function ->
                    function.optString("name").takeIf { it.isNotEmpty() }?.let {
                        builder.name += it
                    }
                    function.optString("arguments").takeIf { it.isNotEmpty() }?.let {
                        builder.arguments += it
                    }
                }
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
}
