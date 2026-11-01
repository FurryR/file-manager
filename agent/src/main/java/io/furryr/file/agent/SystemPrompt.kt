package io.furryr.file.agent

import io.furryr.file.daemon.DaemonManager
import io.furryr.file.daemon.DaemonTarget
import io.furryr.file.daemon.HubActions
import io.furryr.file.proto.hub.ReadFileRequest
import org.json.JSONObject

/** Prompt construction. The model sees the same context the old Rust loop built. */
internal object SystemPrompt {
    suspend fun build(target: DaemonTarget, targetName: String, cwd: String): String {
        val distribution = runCatching {
            val response = readFile(
                ReadFileRequest.newBuilder().setPath("/etc/issue").setLength(4096).build(),
                target,
            )
            response.data.toStringUtf8()
                .replace("\r", "").replace("\n", "")
                .replace("\\r", "").replace("\\n", "")
                .trim()
        }.getOrNull().orEmpty().ifEmpty { "Android" }
        val targets = targetSummary(targetName)
        return buildString {
            append("You are the file-manager agent.\n\n")
            append("Current target: ").append(displayTarget(targetName)).append('\n')
            append("Current path: ").append(cwd).append('\n')
            append("Distribution: ").append(distribution).append('\n')
            append("Known targets: ").append(targets).append("\n\n")
            append(
                "File paths are absolute inside the current target, or canonical VFS URIs " +
                    "(file:///..., container://name/..., ftp://..., sftp://..., saf://...) for " +
                    "another location. copy takes a from/to pair. Use list_targets to discover " +
                    "location roots. Read a file before rewriting it. Keep answers concise.",
            )
        }
    }

    private fun targetSummary(current: String): String {
        val live = DaemonManager.liveEndpoints().associateBy { it.name }
        val names = LinkedHashSet<String>()
        names.add("host")
        names.add("app")
        names.addAll(live.keys)
        names.addAll(
            runCatching { HubActions.containerIds?.invoke().orEmpty() }.getOrDefault(emptyList()),
        )
        val ordered = names.filter { it == "host" || it == "app" } +
            names.filterNot { it == "host" || it == "app" }.sorted()
        val entries = ArrayList<String>()
        for (name in ordered) {
            val endpoint = live[name]
            val label = displayTarget(name)
            if (name == "app") {
                entries.add("$label (app, files)")
                continue
            }
            val state = if (endpoint != null) "running" else "stopped"
            val elevated = if (endpoint?.elevated == true) ", elevated" else ""
            val root = when {
                name == "host" -> " file:///"
                name.startsWith("container-") -> " container://${name.removePrefix("container-")}"
                else -> ""
            }
            val marker = if (name == current) "current, " else ""
            entries.add("$label ($marker$state$elevated, exec)$root")
        }
        for (location in runCatching { HubActions.locationTargets?.invoke().orEmpty() }
            .getOrDefault(emptyList()).filter { it.root.isNotBlank() }.distinctBy { it.root }) {
            entries.add("${location.label} (${location.kind}, files) ${location.root}")
        }
        return entries.joinToString(", ")
    }

    private fun displayTarget(name: String): String = name.removePrefix("container-")
}

/**
 * Path policy. Approval is a Kotlin decision made before any side effect; the
 * terminal shell only renders the prompt.
 */
internal object AgentPolicy {
    /**
     * Force a prompt before every exec, even for safe read-only commands.
     * Off by default: [isSafeCommand] auto-approves inspections, and anything
     * that can mutate state still asks.
     */
    @Volatile
    var approveExec: Boolean = false

