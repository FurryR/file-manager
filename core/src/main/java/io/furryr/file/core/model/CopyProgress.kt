package io.furryr.file.core.model

data class CopyProgress(
    val totalBytes: Long,
    val copiedBytes: Long,
    val currentName: String,
    val finished: Boolean,
    val isCopy: Boolean,
)
