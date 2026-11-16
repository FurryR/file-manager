package io.furryr.file.render

import androidx.compose.ui.res.stringResource
import io.furryr.file.Factory
import io.furryr.file.R
import io.furryr.file.Renderer
import io.furryr.file.core.OpenIcon
import io.furryr.file.core.OpenOption
import io.furryr.file.core.VFile
import io.furryr.file.core.model.FileEntry
import io.furryr.file.model.PageKind
import io.furryr.file.ui.shell.GenericLoaderDialog
import io.furryr.file.ui.util.openFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The generic loader has no durable data beyond the file it is choosing for. */
internal class GenericPageState : PageState()

/** What the chooser promise settles with. */
private sealed interface GenericPick {
    data class Embedded(val factory: Factory) : GenericPick

    data object System : GenericPick
}

/** The fallback behind a file whose MIME has no entry. */
internal class GenericFactory(
    private val options: () -> List<Factory>,
) : Factory {
    override val option: OpenOption =
        OpenOption(id = "generic", label = "", icon = OpenIcon.GENERIC)

    override suspend fun create(file: VFile): Result<Renderer> =
        Result.success(GenericRenderer(file, options()))
}

/**
 * The renderer for a file no factory claimed. It owns the chooser, so a caller
 * only ever receives a renderer and calls `ctx.open(renderer)`; the choice,
 * including the platform hand-off, never leaks into Folder or Home.
 *
 * The chooser is a popup, not a page: [enter] awaits the choice and never
 * commits, so the live page/title/path stay untouched.
 */
internal class GenericRenderer(
    private val file: VFile,
    private val options: List<Factory>,
    private val error: String? = null,
    initialState: GenericPageState? = null,
) : RendererBase<GenericPageState>(initialState) {
    val state: GenericPageState get() = genericSession.state
    override val pagePath: String = file.path.toString()
    override val pageKind: PageKind = PageKind.FILE

    private val genericSession: GenericRendererSession get() = session as GenericRendererSession

    override fun createSession(state: GenericPageState?): PageSession<GenericPageState> =
        GenericRendererSession(file, options, error, state)

    override suspend fun enter(host: RendererContext) {
        val session = session as GenericRendererSession
        session.error?.let { host.reportError(IllegalStateException(it)) }
        val pick = host.popup<GenericPick?> { resolve ->
            GenericLoaderDialog(
                title = stringResource(R.string.open_with_title),
                options = session.options.map { it.option },
                onPick = { option ->
                    session.options.firstOrNull { it.option.id == option.id }
                        ?.let { resolve(GenericPick.Embedded(it)) }
                },
                onSystem = { resolve(GenericPick.System) },
                onDismiss = { resolve(null) },
            )
        }
        when (pick) {
            null -> host.dismissPending()
            GenericPick.System -> {
                // The platform chooser gets the file's stat: size and routing
                // MIME. No body is read.
                val stat = withContext(Dispatchers.IO) {
                    session.file.stat().getOrNull()
                }
                host.context?.let { context ->
                    openFile(
                        context,
                        FileEntry(
                            name = session.file.name,
                            path = session.file.path.toString(),
                            isDirectory = false,
                            modifiedAt = stat?.modifiedAt ?: 0L,
                            size = stat?.size ?: 0L,
                        ),
                        session.file,
                        stat,
                    )
                }
                host.dismissPending()
            }
            is GenericPick.Embedded -> pick.factory.create(session.file).fold(
                onSuccess = { renderer -> host.replace(renderer) },
                onFailure = { failure ->
                    host.reportError(failure)
                    host.dismissPending()
                },
            )
        }
    }
}

internal class GenericRendererSession(
    val file: VFile,
    val options: List<Factory>,
    val error: String?,
    initialState: GenericPageState? = null,
) : PageSession<GenericPageState>(initialState) {
    internal val state: GenericPageState = seedState ?: GenericPageState()
    override val title: String get() = file.name
    override val suppressBookmark: Boolean get() = true
    override fun close() = Unit
}
