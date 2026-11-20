package io.furryr.file.lsp

import org.json.JSONObject

/** How far the workspace finder walks after the first marker. */
internal enum class LspRootMode {
    NEAREST,
    OUTERMOST,
}

/**
 * How the server reaches the workspace.
 *
 * EXEC is the default: the server runs in the provider's own execution
 * namespace and reads the workspace directly. MIRROR and VIRTUAL are explicit
 * opt-ins for providers that have no usable execution namespace.
 */
internal enum class LspWorkspaceMode {
    EXEC,
    MIRROR,
    VIRTUAL,
}

/** One language-server launch description. No container or root is stored here. */
internal data class LspServerSpec(
    val id: String,
    val command: List<String>,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val rootMarkers: Set<String> = emptySet(),
    val rootMode: LspRootMode = LspRootMode.NEAREST,
    val workspace: LspWorkspaceMode = LspWorkspaceMode.EXEC,
    val initializationOptions: JSONObject? = null,
    val settings: JSONObject? = null,
    val enabled: Boolean = true,
) {
    /** Executable plus arguments, never a shell string. */
    val argv: List<String> get() = command + args
}

/**
 * One editor language and its server candidates.
 *
 * [lspLanguageIds] maps a file extension to the LSP `languageId` because the
 * editor's language id is not always the protocol id (`.`tsx` is
 * `typescriptreact`, for example).
 */
internal data class LspLanguageSpec(
    val id: String,
    val label: String?,
    val extensions: Set<String>,
    val exactNames: Set<String>,
    val lspLanguageIds: Map<String, String>,
    val markers: Set<String>,
    val servers: List<String>,
)

/**
 * Per-scope override. [prefix] is a VFS address prefix; the longest matching
 * prefix wins. Null fields inherit the language/server.
 */
internal data class LspOverride(
    val prefix: String,
    val enabled: Boolean = true,
    val language: String? = null,
    val command: List<String>? = null,
    val args: List<String>? = null,
    val env: Map<String, String>? = null,
    val markers: Set<String>? = null,
    val workspace: LspWorkspaceMode? = null,
    val rootMode: LspRootMode? = null,
    val initializationOptions: JSONObject? = null,
    val settings: JSONObject? = null,
)

/** One diagnostic, independent of Sora. */
internal data class LspDiagnostic(
    val startLine: Int,
    val startColumn: Int,
    val endLine: Int,
    val endColumn: Int,
    val severity: Int,
    val message: String,
)
