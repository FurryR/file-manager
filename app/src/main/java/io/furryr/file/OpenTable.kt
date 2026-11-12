package io.furryr.file

import io.furryr.file.core.ContentInfo
import io.furryr.file.core.VFile
import io.furryr.file.core.VFolder
import io.furryr.file.core.ViewOpeners
import io.furryr.file.opener.OpenRequest
import io.furryr.file.opener.OpenRouter
import io.furryr.file.opener.OpenRule
import io.furryr.file.render.RenderHost
import io.furryr.file.render.RenderTarget
import io.furryr.file.render.RendererSession
import io.furryr.file.render.Renderers
import kotlin.coroutines.cancellation.CancellationException

/** Per-open facts the rules may read but the opener module must not know. */
data class OpenContext(
    val host: RenderHost,
)

/** What the app should do with an opened file. */
sealed interface AppOpenOutcome {
    data class Folder(val node: VFolder) : AppOpenOutcome

    /** An in-app renderer claimed the file; the shell stores the session. */
    data class Renderer(val session: RendererSession) : AppOpenOutcome

    /** Hand the file to the platform viewer. */
    data object External : AppOpenOutcome

    data class Failure(val message: String?) : AppOpenOutcome
}

/**
 * App-side open-with table. The generic [OpenRouter] lives in `:opener`; the
 * concrete order is platform policy and stays here.
 *
 * Every file — a local file, an archive entry, a shared file link, a page
 * address — arrives here as a [VFile]: the provider already decided what the
 * address is, so this table never inspects a scheme or a node type.
 */
object OpenTable {
    private val router = OpenRouter<OpenContext, AppOpenOutcome>()

    fun install() {
        router.register(90, ViewRoute())
        router.register(80, RendererRoute())
        router.register(0, ExternalRoute())
    }

    suspend fun route(
        file: VFile,
        content: ContentInfo?,
        context: OpenContext,
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

/** Container view: the file becomes a navigable folder. */
private class ViewRoute : OpenRule<OpenContext, AppOpenOutcome> {
    override val id: String = "view"

    override suspend fun open(request: OpenRequest<OpenContext>): AppOpenOutcome? {
        val content = request.content ?: return null
        val opener = ViewOpeners.resolve(request.file, content) ?: return null
        return ViewOpeners.enter(request.file, opener).fold(
            onSuccess = { AppOpenOutcome.Folder(it) },
            onFailure = { AppOpenOutcome.Failure(it.message) },
        )
    }
}

/** An in-app renderer claims the file (a page, a document, a directory). */
private class RendererRoute : OpenRule<OpenContext, AppOpenOutcome> {
    override val id: String = "renderer"

    override suspend fun open(request: OpenRequest<OpenContext>): AppOpenOutcome? {
        val target = RenderTarget(request.file, request.content)
        val factory = Renderers.resolve(target) ?: return null
        val session = factory.create(target, request.context.host).getOrNull() ?: return null
        // Any renderer may claim the file; the shell stores the generic session
        // and never needs to know which concrete view it got.
        return AppOpenOutcome.Renderer(session)
    }
}

/** Platform viewer is the last resort. */
private class ExternalRoute : OpenRule<OpenContext, AppOpenOutcome> {
    override val id: String = "external"

    override suspend fun open(request: OpenRequest<OpenContext>): AppOpenOutcome =
        AppOpenOutcome.External
}
