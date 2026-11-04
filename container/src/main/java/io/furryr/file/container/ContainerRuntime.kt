package io.furryr.file.container

/**
 * Seam for the platform-side daemon registry. Container management uses this
 * to report whether a container's own daemon is alive, without depending on
 * the UI or terminal implementation. A terminal is only a client of that
 * daemon: it may be closed while the container keeps running, and a container
 * without terminals is still alive as long as its daemon is.
 */
object ContainerRuntime {
    @Volatile
    var isRunning: (String) -> Boolean = { false }
}
