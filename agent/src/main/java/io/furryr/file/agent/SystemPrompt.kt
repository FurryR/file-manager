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
    /** Ask before running any exec, even inside the workspace. */
    @Volatile
    var approveExec: Boolean = true

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
            val requested = args.optString("cwd").takeIf { it.isNotBlank() }
            val outside = requested?.let { outsideWorkspace(it, cwd) }
            when {
                outside != null -> "exec outside the workspace: $outside"
                approveExec -> "exec requires approval: ${args.optString("command")}"
                else -> null
            }
        }
        else -> null
    }

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
