package io.furryr.file.userscript

import java.util.Locale

/**
 * Reads the `// ==UserScript==` block. The body is kept verbatim: header
 * comments are harmless and the exact source makes updates deterministic.
 */
object UserScriptParser {
    private val metadataLine = Regex("""^\s*//\s*@([A-Za-z0-9_-]+)\s*(.*)$""")

    fun parse(source: String, sourceUrl: String): UserScriptDraft {
        val text = source.replace("\r\n", "\n").replace('\r', '\n')
        val header = text.indexOf("==UserScript==")
        require(header >= 0) { "missing ==UserScript== block" }
        val headerEnd = text.indexOf('\n', header)
        require(headerEnd >= 0) { "empty userscript header" }
        val footer = text.indexOf("==/UserScript==", headerEnd)
        require(footer >= 0) { "missing ==/UserScript== block" }
        val block = text.substring(headerEnd + 1, footer)

        var name = ""
        var namespace = ""
        var version = ""
        var description = ""
        var runAt = UserScriptRunAt.DOCUMENT_IDLE
        val matches = mutableListOf<String>()
        val includes = mutableListOf<String>()
        val excludes = mutableListOf<String>()
        val grants = mutableListOf<String>()
        val connects = mutableListOf<String>()
        val requires = mutableListOf<String>()
        val resources = linkedMapOf<String, String>()

        for (line in block.lineSequence()) {
            val match = metadataLine.matchEntire(line.trimEnd()) ?: continue
            val key = match.groupValues[1].lowercase(Locale.ROOT)
            val value = match.groupValues[2].trim()
            if (value.isEmpty()) continue
            when (key) {
                "name" -> if (name.isEmpty()) name = value
                "namespace" -> if (namespace.isEmpty()) namespace = value
                "version" -> if (version.isEmpty()) version = value
                "description" -> if (description.isEmpty()) description = value
                "run-at" -> runAt = UserScriptRunAt.parse(value)
                "match" -> matches += value
                "include" -> includes += value
                "exclude" -> excludes += value
                "grant" -> grants += value
                "connect" -> connects += value
                "require" -> requires += value
                "resource" -> {
                    val parts = value.split(Regex("\\s+"), limit = 2)
                    if (parts.size == 2 && parts[0].isNotEmpty() && parts[1].isNotEmpty()) {
                        resources[parts[0]] = parts[1]
                    }
                }
            }
        }

        require(name.isNotBlank()) { "missing @name" }
        require(matches.isNotEmpty() || includes.isNotEmpty()) {
            "missing @match or @include"
        }
        for (pattern in matches) {
            require(UserScriptPattern.match(pattern) != null) { "invalid @match: $pattern" }
        }
        for (pattern in includes + excludes) {
            require(UserScriptPattern.include(pattern) != null) { "invalid pattern: $pattern" }
        }
        for (url in requires) {
            require(url.isNotBlank()) { "empty @require" }
        }
        for (url in resources.values) {
            require(url.isNotBlank()) { "empty @resource" }
        }

        return UserScriptDraft(
            sourceUrl = sourceUrl.trim(),
            metadata = UserScriptMetadata(
                name = name,
                namespace = namespace,
                version = version,
                description = description,
                runAt = runAt,
                matches = matches,
                includes = includes,
                excludes = excludes,
                grants = grants,
                connects = connects,
                requires = requires,
                resources = resources,
            ),
            body = text,
        )
    }
}
