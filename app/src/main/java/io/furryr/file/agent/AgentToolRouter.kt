package io.furryr.file.agent
import io.furryr.file.daemon.DaemonLauncher

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.file.FileSystems
import java.util.concurrent.TimeUnit

// ══════════════════════════════════════════════════════════════════════════════
// Public types
// ══════════════════════════════════════════════════════════════════════════════

/** Result of a tool execution. */
data class ToolResult(
    val success: Boolean,
    /** JSON-encoded result data, or human-readable text output. */
    val data: String? = null,
    /** Error message when [success] is false. */
    val error: String? = null,
    val mimeType: String = "text/plain",
    /** When true the caller must present a confirmation before proceeding. */
    val requiresConfirmation: Boolean = false
)

/** Execution context for the current agent turn. */
data class AgentContext(
    val executionMode: ExecutionMode,
    val currentContainer: String? = null,
    val confirmationPolicy: ConfirmLevel = ConfirmLevel.NONE
)

/** Controls when tool execution pauses to request user confirmation. */
enum class ConfirmLevel {
    /** Execute without confirmation — tools run immediately. */
    NONE,
    /** Only destructive tools (write_file, execute_command) require confirmation. */
    NON_DESTRUCTIVE,
    /** All 6 tools require confirmation before execution. */
    ALL
}

// ══════════════════════════════════════════════════════════════════════════════
// Router
// ══════════════════════════════════════════════════════════════════════════════

/**
 * Central tool dispatcher that routes LLM function calls to the appropriate
 * implementation — Android filesystem, container, or root shell — applying
 * URI resolution and confirmation policies.
 */
object AgentToolRouter {
    private lateinit var androidContext: Context

    /** One-time initialisation with the Android application context. */
    fun configure(context: Context) {
        androidContext = context.applicationContext
    }

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Execute a single tool call from the LLM and return the result.
     *
     * Confirmation policy is checked before execution; when a tool requires
     * confirmation [ToolResult.requiresConfirmation] is set to `true` and the
     * tool body is **not** executed.
     */
    suspend fun execute(toolCall: ToolCallData, context: AgentContext): ToolResult {
        val confirmResult = checkConfirmation(toolCall.name, context.confirmationPolicy)
        if (confirmResult != null) return confirmResult

        return try {
            when (toolCall.name) {
                "read_file" -> handleReadFile(toolCall.arguments)
                "write_file" -> handleWriteFile(toolCall.arguments)
                "list_directory" -> handleListDirectory(toolCall.arguments)
                "execute_command" -> handleExecuteCommand(toolCall.arguments, context)
                "search_files" -> handleSearchFiles(toolCall.arguments)
                "get_file_info" -> handleGetFileInfo(toolCall.arguments)
                else -> ToolResult(success = false, error = "Unknown tool: ${toolCall.name}")
            }
        } catch (e: Exception) {
            ToolResult(success = false, error = e.message ?: "Internal tool error")
        }
    }

    // ── Confirmation policy ─────────────────────────────────────────────

    /**
     * Returns a [ToolResult] requesting confirmation when the tool is
     * blocked by the current [ConfirmLevel], or `null` when execution
     * should proceed.
     */
    internal fun checkConfirmation(toolName: String, policy: ConfirmLevel): ToolResult? {
        return when (policy) {
            ConfirmLevel.NONE -> null
            ConfirmLevel.ALL -> ToolResult(
                success = true,
                data = JSONObject().apply {
                    put("tool", toolName)
                    put("action", "confirm")
                }.toString(),
                requiresConfirmation = true
            )
            ConfirmLevel.NON_DESTRUCTIVE -> {
                if (toolName == "write_file" || toolName == "execute_command") {
                    ToolResult(
                        success = true,
                        data = JSONObject().apply {
                            put("tool", toolName)
                            put("action", "confirm")
                        }.toString(),
                        requiresConfirmation = true
                    )
                } else {
                    null
                }
            }
        }
    }

    // ── URI resolution ──────────────────────────────────────────────────

