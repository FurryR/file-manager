package io.furryr.file.core

/** Directory summary returned by [Vfs.info]. */
data class DirectoryStats(
    val folderCount: Int = 0,
    val fileCount: Int = 0,
    val usableBytes: Long = 0L,
    val totalBytes: Long = 0L,
)
