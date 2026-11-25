package io.furryr.file.picker

import io.furryr.file.Factory
import io.furryr.file.Renderer
import io.furryr.file.core.OpenIcon
import io.furryr.file.core.OpenOption
import io.furryr.file.core.VFile
import io.furryr.file.model.PageIcon
import io.furryr.file.ui.icons.Icons
import io.furryr.file.render.PageSession
import io.furryr.file.render.PageState
import io.furryr.file.render.RendererBase
import io.furryr.file.render.RendererContext

/**
 * The picker's one action: a routed file becomes a selection.
 *
 * The picker world registers [PickerSelectFactory] as its fallback and no
 * editor, browser or archive opener. A click therefore never opens a format;
 * it reaches [select] and the shell only updates its selected set.
 */
class PickerSelection {
    @Volatile
    var onSelect: ((VFile) -> Unit)? = null

    fun select(file: VFile) {
        onSelect?.invoke(file)
    }
}

internal class PickerSelectFactory(
    private val selection: PickerSelection,
) : Factory {
    override val option: OpenOption = OpenOption(
        id = "picker_select",
        label = "Select",
        icon = OpenIcon.GENERIC,
    )

    override suspend fun create(file: VFile): Result<Renderer> {
        selection.select(file)
        return Result.success(PickerSelectRenderer())
    }
}

/** A placeholder renderer: the factory already recorded the selection. */
private class PickerSelectRenderer : RendererBase<PageState>(null) {
    override val pagePath: String = "picker://"
    override val pageIcon: PageIcon get() = PageIcon.Vector(Icons.Default.Language)

    override fun createSession(state: PageState?): PageSession<PageState> =
        object : PageSession<PageState>(null) {
            override fun close() = Unit
        }

    override suspend fun enter(host: RendererContext) {
        host.dismissPending()
    }
}
