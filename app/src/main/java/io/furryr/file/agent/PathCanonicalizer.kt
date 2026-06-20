package io.furryr.file.agent

/**
 * Pure path-logic resolver for Android URI paths.
 *
 * Resolves `android:///` URIs to physical file system paths,
 * canonicalizes paths (normalize `..`, `.`, duplicate slashes),
 * checks against a security allow-list, and detects path traversal attacks.
 *
 * No file I/O — operates entirely on string paths.
 */
object PathCanonicalizer {

    private const val ANDROID_SCHEME = "android"
    private const val SDCARD_PREFIX = "/sdcard"
    private const val STORAGE_PREFIX = "/storage/emulated/0"
    private const val SYSTEM_PREFIX = "/system"
    private const val DATA_DATA_PREFIX = "/data/data"
    private const val OWN_PACKAGE = "io.furryr.file"

    // ── URI resolution ────────────────────────────────────────────────

    /**
     * Parses an `android:///` URI, extracts the raw path, canonicalizes it,
     * resolves `/sdcard` → `/storage/emulated/0`, and determines whether
     * root access is required.
     *
     * @return [Result.success] with [ResolvedPath] on valid URIs,
     *         [Result.failure] on invalid schemes or empty paths.
     */
    fun resolveAndroidUri(uri: String): Result<ResolvedPath> {
        val schemeEnd = uri.indexOf(":///")
        if (schemeEnd == -1) {
            return Result.failure(IllegalArgumentException("Invalid URI format, expected scheme:///path: $uri"))
        }

        val scheme = uri.substring(0, schemeEnd)
        if (scheme != ANDROID_SCHEME) {
            return Result.failure(IllegalArgumentException("Unsupported URI scheme: $scheme"))
        }

        val rawPath = uri.substring(schemeEnd + 4) // skip ":///"
        if (rawPath.isBlank()) {
            return Result.failure(IllegalArgumentException("Empty path in URI: $uri"))
        }

        val canonicalPath = canonicalize(rawPath)
        val physicalPath = resolveSdcardAlias(canonicalPath)
        val requiresRoot = needsRoot(physicalPath)

        return Result.success(
            ResolvedPath(
                scheme = scheme,
                originalUri = uri,
                physicalPath = physicalPath,
                needsRoot = requiresRoot
            )
        )
    }

    // ── Canonicalization ──────────────────────────────────────────────

    /**
     * Normalizes a file path by resolving `.` (current directory),
     * `..` (parent directory), and collapsing duplicate slashes.
     *
     * Examples:
     * - `/sdcard/foo/../bar` → `/sdcard/bar`
     * - `/sdcard/./bar`      → `/sdcard/bar`
     * - `//sdcard///foo/`    → `/sdcard/foo`
     */
    fun canonicalize(path: String): String {
        if (path.isBlank()) return "/"

        val segments = path.split("/")
        val result = ArrayDeque<String>()

        for (segment in segments) {
            when {
                segment.isEmpty() || segment == "." -> {
                    // skip empty (duplicate slashes) and current-dir
                }
                segment == ".." -> {
                    if (result.isNotEmpty()) {
                        result.removeLast()
                    }
                    // If result is empty, `..` above root is silently ignored
                }
                else -> result.addLast(segment)
            }
        }

        return "/" + result.joinToString("/")
    }

    // ── Allow-list check ──────────────────────────────────────────────

    /**
     * Checks whether a [ResolvedPath] falls within the allowed directory
     * roots. Data directories for packages other than [OWN_PACKAGE] are
     * rejected even if root is available (requires per-package audit).
     */
    fun isPathAllowed(resolved: ResolvedPath): Boolean {
        val path = resolved.physicalPath

        // /storage/emulated/0/... (primary external storage)
        if (path == STORAGE_PREFIX || path.startsWith("$STORAGE_PREFIX/")) return true

        // /sdcard/... (symlink alias — included for post-resolution checks)
        if (path == SDCARD_PREFIX || path.startsWith("$SDCARD_PREFIX/")) return true

        // /data/data/io.furryr.file/... (own app data)
        if (path == "$DATA_DATA_PREFIX/$OWN_PACKAGE" ||
            path.startsWith("$DATA_DATA_PREFIX/$OWN_PACKAGE/")
        ) return true

        // /system/... (always allowed, requires root to access)
        if (path == SYSTEM_PREFIX || path.startsWith("$SYSTEM_PREFIX/")) return true

        // /data/data/<other-package>/... — requires root + per-package audit
        // Reject all non-own-package data paths by default
        if (path.startsWith("$DATA_DATA_PREFIX/")) return false

        return false
    }

