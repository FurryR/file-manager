package io.furryr.file.agent

import io.furryr.file.container.ContainerUris
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
                    "location roots. Read a file before rewriting it. If you need to create " +
                    "temporary files, create them under /tmp/agent. Keep answers concise.",
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
                name.startsWith(DaemonManager.CONTAINER_PREFIX) ->
                    " " + ContainerUris.forContainer(DaemonManager.hubDisplayName(name))
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

    private fun displayTarget(name: String): String = DaemonManager.hubDisplayName(name)
}

/**
 * Path policy. Approval is a Kotlin decision made before any side effect; the
 * terminal shell only renders the prompt and never re-evaluates paths.
 *
 * Only access outside the workspace and `/tmp` asks. Commands themselves are
 * not inspected: the tools' `cwd` and file arguments are the addresses the
 * model asked to touch.
 */
internal object AgentPolicy {
    fun approvalReason(
        name: String,
        args: JSONObject,
        cwd: String,
        targetName: String,
    ): String? = when (name) {
        "read" -> outside(args.optString("path"), cwd, targetName)?.let {
            "read outside the workspace: $it"
        }
        "write" -> outside(args.optString("path"), cwd, targetName)?.let {
            "write outside the workspace: $it"
        }
        "glob" -> outside(args.optString("path").ifBlank { cwd }, cwd, targetName)?.let {
            "glob outside the workspace: $it"
        }
        "copy" -> {
            val from = outside(args.optString("from"), cwd, targetName)
            val to = outside(args.optString("to"), cwd, targetName)
            when {
                from != null && to != null -> "copy outside the workspace: $from -> $to"
                from != null -> "copy source outside the workspace: $from"
                to != null -> "copy destination outside the workspace: $to"
                else -> null
            }
        }
        "archive_list", "archive_read" -> outside(args.optString("archive"), cwd, targetName)?.let {
            "archive access outside the workspace: $it"
        }
        "archive_copy" -> {
            val archive = outside(args.optString("archive"), cwd, targetName)
            val to = outside(args.optString("to"), cwd, targetName)
            when {
                archive != null && to != null -> "archive copy outside the workspace: $archive -> $to"
                archive != null -> "archive source outside the workspace: $archive"
                to != null -> "archive destination outside the workspace: $to"
                else -> null
            }
        }
        "exec" -> {
            val requested = args.optString("container_id").takeIf { it.isNotBlank() }
            val outsideCwd = args.optString("cwd").takeIf { it.isNotBlank() }?.let {
                outside(it, cwd, targetName)
            }
            when {
                requested != null && requested != targetName ->
                    "exec in another target: ${DaemonManager.hubDisplayName(requested)}"
                outsideCwd != null -> "exec outside the workspace: $outsideCwd"
                else -> null
            }
        }
        else -> null
    }

    /**
     * Return the path when it is outside [cwd] and `/tmp`, otherwise null.
     * Workspace-local and temporary access never asks; a VFS URI for another
     * location (ftp, saf, a different container) is always outside.
     */
    private fun outside(path: String, cwd: String, targetName: String): String? {
        if (path.isBlank()) return null
        val bare = if (path.contains("://")) {
            // Keep only the path part of a URI. Local scopes (file:///... and
            // container://name/...) can still be inside the workspace or /tmp;
            // every other protocol is a separate domain and always asks.
            val scheme = path.substringBefore("://")
            if (scheme != "file" && scheme != ContainerUris.SCHEME) return path
            val rest = path.substringAfter("://")
            val slash = rest.indexOf('/')
            if (slash < 0) return path
            if (scheme == ContainerUris.SCHEME) {
                val container = ContainerUris.containerName(path) ?: return path
                if (container != DaemonManager.hubDisplayName(targetName)) return path
            }
            rest.substring(slash)
        } else {
            path
        }
        if (!bare.startsWith('/')) return path
        val normalized = normalize(bare)
        val base = normalize(cwd.ifBlank { "/" })
        if (normalized == "/tmp" || normalized.startsWith("/tmp/")) return null
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
