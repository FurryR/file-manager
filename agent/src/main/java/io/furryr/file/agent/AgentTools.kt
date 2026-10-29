package io.furryr.file.agent

import io.furryr.file.core.model.FileEntry
import io.furryr.file.daemon.DaemonManager
import io.furryr.file.daemon.DaemonTarget
import io.furryr.file.daemon.process.CommandSpec
import io.furryr.file.proto.agent.AgentEvent
import io.furryr.file.proto.agent.AgentToolNotice
import io.furryr.file.proto.agent.AgentToolWrite
import io.furryr.file.proto.hub.CopyFileRequest
import io.furryr.file.proto.hub.GlobRequest
import io.furryr.file.proto.hub.ListDirectoryRequest
import io.furryr.file.proto.hub.ReadFileRequest
import io.furryr.file.proto.hub.WriteFileRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream

internal const val MAX_TOOL_OUTPUT = 128 * 1024
private const val MAX_EXEC_CAPTURE = 1024 * 1024
private const val MAX_LIVE_OUTPUT = 256 * 1024
private const val MAX_WRITE_PREVIEW = 256 * 1024
private const val MAX_DIFF_LINES = 1_500
private const val EXEC_TIMEOUT_MS = 600_000L
private const val OUTPUT_DRAIN_MS = 2_000L

internal data class ToolResult(val ok: Boolean, val content: String)

internal data class ToolInfo(
    val detail: String,
    val description: String? = null,
    val command: String? = null,
    val arrow: String = "←",
)

internal data class ToolContext(
    val target: DaemonTarget,
    val targetName: String,
    val cwd: String,
    val terminalId: String,
    val emit: suspend (AgentEvent) -> Unit,
)

internal object AgentTools {
    val definitions: JSONArray = JSONArray().apply {
        put(readDefinition())
        put(writeDefinition())
        put(copyDefinition())
        put(globDefinition())
        put(execDefinition())
        put(listTargetsDefinition())
    }

    fun info(name: String, arguments: String): ToolInfo {
        val args = runCatching { JSONObject(arguments.ifBlank { "{}" }) }.getOrNull()
            ?: return simpleInfo(arguments)
        return when (name) {
            "read" -> simpleInfo(args.optString("path", "?"), "→")
            "write" -> simpleInfo(args.optString("path", "?"), "←")
            "glob" -> simpleInfo(args.optString("pattern", "?"), "→")
            "copy" -> simpleInfo("${args.optString("from", "?")} -> ${args.optString("to", "?")}")
            "exec" -> {
                val command = args.optString("command", "?")
                val container = args.optString("container_id").takeIf { it.isNotBlank() }
                val detail = if (container != null) "$command @${displayTarget(container)}" else command
                ToolInfo(
                    detail = detail,
                    description = args.optString("description").trim().takeIf { it.isNotEmpty() },
                    command = command,
                    arrow = "←",
                )
            }
            "list_targets" -> ToolInfo("", arrow = "→")
            else -> simpleInfo(arguments)
        }
    }

    suspend fun execute(
        name: String,
        arguments: String,
        context: ToolContext,
    ): ToolResult {
        val args = runCatching {
            JSONObject(arguments.ifBlank { "{}" })
        }.getOrElse { return ToolResult(false, "invalid tool arguments: ${it.message}") }
        return try {
            val content = when (name) {
                "read" -> read(args, context)
                "write" -> write(args, context)
                "copy" -> copy(args, context)
                "glob" -> glob(args, context)
                "exec" -> exec(args, context)
                "list_targets" -> listTargets(context)
                else -> error("unknown tool '$name'")
            }
            ToolResult(true, bound(content))
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            ToolResult(false, error.message ?: "tool failed")
        }
    }

    private fun simpleInfo(value: String, arrow: String = "←"): ToolInfo =
        ToolInfo(value.replace('\n', ' ').take(120), arrow = arrow)

    private suspend fun read(args: JSONObject, context: ToolContext): String {
        val path = stringArg(args, "path")
        val offset = args.optLong("offset", 0L).coerceAtLeast(0L)
        val length = args.optInt("length", 0).coerceAtLeast(0).toLong()
        val directory = runCatching {
            listDirectory(
                ListDirectoryRequest.newBuilder().setPath(path).build(),
                context.target,
            )
        }.getOrNull()
        if (directory != null) return renderDirectory(path, directory)
        val response = readFile(
            ReadFileRequest.newBuilder()
                .setPath(path).setOffset(offset).setLength(length.toInt()).build(),
            context.target,
        )
        if (response.data.isEmpty()) {
            return "(empty at offset $offset; file size ${response.size} bytes)"
        }
        var text = response.data.toStringUtf8()
        if (!response.eof) {
            text += "\n\n[truncated at byte ${offset + response.data.size()} of ${response.size}]"
        }
        return text
    }

