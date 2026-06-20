package io.furryr.file.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/** Human-readable badge label for the execution mode context selector. */
fun ExecutionMode.displayName(containerName: String? = null): String = when (this) {
    ExecutionMode.APP_UID -> "@app"
    ExecutionMode.CONTAINER -> if (containerName != null) "@$containerName" else "@container"
    ExecutionMode.ROOT_SHELL -> "#root"
    ExecutionMode.SHIZUKU -> "#shizuku"
}

/**
 * Fixed-bottom input bar composable for the agent screen.
 *
 * Contains:
 * - **Context badge**: tap to open execution-mode dropdown (APP_UID / CONTAINER / ROOT_SHELL / SHIZUKU).
 * - **Text input**: command or AI prompt input with IME send action.
 * - **Mode toggle**: switches between command mode (Terminal icon) and AI mode (Chat icon).
 * - **Send button**: triggers the current action; disabled when text is blank.
 * - **Fullscreen toggle**: placeholder for future TerminalView fullscreen mode.
 *
 * @param currentMode       Currently active [ExecutionMode] (default APP_UID).
 * @param currentContainer  Container name when [ExecutionMode] is CONTAINER, else null.
 * @param onSendCommand     Invoked on send in command mode with (text, selected mode).
 * @param onAskAi           Invoked on send in AI mode with the prompt text.
 * @param onModeChanged     Invoked when the user switches the execution mode via the context dropdown.
 * @param modifier          Standard Compose modifier.
 */
@Composable
fun AgentInputBar(
    currentMode: ExecutionMode = ExecutionMode.APP_UID,
    currentContainer: String? = null,
    onSendCommand: (String, ExecutionMode) -> Unit,
    onAskAi: (String) -> Unit,
    onModeChanged: (ExecutionMode) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var text by remember { mutableStateOf("") }
    var isAiMode by remember { mutableStateOf(false) }
    var contextMenuExpanded by remember { mutableStateOf(false) }

    val isTextBlank = text.isBlank()

    Surface(
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // ── Context Badge ──────────────────────────────────────────────
            Surface(
                onClick = { contextMenuExpanded = true },
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            ) {
                Text(
                    text = currentMode.displayName(currentContainer),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            // Execution-mode dropdown
            DropdownMenu(
                expanded = contextMenuExpanded,
                onDismissRequest = { contextMenuExpanded = false }
            ) {
                ExecutionMode.values().forEach { mode ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = mode.displayName(
                                    containerName = if (mode == ExecutionMode.CONTAINER) currentContainer else null
                                )
                            )
                        },
                        onClick = {
                            onModeChanged(mode)
                            contextMenuExpanded = false
                        },
                        leadingIcon = {
                            Text(
                                text = mode.displayName(
                                    containerName = if (mode == ExecutionMode.CONTAINER) currentContainer else null
                                ),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.width(4.dp))

            // ── Text Input ─────────────────────────────────────────────────
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = {
                    Text(if (isAiMode) "Ask AI..." else "Enter command...")
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        if (!isTextBlank) {
                            if (isAiMode) {
                                onAskAi(text)
                            } else {
                                onSendCommand(text, currentMode)
                            }
                            text = ""
                        }
                    }
                )
            )

            Spacer(modifier = Modifier.width(2.dp))

            // ── Mode Toggle ────────────────────────────────────────────────
            IconButton(
                onClick = { isAiMode = !isAiMode },
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = if (isAiMode) Icons.AutoMirrored.Filled.Chat else Icons.Default.Terminal,
                    contentDescription = if (isAiMode) "Switch to command mode" else "Switch to AI mode",
                    modifier = Modifier.size(20.dp)
                )
            }

            // ── Send Button ────────────────────────────────────────────────
            FilledIconButton(
                onClick = {
                    if (!isTextBlank) {
                        if (isAiMode) {
                            onAskAi(text)
                        } else {
                            onSendCommand(text, currentMode)
                        }
                        text = ""
                    }
                },
                enabled = !isTextBlank,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    modifier = Modifier.size(20.dp)
                )
            }

            // ── Fullscreen Toggle (placeholder) ────────────────────────────
            IconButton(
                onClick = { /* placeholder: TerminalView fullscreen mode */ },
                modifier = Modifier.size(40.dp),
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Fullscreen,
                    contentDescription = "Fullscreen terminal",
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        HorizontalDivider()
    }
}
