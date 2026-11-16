package io.furryr.file.provider.remote

import io.furryr.file.core.exception.ErrorPresentation
import io.furryr.file.core.exception.PresentableException

/** A failure whose wording belongs to the app, not to the provider. */
enum class SshError(val key: String) {
    KEY_UNLOCK("ssh_error_key_unlock"),
    CONNECT("ssh_error_connect"),
    CHANNEL("ssh_error_channel"),
    TIMEOUT("ssh_error_timeout"),
    COMMAND("ssh_error_command"),
    TRANSFER("ssh_error_transfer"),
    RESPONSE("ssh_error_response"),
    FILE_CHANGED("ssh_error_file_changed"),
    UNAVAILABLE("ssh_error_unavailable"),
    CANCELLED("ssh_error_cancelled"),
}

/**
 * An SSH failure: [error] picks the app's localized wording and [detail] is the
 * one value that wording may interpolate (an exit code, a host, a status).
 */
class SshException(
    val error: SshError,
    val detail: String? = null,
    cause: Throwable? = null,
) : PresentableException("SSH ${error.key}${detail?.let { ": $it" }.orEmpty()}", cause) {
    override val presentation: ErrorPresentation = ErrorPresentation.Keyed(error.key, detail)
}
