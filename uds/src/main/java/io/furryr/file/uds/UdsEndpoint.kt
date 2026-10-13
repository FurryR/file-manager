package io.furryr.file.uds

/**
 * Where a UDS server listens, or a client connects.
 *
 * [Abstract] lives in the kernel's abstract namespace (no filesystem entry) —
 * the same scheme the native daemons use, reachable across UIDs. [Path] is a
 * filesystem socket; bind requires the parent directory to exist and is
 * responsible for any stale socket file.
 */
sealed interface UdsEndpoint {
    val name: String

    data class Abstract(override val name: String) : UdsEndpoint
    data class Path(override val name: String) : UdsEndpoint
}
