package io.furryr.file.daemon

/** A live daemon endpoint an external client could attach to. */
data class DaemonEndpoint(
    val name: String,
    val socketName: String,
    val elevated: Boolean,
)

/**
 * Enumerates live daemon endpoints.
 *
 * Core installs a default registry that knows only its own daemons; a host may
 * replace it with one backed by, e.g., a UDS-path file placed on `PATH`. That
 * is the seam an "attach to any running daemon" mechanism
 * plugs into. No such mechanism is implemented here.
 */
fun interface DaemonRegistry {
    fun endpoints(): List<DaemonEndpoint>
}
