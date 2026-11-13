package io.furryr.file.container

/**
 * A filesystem path exists on the host but the target container has no bind
 * mount or rootfs location that reaches it. The caller builds the user-facing
 * message, so container details never become hardcoded English.
 */
class PathUnavailableException(val containerName: String) : Exception()