    fun approvalReason(name: String, args: JSONObject, cwd: String): String? = when (name) {
        "write" -> outsideWorkspace(args.optString("path"), cwd)?.let {
            "write outside the workspace: $it"
        }
        "copy" -> {
            val from = outsideWorkspace(args.optString("from"), cwd)
            val to = outsideWorkspace(args.optString("to"), cwd)
            when {
                from != null && to != null -> "copy outside the workspace: $from -> $to"
                from != null -> "copy source outside the workspace: $from"
                to != null -> "copy destination outside the workspace: $to"
                else -> null
            }
        }
        "exec" -> {
            val command = args.optString("command")
            val requested = args.optString("cwd").takeIf { it.isNotBlank() }
            val outside = requested?.let { outsideWorkspace(it, cwd) }
            when {
                outside != null -> "exec outside the workspace: $outside"
                approveExec || !isSafeCommand(command) -> "exec requires approval: $command"
                else -> null
            }
        }
        else -> null
    }

    /**
     * Read-only commands that cannot change files, spawn other programs or
     * alter the system. Chained and piped commands are safe only when every
     * segment is safe, and shell redirection or command substitution always
     * asks.
     */
    private fun isSafeCommand(command: String): Boolean {
        val segments = splitCommands(command) ?: return false
        return segments.isNotEmpty() && segments.all(::isSafeSegment)
    }

    /** Split on unquoted `|`, `;`, `&&` and `||`, rejecting every other operator. */
    private fun splitCommands(command: String): List<List<String>>? {
        val segments = ArrayList<List<String>>()
        var words = ArrayList<String>()
        val word = StringBuilder()
        var wordStarted = false
        var quote: Char? = null
        var index = 0

        fun flushWord() {
            if (!wordStarted) return
            words.add(word.toString())
            word.setLength(0)
            wordStarted = false
        }

        fun flushSegment(): Boolean {
            flushWord()
            if (words.isEmpty()) return false
            segments.add(words)
            words = ArrayList()
            return true
        }

        while (index < command.length) {
            val char = command[index]
            if (quote != null) {
                if (quote == '\'') {
                    if (char == '\'') quote = null else word.append(char)
                } else {
                    when {
                        char == '"' -> quote = null
                        char == '\\' && index + 1 < command.length -> {
                            word.append(command[index + 1])
                            index++
                        }
                        char == '`' -> return null
                        char == '$' && index + 1 < command.length &&
                            command[index + 1] == '(' -> return null
                        else -> word.append(char)
                    }
                }
                wordStarted = true
                index++
                continue
            }
            when {
                char == '\\' -> {
                    if (index + 1 >= command.length) return null
                    word.append(command[index + 1])
                    wordStarted = true
                    index++
                }
                char == '\'' || char == '"' -> {
                    quote = char
                    wordStarted = true
                }
                char == '`' -> return null
                char == '$' && index + 1 < command.length && command[index + 1] == '(' -> return null
                char in SHELL_REDIRECTIONS -> return null
                char == ';' -> {
                    if (!flushSegment()) return null
                }
                char == '&' -> {
                    if (index + 1 >= command.length || command[index + 1] != '&') return null
                    index++
                    if (!flushSegment()) return null
                }
                char == '|' -> {
                    if (index + 1 < command.length && command[index + 1] == '|') index++
                    if (!flushSegment()) return null
                }
                char.isWhitespace() -> flushWord()
                else -> {
                    word.append(char)
                    wordStarted = true
                }
            }
            index++
        }
        if (quote != null) return null
        if (!flushSegment()) return null
        return segments
    }

    private fun isSafeSegment(tokens: List<String>): Boolean {
        val program = tokens.first()
        // A path can point at a same-named script; only bare PATH lookups.
        if (program.isEmpty() || program.contains('/')) return false
        val args = tokens.drop(1)
        return when (program) {
            in SAFE_COMMANDS -> true
            "date" -> args.none { it == "-s" || it == "--set" || it.startsWith("--set=") }
            "hostname" -> args.isEmpty() || args.all { it in HOSTNAME_READ_FLAGS }
            "file" -> args.none { it == "-C" || it == "--compile" }
            "find" -> args.none { it in FIND_WRITE_FLAGS }
            "sort" -> args.none {
                it == "-o" || it.startsWith("--output") || it.startsWith("--compress-program")
            }
            "tree" -> args.none { it == "-o" || it.startsWith("--output") }
            "diff" -> args.none { it.startsWith("--output") }
            "rg", "grep" -> args.none {
                it == "--pre" || it.startsWith("--pre=") ||
                    it == "--hostname-bin" || it.startsWith("--hostname-bin=")
            }
            "git" -> isSafeGit(args)
            else -> false
        }
    }

