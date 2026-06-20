package io.furryr.file.util

/**
 * Protocol-aware path with support for full URIs and bare filesystem paths.
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
            val trimmed = raw.trim()
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
                    else if (host != null) return Path(scheme, userInfo, null, port, resolved)
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
    override fun toString(): String = toFullUri()

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
     * Inner path for provider routing — includes host as first segment.
     *
     * ```
     * Path.parse("container://alpine/home/test").toInnerPath()  // => "/alpine/home/test"
     * Path.parse("container://alpine").toInnerPath()            // => "/alpine"
     * Path.parse("container:///").toInnerPath()                 // => "/"
     * ```
     */
    fun toInnerPath(): String {
        val sb = StringBuilder().append('/')
        if (host != null) sb.append(host)
        if (segments.isNotEmpty()) {
            if (host != null) sb.append('/')
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
            segments == other.segments
    }

    override fun hashCode(): Int {
        var result = scheme.hashCode()
        result = 31 * result + (userInfo?.hashCode() ?: 0)
        result = 31 * result + (host?.hashCode() ?: 0)
        result = 31 * result + (port ?: 0)
        result = 31 * result + segments.hashCode()
        return result
    }
}
