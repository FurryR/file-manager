package io.furryr.file.render

import android.content.Context
import io.furryr.file.core.ContentInfo
import io.furryr.file.core.VFile
import io.furryr.file.core.model.FileEntry
import io.furryr.file.model.PageKind
import io.furryr.file.ui.util.openFile

/** External hand-off has no durable data; the page only exists for one frame. */
object ExternalPageState : PageState()

/**
 * A renderer that hands its file to the platform viewer.
 *
 * It never commits a page: `enter` opens the chooser and clears the pending
 * renderer, so the outgoing page stays untouched.
 */
class ExternalRenderer(
    private val file: VFile,
    private val content: ContentInfo?,
) : RendererBase<ExternalPageState>(ExternalPageState) {
    override val state: ExternalPageState = ExternalPageState
    override val pagePath: String = file.path.toString()
    override val pageKind: PageKind = PageKind.FILE

    override fun createSession(state: ExternalPageState?): PageSession<ExternalPageState> =
        ExternalRendererSession(file, content, state)

    override suspend fun enter(host: RendererContext) {
        val context = host.context ?: return
        (session as ExternalRendererSession).open(context)
        host.requestClose()
    }
}

private class ExternalRendererSession(
    private val file: VFile,
    private val content: ContentInfo?,
    initialState: ExternalPageState?,
) : PageSession<ExternalPageState>(initialState) {
    override val state: ExternalPageState = seedState ?: ExternalPageState
    override val capabilities = RenderCapabilities()
    override val title: String? get() = content?.name?.takeIf { it.isNotBlank() } ?: file.name

    override fun pathBar(context: Context): RendererPath? {
        val path = file.path.toString()
        return RendererPath(
            title = title ?: path,
            caption = path,
            editText = path,
        )
    }

    fun open(context: Context) {
        openFile(
            context,
            FileEntry(
                name = title ?: file.name,
                path = file.path.toString(),
                isDirectory = false,
                modifiedAt = 0L,
                size = content?.size ?: 0L,
            ),
            file,
            content,
        )
    }

    override fun close() = Unit
}