    private fun isSafeGit(args: List<String>): Boolean {
        if (args.any {
                it.startsWith("--output") ||
                    it.startsWith("--open-files-in-pager") ||
                    it == "--ext-diff" || it == "--textconv"
            }
        ) {
            return false
        }
        val subcommand = args.firstOrNull() ?: return false
        val rest = args.drop(1)
        return when (subcommand) {
            "--version", "--help", "help", "version",
            "status", "diff", "log", "show", "blame", "describe", "shortlog",
            "whatchanged", "rev-parse", "ls-files", "ls-tree", "cat-file",
            "merge-base", "grep",
            -> true
            "branch" -> rest.all { flag ->
                flag in GIT_BRANCH_READ_FLAGS ||
                    flag.startsWith("--contains") ||
                    flag.startsWith("--merged") ||
                    flag.startsWith("--no-merged") ||
                    flag.startsWith("--points-at") ||
                    flag.startsWith("--sort") ||
                    flag.startsWith("--format")
            }
            "tag" -> rest.isEmpty() || rest.all {
                it == "-l" || it == "--list" || it.startsWith("--sort")
            }
            "remote" -> rest.all { it == "-v" || it == "--verbose" }
            "stash" -> rest.firstOrNull() in setOf("list", "show")
            "config" -> rest.any {
                it == "--get" || it == "--get-all" || it == "--list" || it == "-l" ||
                    it.startsWith("--get-regexp")
            }
            else -> false
        }
    }

    private val SAFE_COMMANDS = setOf(
        "base64", "basename", "bzcat", "cat", "cd", "cksum", "cmp", "comm", "cut",
        "df", "dirname", "du", "echo", "egrep", "fgrep", "free", "getprop",
        "groups", "head", "hexdump", "id", "jq", "ls", "md5sum", "nl",
        "nm", "nproc", "od", "paste", "pgrep", "printenv", "printf", "ps", "pwd",
        "readlink", "realpath", "rev", "sha1sum", "sha256sum", "sha512sum", "stat",
        "strings", "tac", "tail", "tr", "type", "uname", "uptime", "wc", "whereis",
        "which", "whoami", "xxd", "xzcat", "zcat",
    )

    private val HOSTNAME_READ_FLAGS = setOf(
        "-f", "--fqdn", "-s", "--short", "-i", "--ip-address",
        "-I", "--all-ip-addresses", "-d", "--domain",
    )

    private val SHELL_REDIRECTIONS = setOf('>', '<', '\n', '\r')

    private val FIND_WRITE_FLAGS = setOf(
        "-delete", "-exec", "-execdir", "-ok", "-okdir",
        "-fprint", "-fprint0", "-fprintf", "-fls",
    )

    private val GIT_BRANCH_READ_FLAGS = setOf(
        "-a", "--all", "-r", "--remotes", "-v", "-vv", "--verbose",
        "--list", "--show-current",
    )

    /** Return the path when it is outside [cwd], otherwise null. */
    private fun outsideWorkspace(path: String, cwd: String): String? {
        if (path.isBlank()) return null
        if (path.contains("://")) return path
        val normalized = normalize(path)
        val base = normalize(cwd.ifBlank { "/" })
        return when {
            normalized == base -> null
            base == "/" -> null
            normalized.startsWith("$base/") -> null
            else -> normalized
        }
    }

    private fun normalize(path: String): String {
        val absolute = path.startsWith('/')
        val parts = ArrayList<String>()
        for (part in path.split('/')) {
            when (part) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(part)
            }
        }
        return (if (absolute) "/" else "") + parts.joinToString("/")
    }
}
