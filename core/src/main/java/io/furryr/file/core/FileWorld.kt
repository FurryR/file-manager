package io.furryr.file.core

/**
 * One independent file world: a [Vfs] route table, its structural [Proxies],
 * and node operations that resolve through it.
 *
 * The file manager and a picker each own a world. They share process services
 * (the daemon, location stores, SSH connection caches) but never registrations:
 * a provider, proxy or default provider added to one world is invisible to the
 * other.
 */
class FileWorld(
    val vfs: Vfs = Vfs(),
) {
    val proxies: Proxies get() = vfs.proxies
    val nodeOps: NodeOps = NodeOps(vfs)
}
