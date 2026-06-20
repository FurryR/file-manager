package io.furryr.file.agent

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.termux.terminal.TerminalSession

// ─── Block dispatcher ──────────────────────────────────────────────────────

/**
 * Warp-style block dispatcher composable.
 *
 * Routes each [Block] subclass to its dedicated rendering composable.
 * [CommandBlock], [OutputBlock], [ExitStatusBlock], [SuggestionBlock], and
 * [ConfirmBlock] each produce a self-contained timeline card; [AiBlock]
 * renders chat-style messages; [DividerBlock] produces a simple separator.
 *
 * @param block             The block model to render.
 * @param onSuggestionClick Called when the user taps a suggestion chip.
 * @param onConfirm         Called when the user confirms a [ConfirmBlock] action.
 * @param terminalSession   [TerminalSession] for [OutputBlock] rendering.
 * @param modifier          Standard Compose modifier.
 */
@Composable
fun BlockView(
    block: Block,
    onSuggestionClick: (String) -> Unit,
    onConfirm: (Boolean) -> Unit,
    terminalSession: TerminalSession? = null,
    modifier: Modifier = Modifier
) {
    when (block) {
        is AiBlock -> AiBlockView(block, modifier)
        is CommandBlock -> CommandBlockView(block, modifier)
        is OutputBlock -> OutputBlockView(block, terminalSession, modifier)
        is ExitStatusBlock -> ExitStatusBlockView(block, modifier)
        is SuggestionBlock -> SuggestionBlockView(block, onSuggestionClick, modifier)
        is ConfirmBlock -> ConfirmBlockView(
            block = block,
            onConfirm = { onConfirm(true) },
            onDeny = { onConfirm(false) },
            modifier = modifier
        )
    }
}

// ─── Divider block ────────────────────────────────────────────────────────

/**
 * Simple divider block.
 *
 * Renders as a full-width [HorizontalDivider] with small vertical padding
 * to separate timeline sections.
 */
@Composable
fun DividerBlockView(
    modifier: Modifier = Modifier
) {
    HorizontalDivider(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    )
}

// ─── AI block ─────────────────────────────────────────────────────────────

/**
 * AI / user message block composable.
 *
 * Renders a card with:
 * - Avatar icon (person for user, android for assistant).
 * - Role badge in a small color-coded surface.
 * - Content text styled per the message role.
 * - Streaming indicator (animated pulsing dot) when [BlockStatus.STREAMING].
 */