    private fun renderDirectory(
        path: String,
        directory: io.furryr.file.proto.hub.ListDirectoryResponse,
    ): String = buildString {
        append(path).append('\n')
        if (directory.entriesCount == 0) {
            append("(empty directory)\n")
        } else {
            for (entry in directory.entriesList) {
                val kind = if (entry.directory) "dir " else "file"
                append("  ").append(kind).append(' ').append(entry.name)
                    .append("  (").append(entry.size).append(" bytes)\n")
            }
        }
        if (directory.truncated) append("[listing truncated]\n")
    }

    private suspend fun write(args: JSONObject, context: ToolContext): String {
        val path = stringArg(args, "path")
        val content = args.optString("content", null)
            ?: error("missing 'content'")
        val old = runCatching {
            readFile(
                ReadFileRequest.newBuilder().setPath(path).setLength(MAX_WRITE_PREVIEW).build(),
                context.target,
            )
        }.getOrNull()?.takeIf { it.eof }?.data?.toStringUtf8()
        val diff = old?.let { renderDiff(it, content) }
        runCatching {
            context.emit(
                AgentEvent.newBuilder().setToolWrite(
                    AgentToolWrite.newBuilder()
                        .setPath(path).setContent(content).setDiff(diff.orEmpty()).build(),
                ).build(),
            )
        }
        val written = writeFile(
            WriteFileRequest.newBuilder().setPath(path).setData(
                com.google.protobuf.ByteString.copyFromUtf8(content),
            ).build(),
            context.target,
        )
        return "Wrote ${written.bytesWritten} bytes to $path"
    }

    private suspend fun copy(args: JSONObject, context: ToolContext): String {
        val from = stringArg(args, "from")
        val to = stringArg(args, "to")
        val response = copyFile(
            CopyFileRequest.newBuilder().setFrom(from).setTo(to).build(),
            context.target,
        )
        return "Copied ${response.bytesCopied} bytes from $from to $to"
    }

    private suspend fun glob(args: JSONObject, context: ToolContext): String {
        val pattern = stringArg(args, "pattern")
        val path = args.optString("path").takeIf { it.isNotBlank() } ?: context.cwd
        val maxResults = args.optInt("max_results", 200).coerceAtLeast(1)
        val response = glob(
            GlobRequest.newBuilder()
                .setPattern(pattern).setPath(path).setMaxResults(maxResults).build(),
            context.target,
        )
        if (response.pathsCount == 0) {
            return "(no matches for $pattern under $path)"
        }
        return buildString {
            append(response.pathsList.joinToString("\n"))
            if (response.truncated) append("\n[results truncated]")
        }
    }

    private suspend fun exec(args: JSONObject, context: ToolContext): String {
        val command = stringArg(args, "command")
        val cwd = args.optString("cwd").takeIf { it.isNotBlank() }
        val requested = args.optString("container_id").takeIf { it.isNotBlank() }
        val target = requested?.let(::daemonTarget) ?: context.target
        val channel = DaemonManager.execService(target).spawnPipes(
            CommandSpec(
                command = "sh",
                args = listOf("-c", command),
                cwd = cwd,
                env = executionEnvironment(target, context.terminalId),
            ),
        ).getOrThrow()
        val scope = CoroutineScope(Dispatchers.IO + Job())
        var stdout = ByteArrayOutputStream()
        var stderr = ByteArrayOutputStream()
        var streamed = 0
        suspend fun emitOutput(bytes: ByteArray) {
            if (streamed >= MAX_LIVE_OUTPUT) return
            val take = minOf(bytes.size, MAX_LIVE_OUTPUT - streamed)
            streamed += take
            context.emit(
                AgentEvent.newBuilder()
                    .setToolOutput(com.google.protobuf.ByteString.copyFrom(bytes, 0, take))
                    .build(),
            )
        }
        val outJob = scope.launch {
            stdout = drain(channel.stdout, ::emitOutput)
        }
        val errJob = scope.launch {
            stderr = drain(channel.stderr, ::emitOutput)
        }
        try {
            val waited = withTimeoutOrNull(EXEC_TIMEOUT_MS) { channel.waitFor() }
            if (waited == null) {
                runCatching { channel.closeProcess() }
                runCatching { channel.close() }
                scope.coroutineContext[Job]?.cancelAndJoin()
                error("exec timed out after ${EXEC_TIMEOUT_MS / 1000}s; the process group was terminated")
            }
            val exitCode = waited.getOrThrow()
            var truncated = false
            if (withTimeoutOrNull(OUTPUT_DRAIN_MS) { outJob.join() } == null) {
                truncated = true
                outJob.cancel()
            }
            if (withTimeoutOrNull(OUTPUT_DRAIN_MS) { errJob.join() } == null) {
                truncated = true
                errJob.cancel()
            }
            val out = stdout.toString(Charsets.UTF_8)
            val err = stderr.toString(Charsets.UTF_8)
            return buildString {
            append("exit_code: ").append(exitCode).append('\n')
            if (out.isNotEmpty()) {
                append("stdout:\n")
                pushBounded(this, out)
            }
            if (err.isNotEmpty()) {
                append("stderr:\n")
                pushBounded(this, err)
            }
            if (out.isEmpty() && err.isEmpty()) {
                append(
                    if (truncated) "(output discarded: background processes still hold the exec pipes)\n"
                    else "(no output)\n",
                )
            } else if (truncated) {
                append("[output truncated: background processes still hold the exec pipes]\n")
            }
            }
        } finally {
            runCatching { channel.close() }
            runCatching { channel.closeProcess() }
            scope.coroutineContext[Job]?.cancel()
        }
    }

