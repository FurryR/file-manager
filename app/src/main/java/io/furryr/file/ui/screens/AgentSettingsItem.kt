package io.furryr.file.ui.screens

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.furryr.file.R
import io.furryr.file.agent.AgentSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads a settings summary on the IO dispatcher, once per resume.
 *
 * These summaries used to run inside composition: [AgentSettings.sessions]
 * lists and parses every saved conversation, and the MCP/provider rows decrypt
 * keystore values. Composition runs again on every frame of the theme colour
 * animation (the AMOLED toggle is one such animation), so the old calls stalled
 * the UI while the theme faded and on the first frame after opening Settings.
 * Keeping the work off the main thread and out of recomposition makes those
 * frames cheap.
 */
@Composable
private fun <T> ioOnResume(initial: T, load: () -> T): T {
    // -1 means "not resumed yet". Every host of these rows is an Activity, so
    // the first ON_RESUME always arrives after composition; loading there keeps
    // opening Settings to exactly one read per row.
    var resumeGeneration by remember { mutableIntStateOf(-1) }
    LifecycleResumeEffect(Unit) {
        resumeGeneration++
        onPauseOrDispose { }
    }
    val value by produceState(initial, resumeGeneration) {
        if (resumeGeneration < 0) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching(load).getOrDefault(initial)
        }
    }
    return value
}

@Composable
internal fun AgentSettingsItem() {
    val context = LocalContext.current
    val provider = ioOnResume<String?>(initial = null) {
        AgentSettings.activeProvider(context)?.let {
            if (providerUsesFixedUrl(it)) it.name else "${it.name} · ${it.baseUrl}"
        } ?: context.getString(R.string.agent_settings_desc)
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
            provider.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun SessionSettingsItem() {
    val context = LocalContext.current
    val summary = ioOnResume<String?>(initial = null) {
        val count = AgentSettings.sessions(context).size
        if (count > 0) context.getString(R.string.agent_sessions_desc) + " ($count)"
        else context.getString(R.string.agent_sessions_empty)
    }
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
            summary.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun McpSettingsItem() {
    val context = LocalContext.current
    val summary = ioOnResume<String?>(initial = null) {
        val count = AgentSettings.mcpServers(context).size
        if (count > 0) context.getString(R.string.agent_mcp_desc) + " ($count)"
        else context.getString(R.string.agent_mcp_empty)
    }
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
            summary.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