    /** Resolves a tool-provided URI into its canonical form. */
    private fun resolveUri(uri: String): ResolvedUri {
        return when {
            uri.startsWith("android:///") -> {
                val result = AndroidUriResolver.resolve(uri).getOrElse { e ->
                    throw IllegalArgumentException("Invalid android:/// URI: ${e.message}")
                }
                val path = result.resolvedPath.physicalPath
                ResolvedUri.AndroidFs(path = path, needsRoot = result.needsRoot)
            }
            uri.startsWith("container:///") -> {
                val result = ContainerUriResolver.resolveContainerUri(uri).getOrElse { e ->
                    throw IllegalArgumentException("Invalid container:/// URI: ${e.message}")
                }
                val name = result.containerName
                    ?: throw IllegalArgumentException("Container root URI not supported for tool operations: $uri")
                ResolvedUri.Container(
                    containerName = name,
                    containerPath = result.containerPath
                )
            }
            else -> resolveDirectPath(uri)
        }
    }

    private fun resolveDirectPath(path: String): ResolvedUri.AndroidFs {
        if (!path.startsWith("/")) {
            throw IllegalArgumentException("Raw paths must be absolute or use android:/// / container:/// URIs")
        }
        val androidUri = "android:///$path"
        if (!PathCanonicalizer.isPathAllowed(androidUri)) {
            throw IllegalArgumentException("Path is outside allowed roots: $path")
        }
        val resolved = PathCanonicalizer.resolveAndroidUri(androidUri).getOrElse { e ->
            throw IllegalArgumentException("Invalid path: ${e.message}")
        }
        return ResolvedUri.AndroidFs(path = resolved.physicalPath, needsRoot = resolved.needsRoot)
    }

    // ══════════════════════════════════════════════════════════════════════
    // Tool handlers
    // ══════════════════════════════════════════════════════════════════════

    // ── 1. read_file ─────────────────────────────────────────────────────

    private suspend fun handleReadFile(argumentsJson: String): ToolResult {
        val args = JSONObject(argumentsJson)
        val uri = args.opt("uri")?.toString()
            ?: return ToolResult(success = false, error = "Missing required argument: uri")
        val maxBytes = (args.opt("max_bytes") as? Number)?.toInt() ?: MAX_READ_BYTES

        val resolved = resolveUri(uri)

        return when (resolved) {
            is ResolvedUri.AndroidFs -> {
                if (resolved.needsRoot) {
                    readViaRootShell(resolved.path, maxBytes)
                } else {
                    readDirect(resolved.path, maxBytes)
                }
            }
            is ResolvedUri.Container -> {
                readViaContainer(resolved.containerName, resolved.containerPath, maxBytes)
            }
            is ResolvedUri.Direct -> {
                readDirect(resolved.path, maxBytes)
            }
        }
    }

    // ── 2. write_file ────────────────────────────────────────────────────

    private suspend fun handleWriteFile(argumentsJson: String): ToolResult {
        val args = JSONObject(argumentsJson)
        val uri = args.opt("uri")?.toString()
            ?: return ToolResult(success = false, error = "Missing required argument: uri")
        val content = args.opt("content")?.toString()
            ?: return ToolResult(success = false, error = "Missing required argument: content")
        val overwrite = args.optBoolean("overwrite", false)

        val resolved = resolveUri(uri)

        return when (resolved) {
            is ResolvedUri.AndroidFs -> {
                if (resolved.needsRoot) {
                    writeViaRootShell(resolved.path, content, overwrite)
                } else {
                    writeDirect(resolved.path, content, overwrite)
                }
            }
            is ResolvedUri.Container -> {
                writeViaContainer(resolved.containerName, resolved.containerPath, content, overwrite)
            }
            is ResolvedUri.Direct -> {
                writeDirect(resolved.path, content, overwrite)
            }
        }
    }

    // ── 3. list_directory ────────────────────────────────────────────────

    private suspend fun handleListDirectory(argumentsJson: String): ToolResult {
        val args = JSONObject(argumentsJson)
        val uri = args.opt("uri")?.toString()
            ?: return ToolResult(success = false, error = "Missing required argument: uri")
        val recursive = args.optBoolean("recursive", false)
        val pattern = args.opt("pattern")?.toString()?.takeIf { it.isNotEmpty() }

        val resolved = resolveUri(uri)

        return when (resolved) {
            is ResolvedUri.AndroidFs -> listDirect(resolved.path, recursive, pattern)
            is ResolvedUri.Container ->
                listViaContainer(resolved.containerName, resolved.containerPath, recursive, pattern)
            is ResolvedUri.Direct -> listDirect(resolved.path, recursive, pattern)
        }
    }