@Composable
fun AiBlockView(
    block: AiBlock,
    modifier: Modifier = Modifier
) {
    val isUser = block.role == Role.USER
    val isStreaming = block.status == BlockStatus.STREAMING
    val isFailed = block.status == BlockStatus.FAILED

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isUser)
                MaterialTheme.colorScheme.surfaceVariant
            else
                MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // ── Header: avatar + role badge + status ─────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Avatar
                Icon(
                    imageVector = if (isUser) Icons.Default.Face else Icons.Default.Android,
                    contentDescription = if (isUser) "User" else "Assistant",
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(
                            if (isUser)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.secondaryContainer
                        )
                        .padding(4.dp),
                    tint = if (isUser)
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else
                        MaterialTheme.colorScheme.onSecondaryContainer
                )

                // Role badge
                Surface(
                    shape = MaterialTheme.shapes.extraSmall,
                    color = when {
                        isFailed -> MaterialTheme.colorScheme.errorContainer
                        isStreaming -> MaterialTheme.colorScheme.tertiaryContainer
                        else -> MaterialTheme.colorScheme.secondaryContainer
                    },
                    contentColor = when {
                        isFailed -> MaterialTheme.colorScheme.onErrorContainer
                        isStreaming -> MaterialTheme.colorScheme.onTertiaryContainer
                        else -> MaterialTheme.colorScheme.onSecondaryContainer
                    }
                ) {
                    Text(
                        text = when {
                            isFailed -> "Failed"
                            isStreaming -> "Streaming"
                            else -> block.role.name.lowercase().replaceFirstChar { it.uppercase() }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                if (isStreaming) {
                    StreamingIndicator()
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ── Content ─────────────────────────────────────────────────
            Text(
                text = block.content,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isFailed)
                    MaterialTheme.colorScheme.error
                else
                    MaterialTheme.colorScheme.onSurface
            )

            // ── Tool calls (if any) ──────────────────────────────────────
            block.toolCalls?.takeIf { it.isNotEmpty() }?.let { calls ->
                Spacer(modifier = Modifier.height(8.dp))
                calls.forEach { toolCall ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                    ) {
                        Text(
                            text = "⚙",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = toolCall.name,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

// ─── Streaming indicator ──────────────────────────────────────────────────

/**
 * Animated pulsing dot indicating an active streaming response.
 */
@Composable
private fun StreamingIndicator(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "streaming")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "streamingAlpha"
    )

    Box(
        modifier = modifier
            .size(8.dp)
            .alpha(alpha)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.tertiary)
    )
}

// ─── Command block ────────────────────────────────────────────────────────

/**
 * Shell command block composable.
 *
 * Displays the command with a shell prompt prefix, monospace styling,
 * and a status icon reflecting [CommandStatus].
 */
@Composable
fun CommandBlockView(
    block: CommandBlock,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // ── Command line ────────────────────────────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Status icon
                CommandStatusIcon(block.status)

                // Prompt prefix (like $ or #)
                Text(
                    text = if (block.mode == ExecutionMode.ROOT_SHELL) "# " else "$ ",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Command text
                Text(
                    text = block.command,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
            }

            // ── Mode indicator ──────────────────────────────────────────
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = block.mode.displayName(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = "·",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = block.cwd,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ── Exit code + duration (only when completed) ──────────────
            // This mirrors ExitStatusBlockView inline
            if (block.exitCode != null) {
                Spacer(modifier = Modifier.height(6.dp))
                InlineExitStatus(block.exitCode, block.duration)
            }
        }
    }
}

/** Icon for command lifecycle status. */
@Composable
private fun CommandStatusIcon(status: CommandStatus) {
    val (icon, tint) = when (status) {
        CommandStatus.PENDING -> null to null
        CommandStatus.RUNNING -> Icons.Default.PlayArrow to MaterialTheme.colorScheme.tertiary
        CommandStatus.SUCCESS -> Icons.Default.Check to MaterialTheme.colorScheme.primary
        CommandStatus.FAILED -> Icons.Default.Close to MaterialTheme.colorScheme.error
    }

    if (icon != null && tint != null) {
        Icon(
            imageVector = icon,
            contentDescription = status.name.lowercase(),
            tint = tint,
            modifier = Modifier.size(18.dp)
        )
    } else {
        // Pending: small dot placeholder
        Spacer(modifier = Modifier.size(18.dp))
    }
}

// ─── Output block ─────────────────────────────────────────────────────────

/**
 * Terminal output block composable.
 *
 * Renders a read-only terminal snippet embedded inline.
 * In fullscreen mode a badge is displayed and the snippet uses more height.
 * In inline mode the view is height-constrained to avoid dominating the timeline.
 *
 * @param block    The [OutputBlock] with session metadata.
 * @param session  The [TerminalSession] whose buffer to display, or null.
 */
@Composable
fun OutputBlockView(
    block: OutputBlock,
    session: TerminalSession?,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // ── Header with status ──────────────────────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "Output",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (block.isFullscreen) {
                    Surface(
                        shape = MaterialTheme.shapes.extraSmall,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Fullscreen,
                                contentDescription = "Fullscreen",
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Fullscreen",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                } else {
                    Surface(
                        shape = MaterialTheme.shapes.extraSmall,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.FullscreenExit,
                                contentDescription = "Inline",
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Inline",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ── Terminal snippet ────────────────────────────────────────
            TerminalSnippet(
                session = session,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (block.isFullscreen)
                            Modifier.heightIn(min = 200.dp, max = 600.dp)
                        else
                            Modifier.heightIn(max = 200.dp)
                    )
            )
        }
    }
}

// ─── Exit status block ────────────────────────────────────────────────────

/**
 * Exit status summary block.
 *
 * Small card displaying exit code tinted green (0) or red (non-zero)
 * and command duration in seconds. Mirrors the exit status section
 * shown inline inside [CommandBlockView].
 *
 * @param block The [ExitStatusBlock] carrying exit code and duration.
 */
@Composable
fun ExitStatusBlockView(
    block: ExitStatusBlock,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ExitStatusContent(block.exitCode, block.duration)
        }
    }
}

/** Shared exit-code + duration display used by both exit-status and command blocks. */
@Composable
private fun InlineExitStatus(exitCode: Int, duration: Long?) {
    ExitStatusContent(exitCode, duration)
}

@Composable
private fun ExitStatusContent(exitCode: Int, duration: Long?) {
    val (label, color) = if (exitCode == 0) {
        "Exit: 0" to MaterialTheme.colorScheme.primary
    } else {
        "Exit: $exitCode" to MaterialTheme.colorScheme.error
    }

    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        fontFamily = FontFamily.Monospace,
        color = color
    )

    if (duration != null) {
        Text(
            text = "·",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        val seconds = duration / 1_000f
        Text(
            text = "%.1fs".format(seconds),
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ─── Suggestion block ─────────────────────────────────────────────────────

/**
 * Suggestion / quick-action chips block.
 *
 * Renders a [FlowRow] of clickable [FilterChip] components.
 * Tapping a chip calls [onSuggestionClick] with the suggestion text.
 *
 * @param block              The [SuggestionBlock] holding the suggestion strings.
 * @param onSuggestionClick  Callback for chip taps.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SuggestionBlockView(
    block: SuggestionBlock,
    onSuggestionClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Suggestions",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                block.suggestions.forEach { suggestion ->
                    FilterChip(
                        selected = false,
                        onClick = { onSuggestionClick(suggestion) },
                        label = {
                            Text(
                                text = suggestion,
                                style = MaterialTheme.typography.labelMedium
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            labelColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    )
                }
            }
        }
    }
}