    /**
     * Convenience overload: resolves the URI string, checks for path
     * traversal on the raw path, then delegates to [isPathAllowed].
     */
    fun isPathAllowed(uri: String): Boolean {
        val resolved = resolveAndroidUri(uri).getOrNull() ?: return false

        // Extract raw path from URI to detect traversal before canonicalization
        val rawPath = extractRawPath(uri)
        if (detectPathTraversal(rawPath)) return false

        return isPathAllowed(resolved)
    }

    // ── Traversal detection ───────────────────────────────────────────

    /**
     * Returns `true` if the given path contains `..` components that
     * would escape an allowed root directory.
     *
     * - `sdcard/../../system` → `true`   (escapes /sdcard)
     * - `../../etc/passwd`    → `true`   (goes above root)
     * - `sdcard/foo/../bar`   → `false`  (stays within /sdcard)
     */
    fun detectPathTraversal(path: String): Boolean {
        val absPath = if (path.startsWith("/")) path else "/$path"
        val canon = canonicalize(absPath)

        // Canonicalization produced a path above root — direct escape
        if (canon.startsWith("/..")) return true

        // Check if the original path ever dips below root depth
        val segments = absPath.split("/").filter { it.isNotEmpty() }
        var depth = 0
        for (seg in segments) {
            when (seg) {
                ".." -> depth--
                "." -> { /* skip */ }
                else -> depth++
            }
            if (depth < 0) return true
        }

        // Check whether the path started under a known root but
        // canonicalization moved it to a different root entirely
        for (root in listOf(STORAGE_PREFIX, SDCARD_PREFIX, "$DATA_DATA_PREFIX/$OWN_PACKAGE")) {
            if (absPath.startsWith("$root/") || absPath == root) {
                val staysUnderRoot = canon.startsWith("$root/") || canon == root
                if (!staysUnderRoot) return true
            }
        }

        return false
    }

    // ── Root requirement ──────────────────────────────────────────────

    /**
     * Returns `true` if the given physical path requires root access.
     * `/system/` paths always require root, as do `/data/data/` paths
     * belonging to packages other than [OWN_PACKAGE].
     */
    fun needsRoot(path: String): Boolean {
        if (path == SYSTEM_PREFIX || path.startsWith("$SYSTEM_PREFIX/")) return true
        if (path.startsWith("$DATA_DATA_PREFIX/") &&
            !(path == "$DATA_DATA_PREFIX/$OWN_PACKAGE" ||
                path.startsWith("$DATA_DATA_PREFIX/$OWN_PACKAGE/"))
        ) return true
        return false
    }

    // ── Helpers ───────────────────────────────────────────────────────

    /** Resolves the `/sdcard` alias to the physical `/storage/emulated/0` prefix. */
    private fun resolveSdcardAlias(path: String): String {
        return when {
            path == SDCARD_PREFIX -> STORAGE_PREFIX
            path.startsWith("$SDCARD_PREFIX/") -> STORAGE_PREFIX + path.removePrefix(SDCARD_PREFIX)
            else -> path
        }
    }

    /** Extracts the raw path portion from an `android:///` URI. */
    private fun extractRawPath(uri: String): String {
        val schemeEnd = uri.indexOf(":///")
        if (schemeEnd == -1) return uri
        return uri.substring(schemeEnd + 4)
    }
}

/**
 * Result of resolving an Android URI to a physical file system path.
 *
 * @property scheme       Original URI scheme (always `"android"`).
 * @property originalUri  The raw URI as received.
 * @property physicalPath Canonicalized file system path.
 * @property needsRoot    Whether the path requires elevated privileges.
 */
data class ResolvedPath(
    val scheme: String,
    val originalUri: String,
    val physicalPath: String,
    val needsRoot: Boolean
)