    private suspend fun drain(
        input: InputStream,
        emit: suspend (ByteArray) -> Unit,
    ): ByteArrayOutputStream = withContext(Dispatchers.IO) {
        val collected = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val read = try {
                input.read(buffer)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: java.io.IOException) {
                break
            }
            if (read <= 0) break
            if (collected.size() < MAX_EXEC_CAPTURE) {
                collected.write(buffer, 0, minOf(read, MAX_EXEC_CAPTURE - collected.size()))
            }
            emit(buffer.copyOf(read))
        }
        collected
    }

    private suspend fun listTargets(context: ToolContext): String {
        val live = DaemonManager.liveEndpoints().associateBy { it.name }
        val names = LinkedHashSet<String>()
        names.add("host")
        names.add("app")
        names.addAll(live.keys)
        names.addAll(runCatching { io.furryr.file.daemon.HubActions.containerIds?.invoke().orEmpty() }
            .getOrDefault(emptyList()))
        val ordered = names.filter { it == "host" || it == "app" } +
            names.filterNot { it == "host" || it == "app" }.sorted()
        val lines = ArrayList<String>()
        for (name in ordered) {
            val endpoint = live[name]
            val state = if (endpoint != null) "running" else "stopped"
            val elevated = if (endpoint?.elevated == true) ", elevated" else ""
            val current = if (name == context.targetName) "current, " else ""
            val root = when {
                name == "host" -> " file:///"
                name.startsWith("container-") -> " container://${name.removePrefix("container-")}"
                else -> ""
            }
            val label = displayTarget(name)
            lines.add("$label ($current$state$elevated, exec)$root")
        }
        val locations = runCatching {
            io.furryr.file.daemon.HubActions.locationTargets?.invoke().orEmpty()
        }.getOrDefault(emptyList()).filter { it.root.isNotBlank() }.distinctBy { it.root }
        for (location in locations) {
            lines.add("${location.label} (${location.kind}, files) ${location.root}")
        }
        return if (lines.isEmpty()) "(no targets)" else lines.joinToString("\n")
    }

    private fun executionEnvironment(target: DaemonTarget, terminalId: String): Map<String, String> {
        val name = DaemonManager.hubSocketName ?: return emptyMap()
        val key = runCatching { DaemonManager.hubKey(target) }.getOrNull() ?: return emptyMap()
        return mapOf("HUB_SOCKET" to "$name@$key", "HUB_TERMINAL" to terminalId)
    }

    private fun daemonTarget(name: String): DaemonTarget = when (name) {
        "host" -> DaemonTarget.Host
        "app" -> DaemonTarget.App
        else -> DaemonTarget.Named(name.removePrefix("container-"))
    }

    private fun displayTarget(name: String): String = name.removePrefix("container-")

    private fun stringArg(args: JSONObject, name: String): String =
        args.optString(name).takeIf { it.isNotEmpty() } ?: error("missing '$name'")

    internal fun bound(text: String): String =
        if (text.length <= MAX_TOOL_OUTPUT) text
        else text.take(MAX_TOOL_OUTPUT) + "\n[tool output truncated]"

    private fun pushBounded(output: StringBuilder, text: String) {
        if (text.length > MAX_TOOL_OUTPUT) {
            output.append(text, 0, MAX_TOOL_OUTPUT)
            output.append("\n[output truncated]")
        } else {
            output.append(text)
        }
        if (!output.endsWith('\n')) output.append('\n')
    }

    /** Compact line diff with three lines of context. */
    internal fun renderDiff(old: String, new: String): String {
        val oldLines = old.lines()
        val newLines = new.lines()
        if (oldLines.size > MAX_DIFF_LINES || newLines.size > MAX_DIFF_LINES) {
            return buildString {
                newLines.forEach { append('+').append(it).append('\n') }
                append("[diff omitted: file is too large]\n")
            }
        }
        if (oldLines == newLines) return ""
        val rows = oldLines.size
        val columns = newLines.size
        val lcs = Array(rows + 1) { IntArray(columns + 1) }
        for (row in rows - 1 downTo 0) {
            for (column in columns - 1 downTo 0) {
                lcs[row][column] = if (oldLines[row] == newLines[column]) {
                    lcs[row + 1][column + 1] + 1
                } else {
                    maxOf(lcs[row + 1][column], lcs[row][column + 1])
                }
            }
        }
        val ops = ArrayList<Pair<Char, String>>(rows + columns)
        var row = 0
        var column = 0
        while (row < rows || column < columns) {
            if (row < rows && column < columns && oldLines[row] == newLines[column]) {
                ops.add(' ' to oldLines[row]); row++; column++
            } else if (column < columns && (row == rows || lcs[row][column + 1] >= lcs[row + 1][column])) {
                ops.add('+' to newLines[column]); column++
            } else {
                ops.add('-' to oldLines[row]); row++
            }
        }
        val keep = BooleanArray(ops.size)
        for ((index, op) in ops.withIndex()) {
            if (op.first == ' ') continue
            val start = (index - 3).coerceAtLeast(0)
            val end = (index + 4).coerceAtMost(ops.size)
            for (i in start until end) keep[i] = true
        }
        return buildString {
            var previous = false
            for ((index, op) in ops.withIndex()) {
                if (!keep[index]) {
                    previous = false
                    continue
                }
                if (!previous && index > 0) append("...\n")
                append(op.first).append(op.second).append('\n')
                previous = true
            }
        }
    }

    private fun readDefinition() = function(
        "read",
        "Read a file. The path resolves inside the current target, or may be a canonical VFS URI for another location.",
        JSONObject()
            .put("path", stringProp("Absolute path in the current target, or a VFS URI such as container://name/path or saf://id/path."))
            .put("offset", intProp("Byte offset to start reading from. Defaults to 0."))
            .put("length", intProp("Maximum bytes to read. Defaults to 64 KiB.")),
        listOf("path"),
    )

    private fun writeDefinition() = function(
        "write",
        "Create or overwrite a file. The path resolves inside the current target, or may be a canonical VFS URI for another location.",
        JSONObject()
            .put("path", stringProp("Absolute path in the current target, or a VFS URI such as container://name/path or saf://id/path."))
            .put("content", stringProp("Full file content.")),
        listOf("path", "content"),
    )

    private fun copyDefinition() = function(
        "copy",
        "Copy a file or directory from one location to another. Both paths may be absolute paths in the current target or canonical VFS URIs. The destination is the exact new path; its parent directory must already exist.",
        JSONObject()
            .put("from", stringProp("Source file or directory: an absolute path in the current target, or a VFS URI."))
            .put("to", stringProp("Exact destination path, or a VFS URI. Its parent directory must exist.")),
        listOf("from", "to"),
    )

    private fun globDefinition() = function(
        "glob",
        "Find files and directories matching a glob pattern. The base path resolves inside the current target or may be a VFS URI.",
        JSONObject()
            .put("pattern", stringProp("Glob such as **/*.kt, matched against paths relative to path. **/ also matches at the base level itself."))
            .put("path", stringProp("Absolute directory in the current target, or a VFS URI. Defaults to the current directory."))
            .put("max_results", intProp("Maximum number of matches. Defaults to 200.")),
        listOf("pattern"),
    )

    private fun execDefinition() = function(
        "exec",
        "Run a shell command in a container and return its exit code, stdout and stderr.",
        JSONObject()
            .put("command", stringProp("Shell command to run with the target shell."))
            .put("cwd", stringProp("Absolute working directory. Defaults to the target daemon's cwd."))
            .put("container_id", stringProp("Target name: host, app, or a container name. Defaults to the current container when omitted.")),
        listOf("command"),
    )

    private fun listTargetsDefinition() = function(
        "list_targets",
        "List every execution target (host, app, containers) and every registered file location. Each entry shows its root URI when it has one, ready to use as a copy/read/write/glob path prefix.",
        JSONObject(),
        emptyList(),
    )

    private fun function(
        name: String,
        description: String,
        properties: JSONObject,
        required: List<String>,
    ) = JSONObject().apply {
        put("type", "function")
        put(
            "function",
            JSONObject()
                .put("name", name)
                .put("description", description)
                .put(
                    "parameters",
                    JSONObject()
                        .put("type", "object")
                        .put("properties", properties)
                        .put("required", JSONArray(required)),
                ),
        )
    }

    private fun stringProp(description: String) =
        JSONObject().put("type", "string").put("description", description)

    private fun intProp(description: String) =
        JSONObject().put("type", "integer").put("description", description)
}
