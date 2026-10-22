package io.furryr.file.ui.screens

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.furryr.file.R
import io.furryr.file.agent.AgentSettings

@Composable
internal fun AgentSettingsItem() {
    val context = LocalContext.current
    val provider = remember {
        runCatching { AgentSettings.activeProvider(context) }.getOrNull()
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                context.startActivity(Intent(context, AgentProvidersActivity::class.java))
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(stringResource(R.string.agent_settings), style = MaterialTheme.typography.bodyLarge)
        Text(
            provider?.let { "${it.name} · ${it.baseUrl}" }
                ?: stringResource(R.string.agent_settings_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun SessionSettingsItem() {
    val context = LocalContext.current
    val count = runCatching { AgentSettings.sessions(context).size }.getOrDefault(0)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                context.startActivity(Intent(context, AgentSessionsActivity::class.java))
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(stringResource(R.string.agent_sessions), style = MaterialTheme.typography.bodyLarge)
        Text(
            if (count > 0) stringResource(R.string.agent_sessions_desc) + " ($count)"
            else stringResource(R.string.agent_sessions_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun McpSettingsItem() {
    val context = LocalContext.current
    val count = runCatching { AgentSettings.mcpServers(context).size }.getOrDefault(0)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                context.startActivity(Intent(context, AgentMcpActivity::class.java))
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(stringResource(R.string.agent_mcp), style = MaterialTheme.typography.bodyLarge)
        Text(
            if (count > 0) stringResource(R.string.agent_mcp_desc) + " ($count)"
            else stringResource(R.string.agent_mcp_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