    // ── 4. execute_command ───────────────────────────────────────────────

    private suspend fun handleExecuteCommand(argumentsJson: String, context: AgentContext): ToolResult {
        val args = JSONObject(argumentsJson)
        val command = args.opt("command")?.toString()
            ?: return ToolResult(success = false, error = "Missing required argument: command")
        val cwdUri = args.opt("cwd_uri")?.toString()?.takeIf { it.isNotEmpty() }
        val timeoutMs = (args.opt("timeout_ms") as? Number)?.toInt() ?: DEFAULT_TIMEOUT_MS

        // Resolve cwd_uri to a filesystem path if provided
        val cwd: String? = cwdUri?.let { uri ->
            when (val res = resolveUri(uri)) {
                is ResolvedUri.AndroidFs -> res.path
                is ResolvedUri.Direct -> res.path
                is ResolvedUri.Container -> res.containerPath
            }
        }

        return withContext(Dispatchers.IO) {
            executeShellCommand(command, cwd, timeoutMs, context)
        }
    }

    // ── 5. search_files ──────────────────────────────────────────────────

    private suspend fun handleSearchFiles(argumentsJson: String): ToolResult {
        val args = JSONObject(argumentsJson)
        val rootUri = args.opt("root_uri")?.toString()
            ?: return ToolResult(success = false, error = "Missing required argument: root_uri")
        val pattern = args.opt("pattern")?.toString()
            ?: return ToolResult(success = false, error = "Missing required argument: pattern")
        val maxResults = (args.opt("max_results") as? Number)?.toInt() ?: MAX_SEARCH_RESULTS

        val resolved = resolveUri(rootUri)

        return when (resolved) {
            is ResolvedUri.AndroidFs -> searchDirect(resolved.path, pattern, maxResults)
            is ResolvedUri.Container ->
                searchViaContainer(resolved.containerName, resolved.containerPath, pattern, maxResults)
            is ResolvedUri.Direct -> searchDirect(resolved.path, pattern, maxResults)
        }
    }

    // ── 6. get_file_info ─────────────────────────────────────────────────

