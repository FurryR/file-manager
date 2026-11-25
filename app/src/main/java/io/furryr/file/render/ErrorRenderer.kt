package io.furryr.file.render

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.furryr.file.model.PageIcon
import io.furryr.file.ui.icons.Icons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** An address that could not be resolved stays a page, not a toast. */
internal class ErrorPageState : PageState()

internal class ErrorRendererSession(
    private val path: String,
    private val locationPage: Boolean,
    initialState: ErrorPageState? = null,
) : PageSession<ErrorPageState>(initialState) {
    override val title: String get() = path
    override val suppressBookmark: Boolean get() = true
    override val isLocationRenderer: Boolean get() = locationPage
    override fun close() = Unit
}

/**
 * The page for a failed address open. Retry resolves the same address again and
 * replaces this page when it succeeds.
 */
internal class ErrorRenderer(
    private val path: String,
    failure: Throwable,
    private val locationPage: Boolean = false,
    initialState: ErrorPageState? = null,
) : RendererBase<ErrorPageState>(initialState) {
    private var failure by mutableStateOf(failure)

    override val pagePath: String = path
    override val pageIcon: PageIcon get() = PageIcon.Vector(Icons.Default.Language)

    override fun createSession(state: ErrorPageState?): PageSession<ErrorPageState> =
        ErrorRendererSession(path, locationPage, state)

    override suspend fun enter(host: RendererContext) {
        host.commit { _, _, modifier ->
            Box(modifier) {
                DefaultRendererErrorView.Content(failure) { retry(host) }
            }
        }
    }

    override fun pathBar(context: Context): RendererPath =
        RendererPath(title = path, editText = path)

    private fun retry(host: RendererContext) {
        host.scope.launch {
            val result = withContext(Dispatchers.IO) {
                host.vfs.node(NodeAddress.parse(path))
            }
            result.fold(
                onSuccess = { node ->
                    runCatching { host.opener.route(node) }
                        .onSuccess(host::replace)
                        .onFailure { failure = it }
                },
                onFailure = { failure = it },
            )
        }
    }
}
