package io.furryr.file.daemon

/** A live daemon endpoint an external client could attach to. */
data class DaemonEndpoint(
    val name: String,
    val socketName: String,
    val elevated: Boolean,
)
