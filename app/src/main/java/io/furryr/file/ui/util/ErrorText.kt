package io.furryr.file.ui.util

import android.content.Context
import io.furryr.file.R
import io.furryr.file.core.exception.ErrorPresentation
import io.furryr.file.core.exception.PresentableException
import io.furryr.file.provider.remote.SshHostKeyApprovalRequired
import io.furryr.file.provider.remote.SshHostKeyChangedException

/**
 * The one place a failure becomes user text.
 *
 * A [PresentableException] declares what kind of failure it is; the app owns
 * the wording and the locale, so its own English `message` is never shown.
 */
fun Context.errorText(error: Throwable?, fallbackRes: Int = R.string.toast_failed): String =
    when {
        error is SshHostKeyChangedException ->
            getString(R.string.remote_host_changed, error.fingerprint)
        error is SshHostKeyApprovalRequired -> getString(R.string.remote_host_required)
        error is PresentableException -> presentationText(error.presentation, fallbackRes)
        else -> error?.localizedMessage?.takeIf { it.isNotBlank() } ?: getString(fallbackRes)
    }

private fun Context.presentationText(
    presentation: ErrorPresentation,
    fallbackRes: Int,
): String = when (presentation) {
    is ErrorPresentation.ServiceUnavailable ->
        getString(R.string.service_not_started, presentation.endpoint)
    ErrorPresentation.PermissionDenied -> getString(R.string.permission_denied)
    is ErrorPresentation.Message ->
        presentation.text?.takeIf { it.isNotBlank() } ?: getString(fallbackRes)
    is ErrorPresentation.Keyed -> keyedText(presentation.key, presentation.argument, fallbackRes)
}

/** Provider-defined keys are stable identifiers, never displayed strings. */
private fun Context.keyedText(key: String, argument: String?, fallbackRes: Int): String =
    when (key) {
        "ssh_error_key_unlock" -> getString(R.string.ssh_error_key_unlock)
        "ssh_error_connect" -> getString(R.string.ssh_error_connect)
        "ssh_error_channel" -> getString(R.string.ssh_error_channel)
        "ssh_error_timeout" -> getString(R.string.ssh_error_timeout)
        "ssh_error_command" -> getString(R.string.ssh_error_command, argument.orEmpty())
        "ssh_error_transfer" -> argument
            ?.takeIf { it.isNotBlank() }
            ?.let { getString(R.string.ssh_error_transfer_detail, it) }
            ?: getString(R.string.ssh_error_transfer)
        "ssh_error_response" -> getString(R.string.ssh_error_response)
        "ssh_error_file_changed" -> getString(R.string.ssh_error_file_changed)
        "ssh_error_unavailable" -> getString(R.string.ssh_error_unavailable)
        "ssh_error_cancelled" -> getString(R.string.ssh_error_cancelled)
        else -> getString(fallbackRes)
    }
