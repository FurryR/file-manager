package io.furryr.file.container

/**
 * Seam for the app-owned terminal session manager. Container management uses
 * this to report whether a container currently has a live session without
 * depending on the terminal/UI implementation.
 */
object ContainerRuntime {
    @Volatile
    var isRunning: (String) -> Boolean = { false }
}
