package io.furryr.file.provider

import io.furryr.file.util.Path

data class ResolvedProvider(
    val provider: FileProvider,
    val path: Path,
    val originalUri: String,
) {
    /** Inner path string for calling provider methods. */
    val innerPath: String get() = if (path.scheme == "file" || path.scheme == null)
        path.toLocalPath() else path.toInnerPath()
}
