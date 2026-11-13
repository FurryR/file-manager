package io.furryr.file.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.furryr.file.R

/** Shell-owned terminal tab drawer; the rows only switch/close/append tabs. */
@Composable
internal fun TerminalDrawerOverlay(
    visible: Boolean,
    tabs: List<TerminalTab>,
    activeId: String?,
    canCreateTerminal: Boolean,
    onSwitchTab: (TerminalTab) -> Unit,
    onCloseTab: (TerminalTab) -> Unit,
    onNewTab: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally { -it },
        exit = slideOutHorizontally { -it },
    ) {
        Box(
            modifier = Modifier
                .width(DrawerWidth)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            TerminalDrawer(
                tabs = tabs,
                activeId = activeId,
                canCreateTerminal = canCreateTerminal,
                onSwitchTab = onSwitchTab,
                onCloseTab = onCloseTab,
                onNewTab = onNewTab,
            )
        }
    }
}

@Composable
internal fun TerminalDrawer(
    tabs: List<TerminalTab>,
    activeId: String?,
    canCreateTerminal: Boolean,
    onSwitchTab: (TerminalTab) -> Unit,
    onCloseTab: (TerminalTab) -> Unit,
    onNewTab: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            stringResource(R.string.terminal_drawer_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            itemsIndexed(tabs, key = { _, tab -> tab.id }) { idx, tab ->
                val isActive = tab.id == activeId
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSwitchTab(tab) }
                        .background(
                            if (isActive) MaterialTheme.colorScheme.secondaryContainer
                            else Color.Transparent,
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(
                        "[${idx + 1}] ${tab.title}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                        color = if (isActive) MaterialTheme.colorScheme.onSecondaryContainer
                        else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.cd_close_tab),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(16.dp)
                            .clickable { onCloseTab(tab) },
                    )
                }
            }
        }
        Button(
            onClick = onNewTab,
            enabled = canCreateTerminal,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
            Text(stringResource(R.string.drawer_new_terminal), maxLines = 1)
        }
    }
}

/** Shown only when no terminal tab exists; per-tab failures live in the renderer. */
@Composable
internal fun TerminalEmptyState(
    canCreateTerminal: Boolean,
    onNewTab: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize().padding(16.dp),
    ) {
        Icon(
            Icons.Default.Terminal,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(48.dp),
        )
        Text(
            stringResource(R.string.terminal_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.padding(top = 8.dp),
        )
        if (canCreateTerminal) {
            TextButton(onClick = onNewTab, modifier = Modifier.padding(top = 4.dp)) {
                Text(stringResource(R.string.drawer_new_terminal))
            }
        }
    }
}
