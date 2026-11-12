package io.furryr.file.render

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterAlt
import io.furryr.file.R

/**
 * Shared Home path bar and overflow menu. The live view is a
 * [HomeRendererSession]; these pieces stay here so every Home session reports
 * the same title and filter action.
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
        onToggleFilter: () -> Unit,
    ): List<RendererMenuItem> = listOf(
        RendererMenuItem(
            title = context.getString(R.string.filter_title),
            icon = Icons.Default.FilterAlt,
        ) {
            onToggleFilter()
        },
    )
}
