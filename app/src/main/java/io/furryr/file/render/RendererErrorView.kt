package io.furryr.file.render

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.furryr.file.R
import io.furryr.file.core.PermissionDeniedException
import io.furryr.file.core.ProviderUnavailableException
import io.furryr.file.model.ErrorState
import io.furryr.file.ui.components.ServiceUnavailableState

/**
 * Renderer-owned presentation policy for a failed content load. The list view
 * reports the failure and a retry action; it never knows which failure it got.
 */
interface RendererErrorView {
    @Composable
    fun Content(error: Throwable, retry: () -> Unit)
}

/**
 * Default policy. This is the only place that maps provider failures to a
 * presentation.
 */
object DefaultRendererErrorView : RendererErrorView {
    @Composable
    override fun Content(error: Throwable, retry: () -> Unit) {
        when (error) {
            is ProviderUnavailableException -> ServiceUnavailableState(
                endpoint = error.endpoint,
                onRetry = retry,
            )
            is PermissionDeniedException -> ErrorState(stringResource(R.string.permission_denied))
            else -> ErrorState(error.localizedMessage ?: stringResource(R.string.permission_denied))
        }
    }
}
