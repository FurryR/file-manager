package io.furryr.file.terminal

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.furryr.file.R
import io.furryr.file.core.model.FileEntry
import java.util.UUID

/** The page a DevTools tab is attached to. */
interface WebDevToolsHost {
    val devToolsLogs: List<DevToolsLog>
    val devToolsAlive: Boolean
    fun evaluateJs(script: String)
    fun clearDevToolsLogs()
    fun refreshPage(): Boolean
}

/** One line in the web tab's DevTools console. */
data class DevToolsLog(
    val level: String,
    val message: String,
    val source: String? = null,
    val line: Int = 0,
) {
    fun display(): String = when {
        level == "input" -> "> $message"
        level == "result" -> "< $message"
        source != null -> "$source:$line  $message"
        else -> message
    }
}

/**
 * The DevTools tab opened from a web page: console output and JavaScript
 * evaluation against that page. It has no PTY; the shell removes the tab when
 * the page is gone, and the console history stays readable until then.
 */
internal class WebDevToolsTab(
    private val host: WebDevToolsHost,
    private val label: String,
) : TerminalTab {
    override val id: String = UUID.randomUUID().toString()
    override val title: String get() =
        if (host.devToolsAlive) label else "$label (closed)"

    override val alive: Boolean get() = host.devToolsAlive
    override fun acceptDrop(entry: FileEntry): Boolean = false
    override fun dispose() {}

    override suspend fun enter(ctx: TerminalContext) {
        ctx.commit { enabled, modifier -> DevToolsBody(enabled, modifier) }
    }

    // Legacy window resizing is still the correct IME mode for target SDK 28.
    @Suppress("DEPRECATION")
    @Composable
    private fun DevToolsBody(enabled: Boolean, modifier: Modifier) {
        val activity = LocalContext.current as? Activity
        DisposableEffect(activity, enabled) {
            if (enabled) {
                activity?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
            onDispose {
                if (enabled) {
                    activity?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
                }
            }
        }

        val logs = host.devToolsLogs
        val listState = rememberLazyListState()
        var input by remember { mutableStateOf("") }

        LaunchedEffect(logs.size) {
            if (logs.isNotEmpty()) listState.animateScrollToItem(logs.lastIndex)
        }

        Column(modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(
                    stringResource(R.string.devtools_console),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = host::clearDevToolsLogs, enabled = enabled) {
                    Text(stringResource(R.string.devtools_clear))
                }
                TextButton(onClick = { host.refreshPage() }, enabled = enabled) {
                    Text(stringResource(R.string.devtools_reload))
                }
            }

            if (!host.devToolsAlive) {
                Text(
                    stringResource(R.string.devtools_page_closed),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp),
            ) {
                itemsIndexed(logs) { _, log ->
                    Text(
                        text = log.display(),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        color = logColor(log.level),
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(8.dp),
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.devtools_input)) },
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    maxLines = 3,
                )
                IconButton(
                    onClick = {
                        host.evaluateJs(input)
                        input = ""
                    },
                    enabled = enabled && input.isNotBlank(),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(R.string.devtools_run),
                    )
                }
            }
        }
    }

    @Composable
    private fun logColor(level: String) = when (level) {
        "error" -> MaterialTheme.colorScheme.error
        "warning" -> MaterialTheme.colorScheme.tertiary
        "input" -> MaterialTheme.colorScheme.primary
        "result" -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}
