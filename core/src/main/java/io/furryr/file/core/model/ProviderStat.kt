package io.furryr.file.core.model

import io.furryr.file.core.MimeType

/** Result of a `stat` on any provider; files also carry their routing MIME. */
data class ProviderStat(
    val usableBytes: Long = 0,
    val totalBytes: Long = 0,
    val exists: Boolean = false,
    val isDirectory: Boolean = false,
    val canCreateChildren: Boolean = false,
    val size: Long = 0L,
    val modifiedAt: Long = 0L,
    val mime: MimeType? = null,
)
