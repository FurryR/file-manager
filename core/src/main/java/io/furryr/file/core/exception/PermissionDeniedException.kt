package io.furryr.file.core.exception

/** Thrown when the daemon rejects an operation because of insufficient privileges. */
class PermissionDeniedException(path: String) : PresentableException("Permission denied: $path") {
    override val presentation: ErrorPresentation = ErrorPresentation.PermissionDenied
}
