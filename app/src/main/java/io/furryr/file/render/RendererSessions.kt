package io.furryr.file.render

import io.furryr.file.core.Path
import io.furryr.file.isHomePath

/**
 * App-side assembly for the renderers. Every renderer receives the same
 * [RendererContext]; a directory session also receives its initial view and
 * selection snapshot.
 */
object RendererSessions {
    /** The location renderer for [path]: Home or the directory view. */
    fun location(
        path: String,
        initialSelection: Set<String>,
        initialSelectedIndex: Int,
        homeTitle: String,
    ): RendererBase<*> =
        if (isHomePath(path)) HomeSessionRenderer(homeTitle = homeTitle)
        else FolderSessionRenderer(
            initialSelection = initialSelection,
            initialSelectedIndex = initialSelectedIndex,
            initialState = FolderPageState(
                path = path,
                title = Path.parse(path).name,
            ),
        )
}