    private suspend fun handleGetFileInfo(argumentsJson: String): ToolResult {
        val args = JSONObject(argumentsJson)
        val uri = args.opt("uri")?.toString()
            ?: return ToolResult(success = false, error = "Missing required argument: uri")

        val resolved = resolveUri(uri)

        return when (resolved) {
            is ResolvedUri.AndroidFs -> statDirect(resolved.path)
            is ResolvedUri.Container -> statViaContainer(resolved.containerName, resolved.containerPath)
            is ResolvedUri.Direct -> statDirect(resolved.path)
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // I/O primitives (internal for testing)
    // ══════════════════════════════════════════════════════════════════════

    // ── Direct filesystem (app UID) ──────────────────────────────────────

    internal fun readDirect(path: String, maxBytes: Int): ToolResult {
        return runCatching {
            val file = File(path)
            if (!file.exists()) {
                return ToolResult(success = false, error = "File not found: $path")
            }
            if (!file.isFile) {
                return ToolResult(success = false, error = "Not a regular file: $path")
            }
            val content = if (file.length() <= maxBytes) {
                file.readText()
            } else {
                file.inputStream().use { stream ->
                    val buf = ByteArray(maxBytes)
                    val read = stream.read(buf)
                    String(buf, 0, read.coerceAtLeast(0), Charsets.UTF_8)
                }
            }
            val truncated = file.length() > maxBytes
            val json = JSONObject().apply {
                put("path", path)
                put("content", content)
                put("size", file.length())
                put("truncated", truncated)
                if (truncated) put("max_bytes", maxBytes)
            }
            ToolResult(success = true, data = json.toString())
        }.getOrElse { e ->
            ToolResult(success = false, error = "Read error: ${e.message}")
        }
    }

    internal fun writeDirect(path: String, content: String, overwrite: Boolean): ToolResult {
        return runCatching {
            val file = File(path)
            if (file.exists() && !overwrite) {
                return ToolResult(
                    success = false,
                    error = "File already exists and overwrite is false: $path"
                )
            }
            file.parentFile?.mkdirs()
            file.writeText(content)
            val json = JSONObject().apply {
                put("path", path)
                put("bytes_written", content.toByteArray(Charsets.UTF_8).size.toLong())
            }
            ToolResult(success = true, data = json.toString())
        }.getOrElse { e ->
            ToolResult(success = false, error = "Write error: ${e.message}")
        }
    }

    internal fun listDirect(dirPath: String, recursive: Boolean, pattern: String?): ToolResult {
        return runCatching {
            val dir = File(dirPath)
            if (!dir.exists()) {
                return ToolResult(success = false, error = "Directory not found: $dirPath")
            }
            if (!dir.isDirectory) {
                return ToolResult(success = false, error = "Not a directory: $dirPath")
            }

            val entries: Sequence<File> = if (recursive) {
                dir.walkTopDown().filter { it != dir }
            } else {
                dir.listFiles()?.asSequence() ?: emptySequence()
            }

            val filtered = if (pattern != null) {
                val matcher = FileSystems.getDefault().getPathMatcher("glob:$pattern")
                entries.filter { f -> matcher.matches(f.toPath().fileName) }
            } else {
                entries
            }

            val jsonEntries = filtered.map { file ->
                JSONObject().apply {
                    put("name", file.name)
                    put("path", file.absolutePath)
                    put("is_directory", file.isDirectory)
                    put("size", file.length())
                    put("last_modified", file.lastModified())
                }
            }.toList()

            val json = JSONObject().apply {
                put("path", dirPath)
                put("entries", org.json.JSONArray(jsonEntries))
                put("recursive", recursive)
            }
            ToolResult(success = true, data = json.toString())
        }.getOrElse { e ->
            ToolResult(success = false, error = "List error: ${e.message}")
        }
    }

    internal fun statDirect(path: String): ToolResult {
        return runCatching {
            val file = File(path)

            // Use daemon stat for disk usage info (usable/total bytes)
            val daemonStat = DaemonLauncher.getConnection().stat(path)

            val json = JSONObject().apply {
                put("path", path)
                put("exists", file.exists())
                if (file.exists()) {
                    put("size", file.length())
                    put("is_directory", file.isDirectory)
                    put("is_symlink", java.nio.file.Files.isSymbolicLink(file.toPath()))
                    put("last_modified", file.lastModified())
                    put("can_read", file.canRead())
                    put("can_write", file.canWrite())
                    put("can_execute", file.canExecute())
                    if (java.nio.file.Files.isSymbolicLink(file.toPath())) {
                        put("real_path", java.nio.file.Files.readSymbolicLink(file.toPath()).toString())
                    }
                    val mode = (if (file.canRead()) 4 else 0) * 64 +
                        (if (file.canWrite()) 2 else 0) * 64 +
                        (if (file.canExecute()) 1 else 0) * 64
                    put("permissions", mode.toString(8).padStart(3, '0'))
                }
                daemonStat.onSuccess { stat ->
                    put("fs_usable_bytes", stat.usableBytes)
                    put("fs_total_bytes", stat.totalBytes)
                }
            }
            ToolResult(success = true, data = json.toString())
        }.getOrElse { e ->
            // Fallback: return basic info without daemon
            val file = File(path)
            val json = JSONObject().apply {
                put("path", path)
                put("exists", file.exists())
                if (file.exists()) {
                    put("size", file.length())
                    put("is_directory", file.isDirectory)
                    put("last_modified", file.lastModified())
                }
            }
            ToolResult(success = true, data = json.toString())
        }
    }

    internal fun searchDirect(rootPath: String, pattern: String, maxResults: Int): ToolResult {
        return runCatching {
            val root = File(rootPath)
            if (!root.exists()) {
                return ToolResult(success = false, error = "Root directory not found: $rootPath")
            }
            if (!root.isDirectory) {
                return ToolResult(success = false, error = "Not a directory: $rootPath")
            }

            val matcher = FileSystems.getDefault().getPathMatcher("glob:$pattern")
            val matches = root.walkTopDown()
                .filter { f -> f.isFile && matcher.matches(f.toPath().fileName) }
                .take(maxResults)
                .map { file ->
                    JSONObject().apply {
                        put("name", file.name)
                        put("path", file.absolutePath)
                        put("size", file.length())
                        put("last_modified", file.lastModified())
                    }
                }
                .toList()

            val json = JSONObject().apply {
                put("root_path", rootPath)
                put("pattern", pattern)
                put("matches", org.json.JSONArray(matches))
                put("total_found", matches.size)
            }
            ToolResult(success = true, data = json.toString())
        }.getOrElse { e ->
            ToolResult(success = false, error = "Search error: ${e.message}")
        }
    }

    // ── Root-shell operations ────────────────────────────────────────────

    private suspend fun readViaRootShell(path: String, maxBytes: Int): ToolResult {
        val ptyResult = AgentPTYClient.spawnPty(
            command = "/system/bin/sh",
            args = listOf("-c", "cat ${shellQuote(path)}"),
            useRoot = true
        )
        val pty = ptyResult.getOrElse { e ->
            return ToolResult(success = false, error = "Failed to spawn root PTY: ${e.message}")
        }
        return try {
            val output = withContext(Dispatchers.IO) {
                pty.inputStream.bufferedReader().use { reader ->
                    val buf = CharArray(maxBytes.coerceAtMost(1024 * 1024))
                    val len = reader.read(buf, 0, buf.size)
                    if (len > 0) String(buf, 0, len) else ""
                }
            }
            val json = JSONObject().apply {
                put("path", path)
                put("content", output)
                put("size", output.toByteArray(Charsets.UTF_8).size)
            }
            ToolResult(success = true, data = json.toString())
        } catch (e: Exception) {
            ToolResult(success = false, error = "Root read error: ${e.message}")
        } finally {
            runCatching { pty.outputStream.close() }
            runCatching { pty.inputStream.close() }
            AgentPTYClient.closePty(pty.ptyId)
        }
    }

    private suspend fun writeViaRootShell(path: String, content: String, overwrite: Boolean): ToolResult {
        // Check existence first
        val statResult = AgentPTYClient.spawnPty(
            command = "/system/bin/sh",
            args = listOf("-c", "test -f ${shellQuote(path)} && echo EXISTS || echo NOT_FOUND"),
            useRoot = true
        )
        val statPty = statResult.getOrElse { e ->
            return ToolResult(success = false, error = "Failed to spawn root PTY: ${e.message}")
        }
        val status = runCatching {
            statPty.inputStream.bufferedReader().use { it.readLine()?.trim() ?: "" }
        }.getOrDefault("")
        runCatching { statPty.outputStream.close() }
        runCatching { statPty.inputStream.close() }
        AgentPTYClient.closePty(statPty.ptyId)

        if (status == "EXISTS" && !overwrite) {
            return ToolResult(
                success = false,
                error = "File already exists and overwrite is false: $path"
            )
        }

        // Write via base64 to avoid shell escaping issues
        val b64 = java.util.Base64.getEncoder().encodeToString(content.toByteArray(Charsets.UTF_8))
        val shellCmd = "echo ${shellQuote(b64)} | base64 -d > ${shellQuote(path)}"

        val ptyResult = AgentPTYClient.spawnPty(
            command = "/system/bin/sh",
            args = listOf("-c", shellCmd),
            useRoot = true
        )
        val pty = ptyResult.getOrElse { e ->
            return ToolResult(success = false, error = "Failed to spawn root PTY: ${e.message}")
        }
        return try {
            withContext(Dispatchers.IO) {
                val errText = pty.inputStream.bufferedReader().use { it.readText() }
                if (errText.isNotBlank()) {
                    ToolResult(success = false, error = "Root write error: $errText")
                } else {
                    val json = JSONObject().apply {
                        put("path", path)
                        put("bytes_written", content.toByteArray(Charsets.UTF_8).size.toLong())
                    }
                    ToolResult(success = true, data = json.toString())
                }
            }
        } catch (e: Exception) {
            ToolResult(success = false, error = "Root write error: ${e.message}")
        } finally {
            runCatching { pty.outputStream.close() }
            runCatching { pty.inputStream.close() }
            AgentPTYClient.closePty(pty.ptyId)
        }
    }

    // ── Container operations ─────────────────────────────────────────────

    private suspend fun readViaContainer(
        containerName: String, containerPath: String, maxBytes: Int
    ): ToolResult {
        val result = ContainerManager.execInContainer(
            androidContext, containerName,
            "cat ${shellQuote(containerPath)}"
        )
        return result.map { output ->
            val truncated = output.length > maxBytes
            val content = if (truncated) output.take(maxBytes) else output
            val json = JSONObject().apply {
                put("path", containerPath)
                put("container", containerName)
                put("content", content)
                put("size", content.length.toLong())
                put("truncated", truncated)
            }
            ToolResult(success = true, data = json.toString())
        }.getOrElse { e ->
            ToolResult(success = false, error = "Container read error: ${e.message}")
        }
    }

    private suspend fun writeViaContainer(
        containerName: String, containerPath: String, content: String, overwrite: Boolean
    ): ToolResult {
        // Check existence
        val existsResult = ContainerManager.execInContainer(
            androidContext, containerName,
            "test -f ${shellQuote(containerPath)} && echo EXISTS || echo NOT_FOUND"
        )
        val exists = existsResult.getOrElse { e ->
            return ToolResult(success = false, error = "Container check error: ${e.message}")
        }.trim()

        if (exists == "EXISTS" && !overwrite) {
            return ToolResult(
                success = false,
                error = "File already exists and overwrite is false: $containerPath"
            )
        }

        val b64 = java.util.Base64.getEncoder().encodeToString(content.toByteArray(Charsets.UTF_8))
        val cmd = "echo ${shellQuote(b64)} | base64 -d > ${shellQuote(containerPath)}"

        val result = ContainerManager.execInContainer(androidContext, containerName, cmd)
        return result.map {
            val json = JSONObject().apply {
                put("path", containerPath)
                put("container", containerName)
                put("bytes_written", content.toByteArray(Charsets.UTF_8).size.toLong())
            }
            ToolResult(success = true, data = json.toString())
        }.getOrElse { e ->
            ToolResult(success = false, error = "Container write error: ${e.message}")
        }
    }

    private suspend fun listViaContainer(
        containerName: String, containerPath: String, recursive: Boolean, pattern: String?
    ): ToolResult {
        val listCmd = if (recursive) {
            "find ${shellQuote(containerPath)} -maxdepth 1" +
                (pattern?.let { " -name ${shellQuote(it)}" } ?: "")
        } else {
            "ls -la ${shellQuote(containerPath)}" +
                (pattern?.let { " | grep ${shellQuote(it)}" } ?: "")
        }

        val result = ContainerManager.execInContainer(androidContext, containerName, listCmd)
        return result.map { output ->
            val json = JSONObject().apply {
                put("path", containerPath)
                put("container", containerName)
                put("recursive", recursive)
                put("output", output)
            }
            ToolResult(success = true, data = json.toString())
        }.getOrElse { e ->
            ToolResult(success = false, error = "Container list error: ${e.message}")
        }
    }

    private suspend fun searchViaContainer(
        containerName: String, containerPath: String, pattern: String, maxResults: Int
    ): ToolResult {
        val findCmd = "find ${shellQuote(containerPath)} -type f -name ${shellQuote(pattern)} | head -n $maxResults"
        val result = ContainerManager.execInContainer(androidContext, containerName, findCmd)
        return result.map { output ->
            val lines = output.lines().filter { it.isNotBlank() }
            val matches = lines.map { path ->
                JSONObject().apply {
                    put("name", path.substringAfterLast('/'))
                    put("path", path)
                }
            }
            val json = JSONObject().apply {
                put("root_path", containerPath)
                put("container", containerName)
                put("pattern", pattern)
                put("matches", org.json.JSONArray(matches))
                put("total_found", matches.size)
            }
            ToolResult(success = true, data = json.toString())
        }.getOrElse { e ->
            ToolResult(success = false, error = "Container search error: ${e.message}")
        }
    }

    private suspend fun statViaContainer(containerName: String, containerPath: String): ToolResult {
        val statCmd = "stat -c '{\"exists\":true,\"size\":%s,\"is_directory\":%F,\"permissions\":%a,\"last_modified\":%Y}' " +
            "${shellQuote(containerPath)} 2>/dev/null || echo '{\"exists\":false}'"
        val result = ContainerManager.execInContainer(androidContext, containerName, statCmd)
        return result.map { output ->
            ToolResult(success = true, data = output.trim(), mimeType = "application/json")
        }.getOrElse { e ->
            ToolResult(success = false, error = "Container stat error: ${e.message}")
        }
    }

    // ── Command execution ────────────────────────────────────────────────

    internal fun executeShellCommand(
        command: String, cwd: String?, timeoutMs: Int, agentContext: AgentContext
    ): ToolResult {
        return runCatching {
            // Default-initialise vars; every when branch below reassigns them.
            var exitCode = -1
            var output = ""

            when (agentContext.executionMode) {
                ExecutionMode.APP_UID -> {
                    val pb = ProcessBuilder("sh", "-c", command)
                    if (cwd != null) pb.directory(File(cwd))
                    pb.redirectErrorStream(true)
                    val proc = pb.start()
                    output = proc.inputStream.bufferedReader().use { it.readText() }
                    val finished = proc.waitFor(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                    exitCode = if (finished) proc.exitValue() else {
                        proc.destroyForcibly()
                        -1
                    }
                }
                ExecutionMode.CONTAINER -> {
                    val containerName = agentContext.currentContainer
                        ?: return ToolResult(
                            success = false,
                            error = "CONTAINER mode requires currentContainer to be set"
                        )
                    val fullCmd = if (cwd != null) "cd ${shellQuote(cwd)} && $command" else command
                    val execResult: Result<String> = runBlocking {
                        ContainerManager.execInContainer(androidContext, containerName, fullCmd)
                    }
                    execResult.onSuccess { out -> output = out; exitCode = 0 }
                        .onFailure { e -> output = e.message ?: "Container exec failed"; exitCode = 1 }
                }
                ExecutionMode.ROOT_SHELL, ExecutionMode.SHIZUKU -> {
                    val ptyResult: Result<PtyConnection> = runBlocking {
                        AgentPTYClient.spawnPty(
                            command = "/system/bin/sh",
                            args = listOf("-c", command),
                            cwd = cwd,
                            useRoot = true
                        )
                    }
                    val pty = ptyResult.getOrElse { e ->
                        return ToolResult(
                            success = false,
                            error = "Failed to spawn root PTY: ${e.message}"
                        )
                    }
                    try {
                        output = pty.inputStream.bufferedReader().use { it.readText() }
                        exitCode = 0
                    } finally {
                        runCatching { pty.outputStream.close() }
                        runCatching { pty.inputStream.close() }
                        runBlocking { AgentPTYClient.closePty(pty.ptyId) }
                    }
                }
            }

            val json = JSONObject().apply {
                put("command", command)
                put("exit_code", exitCode)
                put("output", output)
                if (cwd != null) put("cwd", cwd)
            }
            ToolResult(
                success = exitCode == 0,
                data = json.toString()
            )
        }.getOrElse { e ->
            ToolResult(success = false, error = "Command execution error: ${e.message}")
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Utilities
    // ══════════════════════════════════════════════════════════════════════

    /** Shell-quote a string for safe command-line usage. */
    internal fun shellQuote(s: String): String {
        if (s.isEmpty()) return "''"
        return "'" + s.replace("'", "'\\''") + "'"
    }

    // ── Constants ────────────────────────────────────────────────────────

    private const val MAX_READ_BYTES = 1048576   // 1 MiB
    private const val DEFAULT_TIMEOUT_MS = 30000
    private const val MAX_SEARCH_RESULTS = 100
}

// ══════════════════════════════════════════════════════════════════════════════
// URI resolution result types
// ══════════════════════════════════════════════════════════════════════════════

/** Canonical form of a tool-provided URI after resolution. */
sealed class ResolvedUri {
    /** Android filesystem path (resolved from `android:///...`). */
    data class AndroidFs(val path: String, val needsRoot: Boolean) : ResolvedUri()

    /** Container-internal path (resolved from `container:///...`). */
    data class Container(val containerName: String, val containerPath: String) : ResolvedUri()

    /** Direct filesystem path (no scheme prefix). */
    data class Direct(val path: String) : ResolvedUri()
}
