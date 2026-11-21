package io.furryr.file.userscript

import java.net.URI
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** When a script runs inside a document. */
enum class UserScriptRunAt(val id: String) {
    DOCUMENT_START("document-start"),
    DOCUMENT_END("document-end"),
    DOCUMENT_IDLE("document-idle"),
    ;

    companion object {
        fun parse(value: String?): UserScriptRunAt = entries.firstOrNull {
            it.id == value?.trim()?.lowercase(Locale.ROOT)
        } ?: DOCUMENT_IDLE
    }
}

/** One installed script, metadata and source. */
data class UserScript(
    val id: String,
    val name: String,
    val namespace: String,
    val version: String,
    val description: String,
    val sourceUrl: String,
    val enabled: Boolean,
    val runAt: UserScriptRunAt,
    val matches: List<String>,
    val includes: List<String>,
    val excludes: List<String>,
    val grants: List<String>,
    val connects: List<String>,
    val body: String,
    val installedAt: Long,
    val updatedAt: Long,
) {
    /** `@grant none` opts out of every GM API. */
    val grantsNone: Boolean get() = grants.any { it.equals("none", ignoreCase = true) }
}

/** Metadata parsed from the user-script header. */
data class UserScriptMetadata(
    val name: String,
    val namespace: String,
    val version: String,
    val description: String,
    val runAt: UserScriptRunAt,
    val matches: List<String>,
    val includes: List<String>,
    val excludes: List<String>,
    val grants: List<String>,
    val connects: List<String>,
    val requires: List<String>,
    val resources: Map<String, String>,
)

/** A downloaded script that is not installed yet. */
data class UserScriptDraft(
    val sourceUrl: String,
    val metadata: UserScriptMetadata,
    val body: String,
)

data class UserScriptRequire(
    val url: String,
    val sha256: String,
    val body: String,
)

data class UserScriptResource(
    val name: String,
    val url: String,
    val sha256: String,
    val body: ByteArray,
)

/**
 * A script compiled for matching: the metadata plus the matchers built once
 * when the engine snapshot is reloaded.
 */
class UserScriptSource(
    val meta: UserScript,
    val requires: List<UserScriptRequire>,
    val resources: List<UserScriptResource>,
    private val matchMatchers: List<UserScriptPattern>,
    private val includeMatchers: List<UserScriptPattern>,
    private val excludeMatchers: List<UserScriptPattern>,
) {
    fun accepts(url: String): Boolean {
        val candidate = url.substringBefore('#')
        // Chrome match patterns ignore the query string; include/exclude
        // patterns are written against the whole address and keep it.
        val pathOnly = candidate.substringBefore('?')
        val positive = matchMatchers.any { it.matches(pathOnly) } ||
            includeMatchers.any { it.matches(candidate) }
        if (!positive) return false
        return excludeMatchers.none { it.matches(candidate) || it.matches(pathOnly) }
    }

    /**
     * `@connect` guard for `GM_xmlhttpRequest`. No entry means same origin
     * only; `*` allows everything; a bare host also allows its subdomains.
     */
    fun connectAllowed(target: String, page: String?): Boolean {
        if (meta.connects.any { it.trim() == "*" }) return true
        val uri = runCatching { URI(target) }.getOrNull() ?: return false
        val host = uri.host ?: return false
        val origin = page?.let { runCatching { URI(it) }.getOrNull() }
        if (origin != null &&
            origin.scheme.equals(uri.scheme, ignoreCase = true) &&
            origin.host.equals(host, ignoreCase = true) &&
            portOf(origin) == portOf(uri)
        ) {
            return true
        }
        return meta.connects.any { connectMatches(it, host) }
    }

    private fun connectMatches(entry: String, host: String): Boolean {
        var value = entry.trim().lowercase(Locale.ROOT)
        if (value.isEmpty()) return false
        value = value.substringAfter("://", value)
        value = when {
            value.startsWith("[") -> value.substringAfter('[').substringBefore(']')
            value.count { it == ':' } == 1 -> value.substringBeforeLast(':')
            else -> value
        }
        value = value.removePrefix("*.").removePrefix(".")
        if (value.isEmpty()) return false
        val candidate = host.lowercase(Locale.ROOT)
        return candidate == value || candidate.endsWith(".$value")
    }

    private fun portOf(uri: URI): Int = when {
        uri.port >= 0 -> uri.port
        uri.scheme.equals("http", ignoreCase = true) -> 80
        uri.scheme.equals("https", ignoreCase = true) -> 443
        else -> -1
    }
}

