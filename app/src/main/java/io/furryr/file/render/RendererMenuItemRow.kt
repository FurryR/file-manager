package io.furryr.file.render

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/** One renderer-contributed menu row, including submenu and checkbox states. */
@Composable
internal fun RendererMenuItemRow(
    item: RendererMenuItem,
    onAction: (RendererMenuItem) -> Unit,
    onSubmenu: (RendererMenuItem) -> Unit,
) {
    val children = item.children.orEmpty()
    DropdownMenuItem(
        text = { Text(item.title) },
        leadingIcon = item.icon?.let { icon -> { Icon(icon, null) } },
        trailingIcon = when {
            children.isNotEmpty() -> {
                { Icon(Icons.Default.ChevronRight, contentDescription = null) }
            }
            item.checked != null -> {
                {
                    Checkbox(
                        checked = item.checked == true,
                        onCheckedChange = null,
                        enabled = item.enabled,
                    )
                }
            }
            else -> null
        },
        enabled = item.enabled,
        onClick = {
            if (children.isEmpty()) onAction(item) else onSubmenu(item)
        },
    )
}
