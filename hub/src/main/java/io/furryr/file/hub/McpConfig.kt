package io.furryr.file.hub

import io.furryr.file.core.Path
import io.furryr.file.core.Vfs
import io.furryr.file.core.source.OpenMode
import io.furryr.file.daemon.DaemonTarget
import io.furryr.file.daemon.HubActions
import io.furryr.file.daemon.HubMcpServer
import org.json.JSONArray
import org.json.JSONObject

/**
 * Resolve the MCP server catalog for one caller. Global servers come from app
 * settings; workspace servers are read from `.agent/mcp.json` in the caller cwd
 * and its ancestors when workspace loading is trusted. Closer workspace entries
 * override ancestors, which override global entries with the same name.
 */
internal object McpConfig {
    private const val MAX_CONFIG_BYTES = 1024 * 1024
    private const val CHUNK = 64 * 1024

    suspend fun servers(target: DaemonTarget, cwd: String): List<HubMcpServer> {
        val merged = LinkedHashMap<String, HubMcpServer>()
        runCatching { HubActions.globalMcpServers?.invoke().orEmpty() }
            .getOrDefault(emptyList())
            .forEach { merged[it.name] = it }
        if (HubActions.workspaceMcpEnabled?.invoke() == true) {
            val chain = ancestorChain(target, cwd)
            for (directory in chain.asReversed()) {
                for (server in readWorkspace(directory, merged)) {
                    merged[server.name] = server
                }
            }
        }
        return merged.values.toList()
    }

    private fun ancestorChain(target: DaemonTarget, cwd: String): List<Path> {
        val chain = ArrayList<Path>()
        var current = Path.parse(callerPath(target, cwd.ifBlank { "/" }, "workspace"))
        while (true) {
            chain.add(current)
            if (current.isRoot) break
            current = current.parent() ?: break
        }
        return chain
    }

    private suspend fun readWorkspace(
        directory: Path,
        known: Map<String, HubMcpServer>,
    ): List<HubMcpServer> {
        val path = directory.child(".agent").child("mcp.json").toString()
        val text = runCatching { readText(path) }.getOrNull() ?: return emptyList()
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
        val servers = root.optJSONObject("mcpServers") ?: return emptyList()
        val result = ArrayList<HubMcpServer>()
        for (name in servers.keys()) {
            val entry = servers.optJSONObject(name) ?: continue
            val base = known[name]
            val url = entry.optString("url")
            val command = entry.optString("command")
            if (url.isBlank() && command.isBlank()) {
                if (base != null) {
                    result.add(
                        base.copy(
                            enabled = if (entry.has("enabled")) entry.optBoolean("enabled") else base.enabled,
                            source = "workspace",
                        ),
                    )
                }
                continue
            }
            val args = entry.optJSONArray("args")?.let { array ->
                (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotEmpty) }
            }.orEmpty()
            val env = entry.optJSONObject("env")?.let { objectValue ->
                objectValue.keys().asSequence().associateWith { objectValue.optString(it) }
            }.orEmpty()
            val headers = entry.optJSONObject("headers")?.let { objectValue ->
                objectValue.keys().asSequence().associateWith { objectValue.optString(it) }
            }.orEmpty()
            val transport = entry.optString("type")
                .ifBlank { entry.optString("transport") }
                .ifBlank { if (url.isNotBlank()) "http" else "stdio" }
            result.add(
                HubMcpServer(
                    name = name,
                    url = url,
                    headers = headers,
                    command = command,
                    args = args,
                    env = env,
                    cwd = entry.optString("cwd"),
                    enabled = entry.optBoolean("enabled", true),
                    source = "workspace",
                    transport = transport,
                ),
            )
        }
        return result
    }

    private suspend fun readText(path: String): String {
        val source = Vfs.file(path).getOrThrow().open(OpenMode.READ).getOrThrow()
        return source.use {
            val output = StringBuilder()
            var offset = 0L
            while (output.length < MAX_CONFIG_BYTES) {
                val data = source.readChunk(offset, CHUNK)
                if (data.isEmpty()) break
                output.append(String(data, Charsets.UTF_8))
                offset += data.size
            }
            output.toString()
        }
    }
}
