package io.furryr.file.core.exception

/**
 * Pure presentation facts an error may carry. The app maps these to Compose;
 * the core never depends on a UI toolkit.
 */
sealed class ErrorPresentation {
    data class ServiceUnavailable(val endpoint: String) : ErrorPresentation()
    data object PermissionDenied : ErrorPresentation()
    data class Message(val text: String?) : ErrorPresentation()

    /**
     * A provider-defined failure whose wording belongs to the app. [key] is a
     * stable identifier (never a displayed string) and [argument] is its one
     * optional detail, such as a host key fingerprint.
     */
    data class Keyed(val key: String, val argument: String? = null) : ErrorPresentation()
}

/**
 * A VFS failure that declares how it should be presented. Renderers read
 * [presentation] instead of switching on concrete exception types.
 */
abstract class PresentableException(
    message: String,
    cause: Throwable? = null,
) : VfsException(message, cause) {
    abstract val presentation: ErrorPresentation
}
