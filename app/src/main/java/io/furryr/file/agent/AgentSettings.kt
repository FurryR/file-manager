package io.furryr.file.agent

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.furryr.file.R
import io.furryr.file.daemon.HubActions
import io.furryr.file.daemon.HubAiConfig
import io.furryr.file.daemon.HubMcpServer
import io.furryr.file.daemon.HubSessionInfo
import io.furryr.file.hub.HubEvents
import java.net.URI
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** User configuration is read on each hub request; credentials never enter shell environments. */
object AgentSettings {
    private const val PREFS = "agent_settings"
    private const val KEY_ALIAS = "io.furryr.file.agent.api_key"
    private const val PROVIDERS = "providers"
    private const val ACTIVE_PROVIDER = "active_provider"
    private const val LAST_MODEL = "last_model"
    private const val LAST_EFFORT = "last_effort"
    private const val TITLE_MODEL = "title_model"
    private const val DEFAULT_EFFORT = "high"
    private const val MCP_SERVERS = "mcp_servers"
    private const val WORKSPACE_MCP = "workspace_mcp_enabled"
    private const val ACTIVE_SESSIONS = "active_sessions"

    data class Provider(
        val id: String,
        val name: String,
        val baseUrl: String,
        val apiKey: String,
    )

    /** A remote (Streamable HTTP) MCP server. Local stdio servers are configured
     * in the workspace `.agent/mcp.json` and started by the agent itself. */
    data class McpServer(
        val id: String,
        val name: String,
        val url: String,
        val headers: Map<String, String>,
        val enabled: Boolean,
    )

    data class SessionInfo(
        val id: String,
        val title: String,
        val updatedAt: Long,
        val messageCount: Int,
    )

    data class SessionMessage(val role: String, val content: String)

    fun install(context: Context) {
        val appContext = context.applicationContext
        HubActions.aiConfigProvider = {
            val provider = activeProvider(appContext)
            check(provider != null && validEndpoint(provider.baseUrl)) {
                appContext.getString(R.string.agent_config_required)
            }
            HubAiConfig(
                baseUrl = provider.baseUrl,
                apiKey = provider.apiKey,
                model = lastModel(appContext),
                effort = lastEffort(appContext),
                provider = provider.name,
                titleModel = titleModel(appContext),
            )
        }
        HubActions.aiSelectionSaver = { model, effort ->
            saveSelection(appContext, model, effort)
        }
        HubActions.sessionLoad = { sessionId -> loadSession(appContext, sessionId) }
        HubActions.sessionSave = { sessionId, messages ->
            saveSession(appContext, sessionId, messages)
        }
        HubActions.sessionTitle = { sessionId -> sessionTitle(appContext, sessionId) }
        HubActions.globalMcpServers = {
            mcpServers(appContext).map { server ->
                HubMcpServer(
                    name = server.name,
                    url = server.url,
                    headers = server.headers,
                    enabled = server.enabled,
                    source = "global",
                )
            }
        }
        HubActions.workspaceMcpEnabled = { workspaceMcpEnabled(appContext) }
        HubActions.sessionList = {
            sessions(appContext).map { session ->
                HubSessionInfo(
                    id = session.id,
                    title = session.title,
                    updatedAt = session.updatedAt,
                    messageCount = session.messageCount,
                )
            }
        }
        HubActions.sessionDelete = { sessionId -> deleteSession(appContext, sessionId) }
        HubActions.sessionRename = { sessionId, title ->
            renameSession(appContext, sessionId, title)
        }
        HubActions.sessionAutoTitle = { sessionId, title ->
            autoTitleSession(appContext, sessionId, title)
        }
        HubActions.activeSession = { terminal -> activeSession(appContext, terminal) }
        HubActions.activeSessionSave = { terminal, sessionId ->
            setActiveSession(appContext, terminal, sessionId)
        }
    }

    fun providers(context: Context): List<Provider> {
        migrate(context)
        val prefs = prefs(context)
        val raw = prefs.getString(PROVIDERS, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id").ifBlank { UUID.randomUUID().toString() }
            Provider(
                id = id,
                name = item.optString("name"),
                baseUrl = item.optString("base_url"),
                apiKey = item.optString("api_key").takeIf { it.isNotEmpty() }
                    ?.let { runCatching { decrypt(it) }.getOrDefault("") }.orEmpty(),
            )
        }
    }

