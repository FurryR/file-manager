package io.furryr.file

import io.furryr.file.core.ContentInfo
import io.furryr.file.core.ContentTypes
import io.furryr.file.core.VFile
import io.furryr.file.core.VFolder
import io.furryr.file.editor.EditorRendererSession
import io.furryr.file.opener.OpenRequest
import io.furryr.file.opener.OpenRoute
import io.furryr.file.opener.OpenRouter
import io.furryr.file.opener.Openers
import io.furryr.file.render.RenderHost
import io.furryr.file.render.RenderTarget
import io.furryr.file.render.Renderers
import io.furryr.file.render.WebRendererSession
import kotlin.coroutines.cancellation.CancellationException

/** Per-open facts that routes may read but the opener module must not know. */
data class OpenRouteContext(
    val host: RenderHost,
)

/** What the app should do with an opened file. */
sealed interface AppOpenOutcome {
    data class Folder(val node: VFolder) : AppOpenOutcome
    data class Web(val session: WebRendererSession) : AppOpenOutcome
    data class Editor(val session: EditorRendererSession) : AppOpenOutcome

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
object OpenRoutes {
    val router = OpenRouter<OpenRouteContext, AppOpenOutcome>()

    fun install() {
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

/** An in-app renderer claims the file (a page, a document, a directory). */
private class RendererRoute : OpenRoute<OpenRouteContext, AppOpenOutcome> {
    override val id: String = "renderer"

    override fun supports(request: OpenRequest<OpenRouteContext>): Boolean {
        val target = RenderTarget(request.file, request.content)
        return Renderers.resolve(target) != null
    }

    override suspend fun open(request: OpenRequest<OpenRouteContext>): AppOpenOutcome? {
        val target = RenderTarget(request.file, request.content)
        val factory = Renderers.resolve(target) ?: return null
        val session = factory.create(target, request.context.host).getOrNull() ?: return null
        if (session is WebRendererSession) return AppOpenOutcome.Web(session)
        if (session is EditorRendererSession) return AppOpenOutcome.Editor(session)
        // An unhandled renderer must not silently swallow the open; let the
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
        AppOpenOutcome.External
}


/**
 * Whether an address belongs to the browser renderer, decided from what is
 * already known before any request.
 *
 * The address is the only free signal. Spending a round trip on the server's
 * type first pushes the page's first byte back by exactly that round trip, so
 * the tab hands the address to the browser renderer immediately and settles the
 * question against the answer it gets ([Verdict]). The rules live here, not in
 * the renderer: a renderer that asked the server what it was could never be
 * handed a page nobody has identified yet.
 */
object WebPagePolicy {
    enum class Verdict {
        /** The browser renderer owns the address. */
        PAGE,

        /** Nothing decisive yet; the body sniff has to answer. */
        UNDECIDED,

        /** A concrete type a browser cannot show: hand it back to the table. */
        NOT_PAGE,
    }

    /**
     * The address alone says "page", so no request is worth spending on the way
     * to the renderer.
     *
     * A page name, or no name at all (an extensionless article, a directory
     * style address), is the common case and always wins. A name the shared
     * table already maps to a concrete non-page type — a `.zip`, a `.pdf`, an
     * image — keeps the eager probe, so a link to an archive opens as an
     * archive on the first tap instead of flashing a browser tab and reopening.
     */
    fun likelyPage(address: String): Boolean {
        val name = nameOf(address)
        if (isPageName(name)) return true
        val byName = ContentTypes.fromName(name) ?: return true
        return ContentTypes.isHtmlMime(byName)
    }

    /**
     * What [content] says about the browser renderer's ownership.
     *
     * [settled] marks facts that came from the body (a magic-byte sniff), not
     * from headers alone: a header can mislabel a page as `text/plain` or
     * octet-stream, a sniffed body cannot.
     */
    fun verdict(content: ContentInfo?, settled: Boolean): Verdict {
        val mime = content?.mime
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() }
        if (ContentTypes.isHtmlMime(mime)) return Verdict.PAGE
        // No usable type, a generic one, or the classic mislabelling of a page
        // as plain text: only the name, and after it the body, can decide.
        val vague = mime == null || ContentTypes.isGenericMime(mime) || mime == "text/plain"
        if (vague) {
            if (isPageName(content?.name)) return Verdict.PAGE
            return if (settled) Verdict.NOT_PAGE else Verdict.UNDECIDED
        }
        return Verdict.NOT_PAGE
    }

    private val pageSuffixes = listOf(".html", ".htm", ".xhtml")

    private fun isPageName(name: String?): Boolean {
        val value = name?.trim()?.lowercase() ?: return false
        return pageSuffixes.any { value.endsWith(it) }
    }

    /** Last path segment of [address]: no query, no fragment, no trailing slash. */
    private fun nameOf(address: String): String =
        address.substringBefore('#').substringBefore('?').trimEnd('/').substringAfterLast('/')
}
