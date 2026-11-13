package io.furryr.file.core

import java.util.Locale

/**
 * Protocol-aware path with support for full URIs and bare filesystem paths.
 *
 * A path may also carry view levels: everything after a `!` is the address
 * *inside* a file (an archive entry, a virtual document, …) and is invisible to
 * providers. The rule is explicit, never inferred:
 *
 * ```
 * /storage/a.zip!/docs/page.html          one view level
 * /storage/a.zip!                         the root of that view
 * /storage/a.zip!/inner.zip!/x.txt        two levels: zip inside a zip
 * https://host/a.zip!/x.txt               a URL owns the bytes, the view does not
 * ```
 *
 * A literal `!` in a file name is written `!!`, so every address round-trips.
 *
 * Supported formats:
 * - [container://alpine/home/test]  → scheme=container, host=alpine, segments=[home, test]
 * - [ftp://user:pass@192.168.1.1:8080/path/to/file] → scheme=ftp, userInfo=user:pass,
 *   host=192.168.1.1, port=8080, segments=[path, to, file]
 * - [/storage/emulated/0]           → scheme=null, segments=[storage, emulated, 0]
 * - [/]                              → scheme=null, segments=[]
 * - [container:///]                  → scheme=container, host=null, segments=[], isRoot
 *
 * Internal representation uses [List]`<String>` segments.
 * Empty list = root (of the scheme/host context).
 */