    fun activeProvider(context: Context): Provider? {
        val providers = providers(context)
        if (providers.isEmpty()) return null
        val active = prefs(context).getString(ACTIVE_PROVIDER, null)
        return providers.firstOrNull { it.id == active } ?: providers.first()
    }

    fun saveProvider(context: Context, provider: Provider) {
        require(provider.name.isNotBlank()) { "provider name is required" }
        require(validEndpoint(provider.baseUrl)) { "invalid provider endpoint" }
        val id = provider.id.ifBlank { UUID.randomUUID().toString() }
        val providers = providers(context).toMutableList()
        val index = providers.indexOfFirst { it.id == id }
        val saved = provider.copy(
            id = id,
            name = provider.name.trim(),
            baseUrl = provider.baseUrl.trim().trimEnd('/'),
        )
        if (index >= 0) providers[index] = saved else providers.add(saved)
        writeProviders(context, providers)
        if (prefs(context).getString(ACTIVE_PROVIDER, null).isNullOrBlank()) {
            setActiveProvider(context, id)
        }
    }

    fun deleteProvider(context: Context, id: String) {
        val providers = providers(context).filterNot { it.id == id }
        writeProviders(context, providers)
        if (prefs(context).getString(ACTIVE_PROVIDER, null) == id) {
            val next = providers.firstOrNull()?.id
            prefs(context).edit().apply {
                if (next == null) remove(ACTIVE_PROVIDER) else putString(ACTIVE_PROVIDER, next)
            }.apply()
        }
    }

    fun setActiveProvider(context: Context, id: String) {
        if (providers(context).none { it.id == id }) return
        prefs(context).edit().putString(ACTIVE_PROVIDER, id).apply()
    }

    fun saveSelection(context: Context, model: String, effort: String) {
        prefs(context).edit().apply {
            if (model.isNotBlank()) putString(LAST_MODEL, model.trim())
            if (effort.isNotBlank()) putString(LAST_EFFORT, effort.trim())
        }.apply()
    }

    fun lastModel(context: Context): String =
        prefs(context).getString(LAST_MODEL, "").orEmpty()

    fun lastEffort(context: Context): String =
        prefs(context).getString(LAST_EFFORT, "").orEmpty().ifBlank { DEFAULT_EFFORT }

    fun titleModel(context: Context): String =
        prefs(context).getString(TITLE_MODEL, "").orEmpty()

    fun setTitleModel(context: Context, model: String) {
        prefs(context).edit().putString(TITLE_MODEL, model.trim()).apply()
    }

    fun mcpServers(context: Context): List<McpServer> {
        val raw = prefs(context).getString(MCP_SERVERS, null) ?: return emptyList()
        val decrypted = runCatching { decrypt(raw) }.getOrNull() ?: return emptyList()
        val array = runCatching { JSONArray(decrypted) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val url = item.optString("url")
            // Legacy stdio entries (command only) are dropped: local MCP is
            // configured in the workspace .agent/mcp.json now.
            if (url.isBlank()) return@mapNotNull null
            McpServer(
                id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                name = item.optString("name"),
                url = url,
                headers = item.optJSONObject("headers")?.let { headers ->
                    headers.keys().asSequence().associateWith { headers.optString(it) }
                }.orEmpty(),
                enabled = item.optBoolean("enabled", true),
            )
        }
    }

    fun saveMcpServer(context: Context, server: McpServer) {
        require(server.name.isNotBlank()) { "MCP server name is required" }
        require(validEndpoint(server.url)) { "MCP server URL is required" }
        val id = server.id.ifBlank { UUID.randomUUID().toString() }
        val servers = mcpServers(context).toMutableList()
        val index = servers.indexOfFirst { it.id == id }
        val saved = server.copy(id = id, name = server.name.trim(), url = server.url.trim())
        if (index >= 0) servers[index] = saved else servers.add(saved)
        writeMcpServers(context, servers)
    }

    fun deleteMcpServer(context: Context, id: String) {
        writeMcpServers(context, mcpServers(context).filterNot { it.id == id })
    }

    fun setMcpServerEnabled(context: Context, id: String, enabled: Boolean) {
        val servers = mcpServers(context).map {
            if (it.id == id) it.copy(enabled = enabled) else it
        }
        writeMcpServers(context, servers)
    }

    fun workspaceMcpEnabled(context: Context): Boolean =
        prefs(context).getBoolean(WORKSPACE_MCP, false)

