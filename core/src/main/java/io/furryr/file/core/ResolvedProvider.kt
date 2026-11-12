package io.furryr.file.core

data class ResolvedProvider(
    val provider: FileProvider,
    val path: Path,
    val originalUri: String,
) {
    /** Provider-facing address for calling provider methods. */
    val innerPath: String
        get() = provider.innerPath(path)
}
