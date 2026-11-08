package io.furryr.file

import io.furryr.file.core.ContentInfo
import io.furryr.file.core.VFile
import io.furryr.file.core.VFolder
import io.furryr.file.core.ViewRef
import io.furryr.file.opener.OpenRequest
import io.furryr.file.opener.OpenRoute
import io.furryr.file.opener.OpenRouter
import io.furryr.file.opener.Openers
import io.furryr.file.render.RenderHost
import io.furryr.file.render.RenderTarget
import io.furryr.file.render.Renderers
import io.furryr.file.render.WebRendererSession
import io.furryr.file.render.WebUrlFile
import kotlin.coroutines.cancellation.CancellationException

/** Per-open facts that routes may read but the opener module must not know. */
data class OpenRouteContext(
    val host: RenderHost,
    val address: String,
    val name: String,
)

/** What the app should do with an opened file. */
sealed interface AppOpenOutcome {
    data class Folder(val node: VFolder) : AppOpenOutcome
    data class Url(val url: String) : AppOpenOutcome
    data class Web(val session: WebRendererSession) : AppOpenOutcome
    data class External(val remote: Boolean) : AppOpenOutcome
    data class Failure(val message: String?) : AppOpenOutcome
}

/**
 * App-side open-with table. The generic [OpenRouter] lives in `:opener`; the
 * concrete order is platform policy and stays here.
 */
object OpenRoutes {
    val router = OpenRouter<OpenRouteContext, AppOpenOutcome>()

    fun install() {
        router.register(100, WebPageRoute())
        router.register(90, OpenerRoute())
        router.register(80, RendererRoute())
        router.register(0, ExternalRoute())
    }

    suspend fun route(
        file: VFile,
        content: ContentInfo?,
        context: OpenRouteContext,
    ): AppOpenOutcome {
        val outcome = try {
            router.route(OpenRequest(file, content, context))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            return AppOpenOutcome.Failure(error.message)
        }
        return outcome ?: AppOpenOutcome.Failure(null)
    }
}

/** A remote HTML page belongs in the WebView, not a document view. */
private class WebPageRoute : OpenRoute<OpenRouteContext, AppOpenOutcome> {
    override val id: String = "web-page"

    override fun supports(request: OpenRequest<OpenRouteContext>): Boolean =
        request.file is WebUrlFile &&
            WebUrlFile.isHtml(request.content, request.context.name)

    override suspend fun open(request: OpenRequest<OpenRouteContext>): AppOpenOutcome =
        AppOpenOutcome.Url(request.context.address)
}

/** Container opener: the file becomes a navigable folder. */
private class OpenerRoute : OpenRoute<OpenRouteContext, AppOpenOutcome> {
    override val id: String = "opener"

    override fun supports(request: OpenRequest<OpenRouteContext>): Boolean {
        val content = request.content ?: return false
        return Openers.candidates(request.file, content).isNotEmpty()
    }

    override suspend fun open(request: OpenRequest<OpenRouteContext>): AppOpenOutcome? {
        val content = request.content ?: return null
        return Openers.openFolder(request.file, content).fold(
            onSuccess = { AppOpenOutcome.Folder(it) },
            onFailure = { AppOpenOutcome.Failure(it.message) },
        )
    }
}

/** An in-app renderer claims the file (directory, web, document). */
private class RendererRoute : OpenRoute<OpenRouteContext, AppOpenOutcome> {
    override val id: String = "renderer"

    override fun supports(request: OpenRequest<OpenRouteContext>): Boolean {
        val content = request.content ?: return false
        return Renderers.resolve(RenderTarget.Node(request.file, content)) != null
    }

    override suspend fun open(request: OpenRequest<OpenRouteContext>): AppOpenOutcome? {
        val content = request.content ?: return null
        val target = RenderTarget.Node(request.file, content)
        val factory = Renderers.resolve(target) ?: return null
        val session = factory.create(target, request.context.host).getOrNull() ?: return null
        if (session is WebRendererSession) return AppOpenOutcome.Web(session)
        // A non-web renderer must not silently swallow the open; let the
        // platform viewer take it.
        session.close()
        return null
    }
}

/** Platform viewer is the last resort. */
private class ExternalRoute : OpenRoute<OpenRouteContext, AppOpenOutcome> {
    override val id: String = "external"

    override fun supports(request: OpenRequest<OpenRouteContext>): Boolean = true

    override suspend fun open(request: OpenRequest<OpenRouteContext>): AppOpenOutcome =
        AppOpenOutcome.External(remote = request.file.ref() is ViewRef.Url)
}
