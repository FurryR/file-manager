package io.furryr.file.render

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Refresh
import io.furryr.file.R

/** Menu shared by the directory renderer and legacy directory callers. */
object DirectoryRenderer {
    fun menuItems(
        context: Context,
        canRefresh: Boolean,
        onRefresh: () -> Unit,
        onToggleFilter: () -> Unit,
        onSort: () -> Unit,
    ): List<RendererMenuItem> = buildList {
        if (canRefresh) {
            add(
                RendererMenuItem(
                    title = context.getString(R.string.refresh),
                    icon = Icons.Default.Refresh,
                    onClick = onRefresh,
                ),
            )
        }
        add(
            RendererMenuItem(
                title = context.getString(R.string.filter_title),
                icon = Icons.Default.FilterAlt,
                onClick = onToggleFilter,
            ),
        )
        add(
            RendererMenuItem(
                title = context.getString(R.string.sort_title),
                icon = Icons.AutoMirrored.Filled.Sort,
                onClick = onSort,
            ),
        )
    }
}
