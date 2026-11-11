package io.furryr.file.core

/**
 * Protocol-aware path with support for full URIs and bare filesystem paths.
 *
 * A path may also carry view levels: everything after a `!` is the address
 * *inside* a file (an archive entry, a virtual document, …) and is invisible to
 * providers. The rule is explicit, never inferred:
 *
 * ```
 * /storage/a.zip!/docs/page.html          one view level
 * /storage/a.zip!/inner.zip!/x.txt        two levels: zip inside a zip
 * https://host/a.zip!/x.txt               a URL owns the bytes, the view does not
 * ```
 *
 * Every provider-facing accessor ([toFullUri], [toInnerPath], [toLocalPath],
 * [segmentList], [parent], [child], [name], [navigate]) sees only the part
 * before the first `!`; only [views] and [toString] know about the rest.
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

        /**
         * Parse a URI string or bare filesystem path into a [Path].
         *
         * Examples:
         * ```
         * Path.parse("container://alpine/home/test")  // scheme=container, host=alpine
         * Path.parse("/storage/emulated/0")            // bare path
         * Path.parse("container:///")                  // scheme root, host=null
         * ```
         */
        fun parse(raw: String): Path {
            // `!` is reserved for view levels: the provider only ever sees the
            // text before the first one.
            val bang = raw.indexOf('!')
            if (bang >= 0) {
                val views = raw.substring(bang + 1)
                    .split('!')
                    .map(::parseSegments)
                    .filter { it.isNotEmpty() }
                return parse(raw.substring(0, bang)).enterViews(views)
            }
            return parseProviderPath(raw)
        }

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

            val scheme = trimmed.substring(0, schemeEnd).takeIf { it.isNotEmpty() }
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

    /** Root path for this scheme/host (drops all segments). */
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
    fun enterView(view: List<String>): Path = if (view.isEmpty()) this else enterViews(listOf(view))

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
     * Path.parse("/").parent()                              // null
     * ```
     */
    fun parent(): Path? {
        if (views.isNotEmpty()) {
            // One level up inside the innermost view; an emptied level closes.
            val last = views.last().dropLast(1)
            val remaining = if (last.isEmpty()) views.dropLast(1) else views.dropLast(1) + listOf(last)
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
        return Path(scheme, userInfo, host, port, segments + name)
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
        if (target.scheme != null || target.host != null) {
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
        return Path(scheme, userInfo, host, port, resolved)
    }

    // ── String representations ──────────────────────────────────────────

    /**
     * Canonical path string for daemon communication (Rust backend).
     *
     * For bare paths: `"/storage/emulated/0"`, `"/"`
     * For URIs: `"container://alpine/home/test"`
     *
     * Trailing slashes are never present (except for root `"/"`).
     */
    override fun toString(): String {
        val base = toFullUri()
        if (views.isEmpty()) return base
        return base + views.joinToString("") { view -> "!/" + view.joinToString("/") }
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
        sb.append(segments.joinToString("/"))
        return sb.toString()
    }

    /**
     * Inner path for provider routing — includes user info, host and port as
     * the first segment.
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
