package io.furryr.file.core

/**
 * A provider whose native paths can be projected onto the host filesystem.
 *
 * The host provider projects its own paths. A container provider projects an
 * entry in its rootfs or bind mounts back to the host pathname, so a host
 * shell can reach it. The host provider asks this contract instead of
 * inspecting schemes, and `:provider` never depends on `:container`.
 */
interface HostPathProvider {
    /**
     * Host pathname for [innerPath], or null when the entry has no host path
     * (a private mount, a stale container). [followFinal] resolves the final
     * symlink for a directory; a file keeps it.
     */
    fun hostPath(innerPath: String, followFinal: Boolean): String?
}
