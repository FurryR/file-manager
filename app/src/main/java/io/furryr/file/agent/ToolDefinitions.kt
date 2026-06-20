package io.furryr.file.agent

/**
 * A tool definition for LLM function calling (OpenAI/Anthropic/Gemini format).
 *
 * The [parameters] map is a JSON Schema object built with Kotlin standard
 * collections — no external serialization library required.
 */
data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: Map<String, Any?>
) {
    /** Returns this definition as an OpenAI-compatible function-calling map. */
    fun toJsonObject(): Map<String, Any?> = mapOf(
        "type" to "function",
        "function" to mapOf(
            "name" to name,
            "description" to description,
            "parameters" to parameters
        )
    )
}

/** All file-manager tools exposed to the LLM agent. */
object ToolDefinitions {

    /** Returns all 6 tool definitions as OpenAI-compatible JSON Schema maps. */
    fun allToolDefinitions(): List<Map<String, Any?>> = listOf(
        readFile(),
        writeFile(),
        listDirectory(),
        executeCommand(),
        searchFiles(),
        getFileInfo()
    )

    // ------------------------------------------------------------------
    // Individual tool builders
    // ------------------------------------------------------------------

    private fun readFile(): Map<String, Any?> = ToolDefinition(
        name = "read_file",
        description = "Read the contents of a file at the specified URI. " +
            "Returns the file content as a string, optionally truncated to max_bytes. " +
            "Useful for inspecting source files, config files, or logs.",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "uri" to mapOf(
                    "type" to "string",
                    "description" to "File URI to read (e.g., file:///path/to/file)"
                ),
                "max_bytes" to mapOf(
                    "type" to "integer",
                    "description" to "Maximum number of bytes to read. Must not exceed 1048576 (1 MiB).",
                    "maximum" to 1048576
                )
            ),
            "required" to listOf("uri"),
            "additionalProperties" to false
        )
    ).toJsonObject()

    private fun writeFile(): Map<String, Any?> = ToolDefinition(
        name = "write_file",
        description = "Write content to a file at the specified URI. " +
            "Creates the file if it does not exist. By default refuses to overwrite " +
            "an existing file unless overwrite is explicitly set to true.",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "uri" to mapOf(
                    "type" to "string",
                    "description" to "File URI to write (e.g., file:///path/to/file)"
                ),
                "content" to mapOf(
                    "type" to "string",
                    "description" to "Text content to write to the file"
                ),
                "overwrite" to mapOf(
                    "type" to "boolean",
                    "description" to "Whether to overwrite an existing file (default: false)",
                    "default" to false
                )
            ),
            "required" to listOf("uri", "content"),
            "additionalProperties" to false
        )
    ).toJsonObject()

    private fun listDirectory(): Map<String, Any?> = ToolDefinition(
        name = "list_directory",
        description = "List the entries (files and subdirectories) in a directory. " +
            "When recursive is true, includes all descendants. An optional glob pattern " +
            "can be used to filter results (e.g., \"*.kt\", \"*.{jpg,png}\").",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "uri" to mapOf(
                    "type" to "string",
                    "description" to "Directory URI to list (e.g., file:///path/to/dir)"
                ),
                "recursive" to mapOf(
                    "type" to "boolean",
                    "description" to "Whether to list recursively (default: false)",
                    "default" to false
                ),
                "pattern" to mapOf(
                    "type" to "string",
                    "description" to "Glob pattern to filter results (e.g., \"*.kt\")"
                )
            ),
            "required" to listOf("uri"),
            "additionalProperties" to false
        )
    ).toJsonObject()

    private fun executeCommand(): Map<String, Any?> = ToolDefinition(
        name = "execute_command",
        description = "Execute a shell command and return its output. " +
            "The command runs in the specified context: app (default UID), " +
            "container (isolated environment), or root (elevated privileges). " +
            "Use cwd_uri to set the working directory.",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "command" to mapOf(
                    "type" to "string",
                    "description" to "Shell command to execute"
                ),
                "cwd_uri" to mapOf(
                    "type" to "string",
                    "description" to "Working directory URI for the command"
                ),
                "timeout_ms" to mapOf(
                    "type" to "integer",
                    "description" to "Execution timeout in milliseconds (default: 30000)",
                    "default" to 30000
                ),
                "context" to mapOf(
                    "type" to "string",
                    "enum" to listOf("app", "container", "root"),
                    "description" to "Execution context: app (default UID), container (isolated), or root (elevated)",
                    "default" to "app"
                )
            ),
            "required" to listOf("command"),
            "additionalProperties" to false
        )
    ).toJsonObject()

    private fun searchFiles(): Map<String, Any?> = ToolDefinition(
        name = "search_files",
        description = "Search for files matching a glob pattern starting from root_uri. " +
            "Returns up to max_results matches. Useful for locating files by name or type " +
            "without listing entire directory trees.",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "root_uri" to mapOf(
                    "type" to "string",
                    "description" to "Root directory URI to start the search from"
                ),
                "pattern" to mapOf(
                    "type" to "string",
                    "description" to "Glob or regex pattern to match filenames"
                ),
                "max_results" to mapOf(
                    "type" to "integer",
                    "description" to "Maximum number of results to return (default: 100)",
                    "default" to 100
                )
            ),
            "required" to listOf("root_uri", "pattern"),
            "additionalProperties" to false
        )
    ).toJsonObject()

    private fun getFileInfo(): Map<String, Any?> = ToolDefinition(
        name = "get_file_info",
        description = "Retrieve metadata about a file or directory at the specified URI. " +
            "Returns details such as size, last modified time, type (file/dir/symlink), " +
            "and permissions if available.",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "uri" to mapOf(
                    "type" to "string",
                    "description" to "File URI to inspect (e.g., file:///path/to/file)"
                )
            ),
            "required" to listOf("uri"),
            "additionalProperties" to false
        )
    ).toJsonObject()
}
