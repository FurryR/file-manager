package io.furryr.file.ui.shell

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.TabRow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.furryr.file.R
import io.furryr.file.model.FileTab
import io.furryr.file.ui.components.DraggableFileTab

/**
 * The controlled file-tab strip: reorder, select, close and add. It owns no
 * tab state; the shell passes the current list and receives the actions.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FileTabStrip(
    tabs: List<FileTab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onClose: (Int) -> Unit,
    onMove: (String, Int) -> Boolean,
    onNewTab: () -> Unit,
    onShowSessions: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        BoxWithConstraints(modifier = Modifier.weight(1f)) {
            val showClose = tabs.size > 1
            if (maxWidth >= 90.dp * tabs.size.toFloat()) {
                TabRow(
                    selectedTabIndex = selectedIndex,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    tabs.forEachIndexed { index, tab ->
                        DraggableFileTab(
                            index = index,
                            tab = tab,
                            selected = index == selectedIndex,
                            showClose = showClose,
                            onClick = { onSelect(index) },
                            onClose = { onClose(index) },
                            onMove = onMove,
                        )
                    }
                }
            } else {
                ScrollableTabRow(
                    selectedTabIndex = selectedIndex,
                    modifier = Modifier.fillMaxWidth(),
                    edgePadding = 0.dp,
                ) {
                    tabs.forEachIndexed { index, tab ->
                        DraggableFileTab(
                            index = index,
                            tab = tab,
                            selected = index == selectedIndex,
                            showClose = showClose,
                            onClick = { onSelect(index) },
                            onClose = { onClose(index) },
                            onMove = onMove,
                        )
                    }
                }
            }
        }
        Box(
            modifier = Modifier
                .size(48.dp)
                .combinedClickable(
                    onClick = onNewTab,
                    onLongClick = onShowSessions,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.cd_new_tab))
        }
    }
}
