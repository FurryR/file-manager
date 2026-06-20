package io.furryr.file.agent

/**
 * Android URI resolution layer built on top of [PathCanonicalizer].
 *
 * Provides a convenient API for resolving `android:///` URIs and querying
 * security properties without manually composing PathCanonicalizer calls.
 *
 * All path logic is delegated to [PathCanonicalizer] — this class is a thin
 * orchestration wrapper for UI-layer and agent-tool consumers.
 */
object AndroidUriResolver {

    /**
     * Resolves an `android:///` URI and returns a [Result] containing
     * an [AndroidUriResult] with the resolved path, allow-list status,
     * and root requirement metadata.
     *
     * Returns [Result.failure] for invalid URI schemes or malformed URIs.
     */
    fun resolve(uri: String): Result<AndroidUriResult> {
        return PathCanonicalizer.resolveAndroidUri(uri).map { resolved ->
            AndroidUriResult(
                resolvedPath = resolved,
                allowed = PathCanonicalizer.isPathAllowed(resolved),
                needsRoot = resolved.needsRoot
            )
        }
    }

    /**
     * Returns `true` if the given URI passes the security allow-list.
     *
     * Validates the URI format, detects path traversal attacks, and checks
     * the resolved physical path against the configured allowed roots.
     *
     * Delegates directly to [PathCanonicalizer.isPathAllowed].
     */
    fun isAllowed(uri: String): Boolean {
        return PathCanonicalizer.isPathAllowed(uri)
    }

    /**
     * Returns `true` if the given URI resolves to a path that requires
     * root (elevated) access.
     *
     * Delegates to [PathCanonicalizer.needsRoot] via the resolved physical path.
     * Returns `false` if the URI is invalid or cannot be resolved.
     */
    fun needsRoot(uri: String): Boolean {
        return PathCanonicalizer.resolveAndroidUri(uri)
            .map { resolved -> resolved.needsRoot }
            .getOrDefault(false)
    }
}

/**
 * Result of resolving an Android URI, combining the canonical [ResolvedPath]
 * with pre-computed security metadata.
 *
 * @property resolvedPath  The canonical resolved path from [PathCanonicalizer.resolveAndroidUri].
 * @property allowed       Whether the resolved path passes the security allow-list.
 * @property needsRoot     Whether the resolved path requires elevated privileges.
 */
data class AndroidUriResult(
    val resolvedPath: ResolvedPath,
    val allowed: Boolean,
    val needsRoot: Boolean
)
