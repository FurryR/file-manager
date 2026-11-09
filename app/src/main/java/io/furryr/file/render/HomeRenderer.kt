package io.furryr.file.render

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterAlt
import io.furryr.file.R

/**
 * Home is a view, not a file, so it has no [RendererSession]. It still owns a
 * renderer menu so the overflow sheet is assembled from the active view rather
 * than from a fixed list in the activity.
 */
object HomeRenderer {
    /**
     * Home shows its title, but tapping it must start an empty address field:
     * `home://` is an internal landing marker, not a location to edit.
     */
    fun pathBar(context: Context): RendererPath = RendererPath(
        title = context.getString(R.string.home_title),
        editText = "",
    )

    fun menuItems(
        context: Context,
        host: RenderHost,
    ): List<RendererMenuItem> = listOf(
        RendererMenuItem(
            title = context.getString(R.string.filter_title),
            icon = Icons.Default.FilterAlt,
        ) {
            host.toggleFilter()
        },
    )
}
