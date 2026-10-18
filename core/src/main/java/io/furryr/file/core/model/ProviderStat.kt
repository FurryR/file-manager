package io.furryr.file.core.model

/** Result of a `stat` on any provider. */
data class ProviderStat(
    val usableBytes: Long = 0,
    val totalBytes: Long = 0,
    val exists: Boolean = false,
    val isDirectory: Boolean = false,
    val canCreateChildren: Boolean = false,
    val canEnter: Boolean = false,
)
