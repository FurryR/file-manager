package io.furryr.file.render

import io.furryr.file.isHomePath
import kotlinx.coroutines.CoroutineScope

/**
 * App-side assembly for the renderers. Every renderer receives the same
 * [RenderHost]; a directory session also receives its initial view and
 * selection snapshot.
 */
object RendererSessions {
    /** The location renderer for [path]: Home or the directory view. */
    fun location(
        host: RenderHost,
        scope: CoroutineScope,
        path: String,
        initialSelection: Set<String>,
        initialSelectedIndex: Int,
    ): RendererSession =
        if (isHomePath(path)) HomeRendererSession(host)
        else FolderRendererSession(
            host = host,
            scope = scope,
            initialSelection = initialSelection,
            initialSelectedIndex = initialSelectedIndex,
        )
}
