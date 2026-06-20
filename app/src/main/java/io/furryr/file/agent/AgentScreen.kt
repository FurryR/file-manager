package io.furryr.file.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.termux.terminal.TerminalSession

// ═════════════════════════════════════════════════════════════════════════════
//  AgentViewModel — contract stub (full implementation: Task 30)
// ═════════════════════════════════════════════════════════════════════════════

/**
 * View-model contract for the AI agent screen.
 *
 * Holds reactive block-model state, terminal session lifecycle, and
 * action delegations.  The concrete implementation lives in [io.furryr.file.agent.AgentViewModelImpl]
 * (Task 30).
 *
 * ## State
 * - [blocks]           — timeline blocks driving the LazyColumn.
 * - [terminalSession]  — attached [TerminalSession] for fullscreen mode and inline snippet rendering.
 * - [currentMode]      — active [ExecutionMode] displayed in the context badge.
 *
 * ## Actions
 * - [onSendCommand]    — execute a shell command in [currentMode].
 * - [onAskAi]          — send a natural-language prompt to the LLM.
 * - [onInsertSuggestion] — populate the input bar with a suggestion chip's text.
 * - [onConfirmAction]  — handle a [ConfirmBlock] decision (approved or denied),
 *                        forwarding to the block's embedded callback.
 * - [onModeChanged]    — switch the runtime [ExecutionMode].
 *
 * All members are `abstract` / `open` so that the concrete implementation can
 * be supplied later without breaking this screen's compilation.
 */
abstract class AgentViewModel {
    /** Ordered blocks for the timeline LazyColumn. */
    abstract val blocks: List<Block>

    /** Current terminal session; `null` before any session is created. */
    abstract val terminalSession: TerminalSession?

    /** Active execution context shown in badges and forwarded to [AgentInputBar]. */
    abstract val currentMode: ExecutionMode

    /** Execute a shell command through the current execution mode. */
    abstract fun onSendCommand(command: String, mode: ExecutionMode)

    /** Send a natural-language prompt to the configured LLM provider. */
    abstract fun onAskAi(prompt: String)

    /** Called when the user taps a suggestion chip — insert text into the input bar. */
    abstract fun onInsertSuggestion(text: String)

    /** Handle a confirm/deny decision on a [ConfirmBlock]. */
    abstract fun onConfirmAction(confirmed: Boolean, block: ConfirmBlock)

    /** Switch the active execution mode (APP_UID → ROOT_SHELL, etc.). */
    abstract fun onModeChanged(mode: ExecutionMode)
}

// ═════════════════════════════════════════════════════════════════════════════
//  AgentScreen — main agent composable
// ═════════════════════════════════════════════════════════════════════════════

/**
 * Full-screen AI agent composable.
 *
 * Assembles the complete agent UI:
 * - **TopAppBar** with context badge, settings, and close actions.
 * - **LazyColumn** timeline rendering each [Block] via [BlockView].
 * - **AgentInputBar** pinned at the bottom for command and AI prompt entry.
 * - **Fullscreen toggle** that expands the embedded terminal to fill the content area.
 *
 * Local UI state (fullscreen toggle, scroll position) is managed inside this
 * composable via [remember] + [mutableStateOf].  Block-modelling and terminal
 * lifecycle are delegated to [viewModel].
 *
 * @param viewModel  Contract providing blocks, terminal session, and action callbacks.
 * @param modifier   Standard Compose modifier applied to the root [Scaffold].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentScreen(
    viewModel: AgentViewModel,
    modifier: Modifier = Modifier
) {
    // ── Local UI state ──────────────────────────────────────────────────
    var isFullscreen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val blocks = viewModel.blocks

    // ── Auto-scroll to bottom on new blocks ──────────────────────────────
    LaunchedEffect(blocks.size) {
        if (blocks.isNotEmpty()) {
            listState.animateScrollToItem(blocks.lastIndex)
        }
    }

    // ── Scaffold ────────────────────────────────────────────────────────
    Scaffold(
        modifier = modifier,
        topBar = {
            AgentTopBar(
                currentMode = viewModel.currentMode,
                onEnterFullscreen = { isFullscreen = true },
                modifier = Modifier
            )
        },
        bottomBar = {
            // Hide the input bar during fullscreen terminal mode.
            if (!isFullscreen) {
                AgentInputBar(
                    currentMode = viewModel.currentMode,
                    onSendCommand = viewModel::onSendCommand,
                    onAskAi = viewModel::onAskAi,
                    onModeChanged = viewModel::onModeChanged
                )
            }
        }
    ) { paddingValues ->
        if (isFullscreen) {
            // ── Fullscreen terminal ───────────────────────────────────
            FullscreenTerminal(
                session = viewModel.terminalSession,
                onExitFullscreen = { isFullscreen = false },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            )
        } else {
            // ── Timeline (block list) ─────────────────────────────────
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    items = blocks,
                    key = { block -> block.id }
                ) { block ->
                    BlockView(
                        block = block,
                        onSuggestionClick = { suggestion ->
                            viewModel.onInsertSuggestion(suggestion)
                        },
                        onConfirm = { confirmed ->
                            if (block is ConfirmBlock) {
                                viewModel.onConfirmAction(confirmed, block)
                            }
                        },
                        terminalSession = when (block) {
                            is OutputBlock -> viewModel.terminalSession
                            else -> null
                        },
                        modifier = Modifier.animateItem()
                    )
                }
            }
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  Internal composables
// ═════════════════════════════════════════════════════════════════════════════

/**
 * Top app bar for the agent screen.
 *
 * Displays the "Agent" title alongside a context badge showing the active
 * [ExecutionMode], plus action buttons for settings, fullscreen, and close.
 *
 * @param currentMode       Active execution mode rendered in the context badge.
 * @param onEnterFullscreen Called when the user taps the fullscreen action.
 * @param modifier          Standard Compose modifier.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentTopBar(
    currentMode: ExecutionMode,
    onEnterFullscreen: () -> Unit,
    modifier: Modifier = Modifier
) {
    CenterAlignedTopAppBar(
        title = {
            androidx.compose.foundation.layout.Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Agent",
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(modifier = Modifier.width(8.dp))
                ContextBadge(mode = currentMode)
            }
        },
        actions = {
            IconButton(onClick = onEnterFullscreen) {
                Icon(
                    imageVector = Icons.Default.Fullscreen,
                    contentDescription = "Enter fullscreen"
                )
            }
            IconButton(onClick = { /* settings — deferred to integration */ }) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings"
                )
            }
            IconButton(onClick = { /* close — deferred to integration */ }) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close"
                )
            }
        },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        modifier = modifier
    )
}

/**
 * Compact context badge showing the active [ExecutionMode].
 *
 * Reuses [ExecutionMode.displayName] from [AgentInputBar] for consistency.
 *
 * @param mode The active execution mode.
 */
@Composable
private fun ContextBadge(mode: ExecutionMode) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    ) {
        Text(
            text = mode.displayName(),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

/**
 * Fullscreen terminal overlay.
 *
 * Renders [TerminalViewComposable] filling the available content area with a
 * floating action button to exit fullscreen mode.
 *
 * @param session         The active terminal session to display.
 * @param onExitFullscreen Called when the exit-fullscreen button is tapped.
 * @param modifier        Standard Compose modifier.
 */
@Composable
private fun FullscreenTerminal(
    session: TerminalSession?,
    onExitFullscreen: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        TerminalViewComposable(
            session = session,
            modifier = Modifier.fillMaxSize(),
            enabled = true
        )

        FloatingActionButton(
            onClick = onExitFullscreen,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp),
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Exit fullscreen"
            )
        }
    }
}