/** One compiled Tampermonkey/Chrome match pattern or include glob. */
class UserScriptPattern private constructor(private val regex: Regex) {
    fun matches(url: String): Boolean = regex.matches(url)

    companion object {
        /** Chrome match pattern: `scheme://host/path`. */
        fun match(raw: String): UserScriptPattern? {
            val value = raw.trim()
            val schemeEnd = value.indexOf("://")
            if (schemeEnd <= 0) return null
            val scheme = value.substring(0, schemeEnd).lowercase(Locale.ROOT)
            if (scheme != "*" && scheme != "http" && scheme != "https") return null
            val afterScheme = value.substring(schemeEnd + 3)
            val slash = afterScheme.indexOf('/')
            if (slash < 0) return null
            val host = afterScheme.substring(0, slash)
            val path = afterScheme.substring(slash)
            if (host.isEmpty() || path.isEmpty()) return null
            val builder = StringBuilder("^")
            when (scheme) {
                "*" -> builder.append("https?")
                else -> builder.append(scheme)
            }
            builder.append("://")
            if (host == "*") {
                builder.append("[^/]+")
            } else {
                if (host.startsWith("*.")) builder.append("(?:[^/:]+\\.)?")
                appendLiteral(builder, host.removePrefix("*."))
                builder.append("(?::\\d+)?")
            }
            appendGlob(builder, path)
            builder.append('$')
            return runCatching {
                UserScriptPattern(Regex(builder.toString(), RegexOption.IGNORE_CASE))
            }.getOrNull()
        }

        /** `@include`/`@exclude`: `/regex/`, or a glob over the whole URL. */
        fun include(raw: String): UserScriptPattern? {
            val value = raw.trim()
            if (value.isEmpty()) return null
            if (value.length > 2 && value.startsWith("/") && value.endsWith("/")) {
                return runCatching {
                    UserScriptPattern(Regex(value.substring(1, value.length - 1)))
                }.getOrNull()
            }
            val builder = StringBuilder("^")
            appendGlob(builder, value)
            builder.append('$')
            return runCatching {
                UserScriptPattern(Regex(builder.toString(), RegexOption.IGNORE_CASE))
            }.getOrNull()
        }

        fun isHttpUrl(raw: String): Boolean {
            val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return false
            return (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) &&
                !uri.host.isNullOrBlank()
        }

        private fun appendGlob(builder: StringBuilder, value: String) {
            for (char in value) {
                if (char == '*') builder.append(".*") else appendLiteral(builder, char.toString())
            }
        }

        private fun appendLiteral(builder: StringBuilder, value: String) {
            for (char in value) {
                if (char in "\\^$.|?*+()[]{}") builder.append('\\')
                builder.append(char)
            }
        }
    }
}

/** JSON body returned by the bridge's `getScripts` call. */
internal fun UserScriptSource.payload(): JSONObject = JSONObject().apply {
    put("id", meta.id)
    put("runAt", meta.runAt.id)
    put("code", meta.body)
    put("requires", JSONArray(requires.map { it.body }))
    put("grantsNone", meta.grantsNone)
    put("info", JSONObject().apply {
        put("script", JSONObject().apply {
            put("name", meta.name)
            put("namespace", meta.namespace)
            put("version", meta.version)
            put("description", meta.description)
            put("matches", JSONArray(meta.matches))
            put("includes", JSONArray(meta.includes))
            put("excludes", JSONArray(meta.excludes))
            put("grant", JSONArray(meta.grants))
            put("connect", JSONArray(meta.connects))
        })
        put("scriptHandler", "File")
        put("version", meta.version)
    })
}
