package io.furryr.file.render

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import io.furryr.file.core.exception.ErrorPresentation
import io.furryr.file.core.exception.PresentableException
import io.furryr.file.model.ErrorState
import io.furryr.file.ui.components.ServiceUnavailableState
import io.furryr.file.ui.util.errorText

/**
 * Renderer-owned presentation policy for a failed content load. The list view
 * reports the failure and a retry action; it never knows which failure it got.
 */
interface RendererErrorView {
    @Composable
    fun Content(error: Throwable, retry: () -> Unit)
}

/**
 * Default policy. Exceptions that implement [PresentableException] choose their
 * presentation; every wording decision goes through [errorText], so provider
 * failures are localized like any other app text.
 */
object DefaultRendererErrorView : RendererErrorView {
    @Composable
    override fun Content(error: Throwable, retry: () -> Unit) {
        when (val presentation = (error as? PresentableException)?.presentation) {
            is ErrorPresentation.ServiceUnavailable -> ServiceUnavailableState(
                endpoint = presentation.endpoint,
                onRetry = retry,
            )
            else -> ErrorState(LocalContext.current.errorText(error), retry)
        }
    }
}
