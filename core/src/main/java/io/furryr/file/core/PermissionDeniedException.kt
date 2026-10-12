package io.furryr.file.core

/** Thrown when the daemon rejects an operation because of insufficient privileges. */
class PermissionDeniedException(path: String) : IllegalStateException("Permission denied: $path")
