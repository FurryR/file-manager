package io.furryr.file

import io.furryr.file.core.ContentInfo
import io.furryr.file.core.ContentTypes
import io.furryr.file.core.VFile
import io.furryr.file.core.VFolder
import io.furryr.file.core.VNode
import io.furryr.file.core.ViewOpeners
import io.furryr.file.opener.OpenRequest
import io.furryr.file.opener.OpenRouter
import io.furryr.file.opener.OpenRule
import io.furryr.file.render.ExternalRenderer
import io.furryr.file.render.FolderPageState
import io.furryr.file.render.FolderSessionRenderer
import io.furryr.file.render.NodeOpener
import io.furryr.file.render.Opened
import io.furryr.file.render.RendererContext
import io.furryr.file.render.RenderTarget
import io.furryr.file.render.RendererBase
import io.furryr.file.render.Renderers
import kotlin.coroutines.cancellation.CancellationException

/** Per-open facts the rules may read but the opener module must not know. */
data class OpenContext(
    val host: RendererContext,
)

/** What the app should do with an opened file. */
sealed interface AppOpenOutcome {
    data class Folder(val node: VFolder) : AppOpenOutcome

    /** An in-app renderer claimed the file; the shell stores the renderer. */
    data class Renderer(val renderer: RendererBase<*>) : AppOpenOutcome

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
object OpenTable : NodeOpener {
    private val router = OpenRouter<OpenContext, AppOpenOutcome>()

    override suspend fun open(
        node: VNode,
        content: ContentInfo?,
        host: RendererContext,
    ): Opened = when (node) {
        is VFolder -> Opened.Renderer(
            FolderSessionRenderer(
                initialState = FolderPageState(node.path.toString(), node.name),
            ),
        )
        is VFile -> {
            // A listed row arrives without facts. An unknown extension says
            // nothing about the bytes, so probe before routing: otherwise every
            // extensionless text file falls through to the platform viewer.
            val facts = content ?: if (ContentTypes.kindOfName(node.name) == null) {
                node.describe().getOrNull()
            } else {
                null
            }
            when (val outcome = route(node, facts, OpenContext(host))) {
                is AppOpenOutcome.Renderer -> Opened.Renderer(outcome.renderer)
                is AppOpenOutcome.Folder -> Opened.Renderer(
                    FolderSessionRenderer(
                        initialState = FolderPageState(
                            path = outcome.node.path.toString(),
                            title = outcome.node.name,
                        ),
                    ),
                )
                AppOpenOutcome.External -> Opened.Renderer(ExternalRenderer(node, facts))
                is AppOpenOutcome.Failure -> Opened.Failure(outcome.message)
            }
        }
        else -> Opened.Failure("Unsupported node")
    }

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
        return factory.create(target, request.context.host).fold(
            onSuccess = { AppOpenOutcome.Renderer(it) },
            onFailure = { AppOpenOutcome.Failure(it.message) },
        )
    }
}

/** Platform viewer is the last resort. */
private class ExternalRoute : OpenRule<OpenContext, AppOpenOutcome> {
    override val id: String = "external"

    override suspend fun open(request: OpenRequest<OpenContext>): AppOpenOutcome =
        AppOpenOutcome.Renderer(ExternalRenderer(request.file, request.content))
}