    fun setWorkspaceMcpEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(WORKSPACE_MCP, enabled).apply()
    }

    @Synchronized
    fun loadSession(context: Context, sessionId: String): String? =
        readSessionFile(sessionFile(context, sessionId))?.messages?.toString()

    @Synchronized
    fun saveSession(context: Context, sessionId: String, messages: String): String? =
        runCatching {
            val id = sessionId.ifBlank { "default" }
            val file = sessionFile(context, id)
            val existing = readSessionFile(file)
            val parsed = runCatching { JSONArray(messages) }.getOrElse { JSONArray() }
            val title = if (existing?.titleGenerated == true) {
                existing.title
            } else {
                existing?.title?.takeIf { it.isNotBlank() && it != "New conversation" }
                    ?: titleOf(parsed)
            }
            val wrapper = JSONObject()
                .put("id", id)
                .put("title", title)
                .put("title_generated", existing?.titleGenerated == true)
                .put("updated_at", System.currentTimeMillis())
                .put("messages", parsed)
            file.parentFile?.mkdirs()
            file.writeText(wrapper.toString())
            if (existing == null) {
                HubEvents.publishTitle(id, title)
            }
            title
        }.getOrNull()

    @Synchronized
    fun sessions(context: Context): List<SessionInfo> {
        val directory = File(context.filesDir, "agent/sessions")
        return directory.listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.endsWith(".json") }
            .mapNotNull { file ->
                readSessionFile(file)?.let { session ->
                    SessionInfo(
                        id = session.id,
                        title = session.title,
                        updatedAt = session.updatedAt,
                        messageCount = session.messages.length(),
                    )
                }
            }
            .sortedByDescending { it.updatedAt }
    }

    @Synchronized
    fun deleteSession(context: Context, sessionId: String) {
        runCatching { sessionFile(context, sessionId).delete() }
    }

    @Synchronized
    fun sessionTitle(context: Context, sessionId: String): String? =
        readSessionFile(sessionFile(context, sessionId))?.title

    @Synchronized
    fun autoTitleSession(context: Context, sessionId: String, title: String): String? =
        runCatching {
            val file = sessionFile(context, sessionId)
            val session = readSessionFile(file) ?: return@runCatching null
            if (session.titleGenerated) {
                HubEvents.publishTitle(sessionId, session.title)
                return@runCatching session.title
            }
            val trimmed = title.trim().take(60).ifBlank {
                HubEvents.publishTitle(sessionId, session.title)
                return@runCatching session.title
            }
            file.writeText(
                JSONObject()
                    .put("id", session.id)
                    .put("title", trimmed)
                    .put("title_generated", true)
                    .put("updated_at", session.updatedAt)
                    .put("messages", session.messages)
                    .toString(),
            )
            HubEvents.publishTitle(sessionId, trimmed)
            trimmed
        }.getOrNull()

    @Synchronized
    fun renameSession(context: Context, sessionId: String, title: String) {
        runCatching {
            val file = sessionFile(context, sessionId)
            val session = readSessionFile(file) ?: return@runCatching
            val trimmed = title.trim().ifBlank { session.title }
            file.writeText(
                JSONObject()
                    .put("id", session.id)
                    .put("title", trimmed)
                    .put("title_generated", true)
                    .put("updated_at", session.updatedAt)
                    .put("messages", session.messages)
                    .toString(),
            )
            HubEvents.publishTitle(sessionId, trimmed)
        }
    }

    @Synchronized
    fun sessionMessages(context: Context, sessionId: String): List<SessionMessage> {
        val session = readSessionFile(sessionFile(context, sessionId)) ?: return emptyList()
        return (0 until session.messages.length()).mapNotNull { index ->
            val message = session.messages.optJSONObject(index) ?: return@mapNotNull null
            val content = message.optString("content")
            if (content.isBlank()) return@mapNotNull null
            SessionMessage(message.optString("role").ifBlank { "message" }, content)
        }
    }

    fun activeSession(context: Context, terminal: String): String? =
        activeSessions(context)
            .optString(terminal.ifBlank { "default" })
            .takeIf { it.isNotBlank() }

    fun setActiveSession(context: Context, terminal: String, sessionId: String) {
        val sessions = activeSessions(context)
        val key = terminal.ifBlank { "default" }
        if (sessionId.isBlank()) sessions.remove(key) else sessions.put(key, sessionId)
        prefs(context).edit().putString(ACTIVE_SESSIONS, sessions.toString()).apply()
    }

    private data class SessionData(
        val id: String,
        val title: String,
        val titleGenerated: Boolean,
        val updatedAt: Long,
        val messages: JSONArray,
    )

    private fun activeSessions(context: Context): JSONObject =
        prefs(context).getString(ACTIVE_SESSIONS, null)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: JSONObject()

    private fun readSessionFile(file: File): SessionData? {
        if (!file.isFile) return null
        val text = runCatching { file.readText() }.getOrNull()?.trim().orEmpty()
        if (text.isEmpty()) return null
        val objectRoot = if (text.startsWith("[")) null else runCatching { JSONObject(text) }.getOrNull()
        val messages = if (objectRoot != null) {
            objectRoot.optJSONArray("messages") ?: return null
        } else {
            runCatching { JSONArray(text) }.getOrNull() ?: return null
        }
        val id = objectRoot?.optString("id")?.takeIf { it.isNotBlank() }
            ?: file.nameWithoutExtension
        val title = objectRoot?.optString("title")?.takeIf { it.isNotBlank() }
            ?: titleOf(messages)
        val titleGenerated = objectRoot?.optBoolean("title_generated", false) ?: false
        val updatedAt = objectRoot?.optLong("updated_at")?.takeIf { it > 0 }
            ?: file.lastModified()
        return SessionData(id, title, titleGenerated, updatedAt, messages)
    }

    private fun titleOf(messages: JSONArray): String {
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            if (message.optString("role") != "user") continue
            val line = message.optString("content")
                .lineSequence()
                .map(String::trim)
                .firstOrNull { it.isNotEmpty() }
                ?: continue
            return line.take(60)
        }
        return "New conversation"
    }

    fun validEndpoint(value: String): Boolean = runCatching {
        val uri = URI(value.trim())
        uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null
    }.getOrDefault(false)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun writeProviders(context: Context, providers: List<Provider>) {
        val array = JSONArray()
        for (provider in providers) {
            array.put(
                JSONObject()
                    .put("id", provider.id)
                    .put("name", provider.name)
                    .put("base_url", provider.baseUrl)
                    .put(
                        "api_key",
                        provider.apiKey.takeIf { it.isNotEmpty() }?.let(::encrypt).orEmpty(),
                    ),
            )
        }
        prefs(context).edit().putString(PROVIDERS, array.toString()).apply()
    }

    private fun writeMcpServers(context: Context, servers: List<McpServer>) {
        val array = JSONArray()
        for (server in servers) {
            array.put(
                JSONObject()
                    .put("id", server.id)
                    .put("name", server.name)
                    .put("url", server.url)
                    .put("headers", JSONObject(server.headers))
                    .put("enabled", server.enabled),
            )
        }
        prefs(context).edit()
            .putString(MCP_SERVERS, if (array.length() == 0) "" else encrypt(array.toString()))
            .apply()
    }

    private fun sessionFile(context: Context, sessionId: String): File {
        val safe = sessionId.ifBlank { "default" }.map { char ->
            if (char.isLetterOrDigit() || char == '-' || char == '_') char else '_'
        }.joinToString("")
        return File(context.filesDir, "agent/sessions/$safe.json")
    }

    private fun migrate(context: Context) {
        val prefs = prefs(context)
        if (prefs.contains(PROVIDERS)) return
        val baseUrl = prefs.getString("base_url", "").orEmpty().trim()
        val model = prefs.getString("model", "").orEmpty().trim()
        if (baseUrl.isNotEmpty()) {
            val provider = Provider(
                id = UUID.randomUUID().toString(),
                name = "Default",
                baseUrl = baseUrl,
                apiKey = prefs.getString("api_key_encrypted", null)?.let {
                    runCatching { decrypt(it) }.getOrDefault("")
                }.orEmpty(),
            )
            writeProviders(context, listOf(provider))
            prefs.edit().putString(ACTIVE_PROVIDER, provider.id).apply()
            if (model.isNotEmpty()) prefs.edit().putString(LAST_MODEL, model).apply()
        }
        prefs.edit().remove("base_url").remove("api_key_encrypted").remove("model").apply()
    }

    @Synchronized
    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val encrypted = Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        return "$iv:$encrypted"
    }

    private fun decrypt(value: String): String {
        val (iv, encrypted) = value.split(':', limit = 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        return String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8)
    }
}
