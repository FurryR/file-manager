package io.furryr.file.core.model

/** A single directory entry as returned by any provider. */
data class FileEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val modifiedAt: Long,
    val isSymlink: Boolean = false,
    val realPath: String = "",
    val mode: Int = 0,
    val size: Long = 0L,
)
