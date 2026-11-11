package io.furryr.file.core

data class ResolvedProvider(
    val provider: FileProvider,
    val path: Path,
    val originalUri: String,
) {
    /** Provider-facing address for calling provider methods. */
    val innerPath: String
        get() = when {
            provider.fullUriPaths -> path.toFullUri()
            path.scheme == "file" || path.scheme == null -> path.toLocalPath()
            else -> path.toInnerPath()
        }
}
