package io.furryr.file.copy

data class CopyProgress(
    val totalBytes: Long,
    val copiedBytes: Long,
    val currentName: String,
    val finished: Boolean,
    val isCopy: Boolean,
)