class Path private constructor(
    val scheme: String?,
    val userInfo: String?,
    val host: String?,
    val port: Int?,
    private val segments: List<String>,
    /** View levels entered inside the file, outermost first. Provider-blind. */
    val views: List<List<String>> = emptyList(),
) {
    // ── Companion & factory ──────────────────────────────────────────────

    companion object {
        private val ROOT = Path(null, null, null, null, emptyList())

        /** Build an address without formatting and re-parsing a string. */
        fun of(
            scheme: String?,
            userInfo: String? = null,
            host: String? = null,
            port: Int? = null,
            segments: List<String> = emptyList(),
        ): Path = Path(
            scheme?.lowercase(Locale.ROOT), userInfo, host, port,
            segments.filter { it.isNotEmpty() },
        )

        /**
         * Parse a URI string or bare filesystem path into a [Path].
         *
         * Examples:
         * ```
         * Path.parse("container://alpine/home/test")  // scheme=container, host=alpine
         * Path.parse("/storage/emulated/0")            // bare path
         * Path.parse("container:///")                  // scheme root, host=null
         * Path.parse("a.zip!")                         // view root inside a.zip
         * ```
         */
        fun parse(raw: String): Path {
            // `!` is reserved for view levels: the provider only ever sees the
            // text before the first unescaped one. `!!` is a literal `!`.
            val delimiter = firstDelimiter(raw)
            if (delimiter < 0) {
                return parseProviderPath(decode(raw))
            }
            val provider = decode(raw.substring(0, delimiter))
            val viewText = raw.substring(delimiter + 1)
            val views = splitViews(viewText)
            return parseProviderPath(provider).enterViews(views)
        }

        private fun firstDelimiter(text: String): Int {
            var index = 0
            while (index < text.length) {
                if (text[index] == '!') {
                    if (index + 1 < text.length && text[index + 1] == '!') {
                        index += 2
                        continue
                    }
                    return index
                }
                index++
            }
            return -1
        }

        private fun splitViews(text: String): List<List<String>> {
            val levels = mutableListOf<List<String>>()
            var start = 0
            var index = 0
            while (index < text.length) {
                if (text[index] == '!') {
                    if (index + 1 < text.length && text[index + 1] == '!') {
                        index += 2
                        continue
                    }
                    levels += parseSegments(decode(text.substring(start, index)))
                    start = index + 1
                }
                index++
            }
            levels += parseSegments(decode(text.substring(start)))
            return levels
        }

        private fun decode(text: String): String = text.replace("!!", "!")

        private fun encode(text: String): String = text.replace("!", "!!")

        private fun parseProviderPath(raw: String): Path {
            // Absolute paths and provider URIs are exact filesystem names:
            // trailing spaces are significant, and a bare filename may contain
            // "://" without becoming a URI.
            if (raw.startsWith('/')) return parseBarePath(raw)
            val trimmed = if (raw.contains("://")) raw else raw.trim()
            if (trimmed.isEmpty() || trimmed == "/") return ROOT

            val schemeEnd = trimmed.indexOf("://")
            if (schemeEnd < 0) {
                return parseBarePath(trimmed)
            }

            val scheme = trimmed.substring(0, schemeEnd)
                .takeIf { it.isNotEmpty() }
                ?.lowercase(Locale.ROOT)
            val afterScheme = trimmed.substring(schemeEnd + 3)

            if (afterScheme.isEmpty()) {
                return Path(scheme, null, null, null, emptyList())
            }

            val authorityEnd = afterScheme.indexOf('/')
            val authorityPart = if (authorityEnd >= 0) afterScheme.substring(0, authorityEnd) else afterScheme
            val pathPart = if (authorityEnd >= 0) afterScheme.substring(authorityEnd) else "/"

            var userInfo: String? = null
            var host: String? = null
            var port: Int? = null

            if (authorityPart.isNotEmpty()) {
                val atIndex = authorityPart.lastIndexOf('@')
                val hostPort = if (atIndex >= 0) {
                    userInfo = authorityPart.substring(0, atIndex).takeIf { it.isNotEmpty() }
                    authorityPart.substring(atIndex + 1)
                } else {
                    authorityPart
                }

                val bracketEnd = hostPort.lastIndexOf(']')
                val colonIndex = hostPort.lastIndexOf(':')
                val hasPort = if (bracketEnd >= 0) colonIndex > bracketEnd else colonIndex >= 0

                if (hasPort) {
                    host = hostPort.substring(0, colonIndex).takeIf { it.isNotEmpty() }
                    port = hostPort.substring(colonIndex + 1).toIntOrNull()
                } else {
                    host = hostPort.takeIf { it.isNotEmpty() }
                }
            }

            val segs = parseSegments(pathPart)
            return Path(scheme, userInfo, host, port, segs)
        }

        private fun parseBarePath(path: String): Path {
            val segs = parseSegments(path)
            return Path(null, null, null, null, segs)
        }

        private fun parseSegments(part: String): List<String> {
            val normalized = part.trimStart('/')
            if (normalized.isEmpty()) return emptyList()
            return normalized.split('/').filter { it.isNotEmpty() }
        }
    }

    // ── Properties ───────────────────────────────────────────────────────

    /** Root of the current scheme/host context (segments is empty). */
    val isRoot: Boolean get() = segments.isEmpty()

    /** Root path for this scheme/host (drops all segments and views). */
    val root: Path get() = Path(scheme, userInfo, host, port, emptyList())

    /** Last path segment, or host if no segments (for container:// URIs). */
    val name: String? get() = segments.lastOrNull() ?: host

    /** True if this path has no scheme (bare filesystem path). */
    val isBarePath: Boolean get() = scheme == null

    /** Immutable copy of path segments (zero-copy view). */
    val segmentList: List<String> get() = segments

    /** True when the address points inside a file rather than at a provider path. */
    val isViewed: Boolean get() = views.isNotEmpty()

    /** This path plus one more view level; the provider part is untouched. */
    fun enterView(view: List<String>): Path = enterViews(listOf(view))

    /** Enter the root of a view (the `a.zip!` address). */
    fun enterViewRoot(): Path = enterView(emptyList())

    private fun enterViews(extra: List<List<String>>): Path =
        Path(scheme, userInfo, host, port, segments, views + extra)

    /** The provider-visible address, with every view level dropped. */
    fun providerPath(): Path =
        if (views.isEmpty()) this else Path(scheme, userInfo, host, port, segments)

    // ── Navigation ───────────────────────────────────────────────────────

    /**
     * Returns the parent directory path, or null if already at root.
     *
     * ```
     * Path.parse("container://alpine/home/test").parent()  // => container://alpine/home
     * Path.parse("/a/b").parent()                           // => /a
     * Path.parse("/a.zip!/x").parent()                      // => /a.zip!
     * Path.parse("/a.zip!").parent()                        // => /a.zip
     * Path.parse("/").parent()                              // null
     * ```
     */
    fun parent(): Path? {
        if (views.isNotEmpty()) {
            val last = views.last()
            if (last.isEmpty()) {
                // The view root's parent is the backing file, never itself.
                val remaining = views.dropLast(1)
                return Path(scheme, userInfo, host, port, segments, remaining)
            }
            // One level up inside the innermost view; an emptied level stays
            // as that view's root instead of collapsing to the backing file.
            val remaining = views.dropLast(1) + listOf(last.dropLast(1))
            return Path(scheme, userInfo, host, port, segments, remaining)
        }
        if (segments.isEmpty()) return null
        return Path(scheme, userInfo, host, port, segments.dropLast(1))
    }

    /**
     * Appends a child segment to this path.
     *
     * ```
     * Path.parse("/home").child("test")  // => /home/test
     * ```
     */
    fun child(name: String): Path {
        require(name.isNotEmpty()) { "child name must not be empty" }
        require('/' !in name) { "child name must not contain '/': $name" }
        if (views.isEmpty()) {
            return Path(scheme, userInfo, host, port, segments + name)
        }
        val last = views.last() + name
        return Path(scheme, userInfo, host, port, segments, views.dropLast(1) + listOf(last))
    }

    /**
     * Navigate a relative or absolute path from this path.
     *
     * - Absolute URIs (with scheme) are returned as-is.
     * - Relative paths resolve `..` and `.` against current segments.
     *
     * ```
     * Path.parse("/a/b").navigate("../c/d")  // => /a/c/d
     * Path.parse("/a").navigate("container://alpine/")  // => container://alpine/
     * ```
     */
    fun navigate(path: String): Path {
        val target = parse(path)
        if (target.scheme != null || target.host != null || target.isViewed) {
            return target
        }
        // Resolve relative segments
        val resolved = segments.toMutableList()
        for (seg in target.segments) {
            when (seg) {
                ".." -> {
                    if (resolved.isNotEmpty()) resolved.removeAt(resolved.lastIndex)
                    // Leaving a host root goes back to the scheme root (the list
                    // of connections), not to a user-less `scheme://user@/`.
                    else if (host != null) return Path(scheme, null, null, null, resolved)
                }
                "." -> { /* skip */ }
                else -> resolved.add(seg)
            }
        }
        return Path(scheme, userInfo, host, port, resolved, views)
    }

    // ── String representations ──────────────────────────────────────────

    /**
     * Canonical address string. `!` inside a name is escaped as `!!`, and an
     * empty view level is a visible view root (`a.zip!`).
     */
    override fun toString(): String {
        val base = toFullUri()
        if (views.isEmpty()) return base
        return buildString {
            append(base)
            for (view in views) {
                append('!')
                if (view.isNotEmpty()) {
                    append('/')
                    append(view.joinToString("/") { encode(it) })
                }
            }
        }
    }

    /**
     * Full URI with scheme, authority, and path.
     *
     * - Bare path: `"/storage/emulated/0"`
     * - URI: `"container://alpine/home/test"`
     * - Scheme root: `"container:///"`
     */
    fun toFullUri(): String {
        if (scheme == null) return toLocalPath()
        val sb = StringBuilder()
        sb.append(scheme).append("://")
        if (userInfo != null) sb.append(userInfo).append('@')
        if (host != null) sb.append(host)
        if (port != null) sb.append(':').append(port)
        sb.append('/')
        sb.append(segments.joinToString("/") { encode(it) })
        return sb.toString()
    }

    /**
     * Inner path for provider routing — includes user info, host and port as
     * the first segment. Names are raw (a filename's `!` is not escaped here).
     *
     * ```
     * Path.parse("container://alpine/home/test").toInnerPath()  // => "/alpine/home/test"
     * Path.parse("ssh://user@host:2222/home").toInnerPath()     // => "/user@host:2222/home"
     * Path.parse("container:///").toInnerPath()                 // => "/"
     * ```
     */
    fun toInnerPath(): String {
        val sb = StringBuilder().append('/')
        if (userInfo != null) sb.append(userInfo).append('@')
        if (host != null) sb.append(host)
        if (port != null) sb.append(':').append(port)
        if (segments.isNotEmpty()) {
            if (host != null || userInfo != null) sb.append('/')
            sb.append(segments.joinToString("/"))
        }
        return sb.toString()
    }

    /**
     * Local filesystem path — only the segment portion.
     *
     * ```
     * Path.parse("/storage/emulated/0").toLocalPath()  // => "/storage/emulated/0"
     * ```
     */
    fun toLocalPath(): String {
        if (segments.isEmpty()) return "/"
        return "/" + segments.joinToString("/")
    }

    // ── Equality ─────────────────────────────────────────────────────────

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Path) return false
        return scheme == other.scheme && userInfo == other.userInfo &&
            host == other.host && port == other.port &&
            segments == other.segments && views == other.views
    }

    override fun hashCode(): Int {
        var result = scheme.hashCode()
        result = 31 * result + (userInfo?.hashCode() ?: 0)
        result = 31 * result + (host?.hashCode() ?: 0)
        result = 31 * result + (port ?: 0)
        result = 31 * result + segments.hashCode()
        result = 31 * result + views.hashCode()
        return result
    }
}
