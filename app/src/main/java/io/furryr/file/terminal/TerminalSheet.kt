package io.furryr.file.terminal

import android.app.Activity
import android.content.Context
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import io.furryr.file.ui.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.furryr.file.R
import io.furryr.file.core.model.FileEntry
import io.furryr.file.daemon.DaemonManager
import kotlin.math.abs
import kotlinx.coroutines.launch

private const val SmallRatio = 0.50f
private const val LargeRatio = 0.90f
internal const val KEY_REPEAT_DELAY_MS = 400L
internal const val KEY_REPEAT_INTERVAL_MS = 60L
internal val DrawerWidth = 180.dp
internal const val DRAWER_DRAG_THRESHOLD = 50f

/**
 * Terminal sheet.
 *
 * The tab list itself belongs to [Terminal]; this composable only renders it,
 * forwards activate/close/open and owns the bottom-sheet behaviour. A tab's
 * body is a [TerminalTab] supplied by the active file-tab renderer, so
 * terminal pixels, the extra-key row, IME focus and per-tab failures never
 * live in the shell.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TerminalSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    onShow: () -> Unit,
    onDropAccepted: () -> Unit = {},
    /** Null when the active page cannot host a new terminal; existing tabs stay usable. */
    newTerminal: ((Context) -> TerminalTab?)? = null,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val focusManager = LocalFocusManager.current

    val tabs = Terminal.tabs
    var showDrawer by remember { mutableStateOf(false) }
    // Tabs may outlive this composition (activity recreate); the menu key must
    // always reach the drawer state that is visible now, never a stale one.
    SideEffect { Terminal.setDrawerOpener { showDrawer = true } }
    var dropHovered by remember { mutableStateOf(false) }
    var dragDropped by remember { mutableStateOf(false) }
    var dragBounds by remember { mutableStateOf(Rect.Zero) }
    var currentToast by remember { mutableStateOf<Toast?>(null) }
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
    val smallTargetPx = screenHeightPx * SmallRatio
    val largeTargetPx = screenHeightPx * LargeRatio
    val currentSmallTargetPx by rememberUpdatedState(smallTargetPx)
    val currentDropTab by rememberUpdatedState(Terminal.active)
    val currentVisible by rememberUpdatedState(visible)
    val currentOnShow by rememberUpdatedState(onShow)
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val currentOnDropAccepted by rememberUpdatedState(onDropAccepted)

    fun inDropZone(position: Offset): Boolean {
        if (dragBounds.height <= 0f) return false
        val top = (dragBounds.bottom - currentSmallTargetPx).coerceAtLeast(dragBounds.top)
        return position.x in dragBounds.left..dragBounds.right && position.y in top..dragBounds.bottom
    }

    val dropTarget = remember {
        object : DragAndDropTarget {
            override fun onStarted(event: DragAndDropEvent) {
                dragDropped = false
                if (!currentVisible) currentOnShow()
            }

            override fun onEntered(event: DragAndDropEvent) {
                if (!currentVisible) currentOnShow()
            }

            override fun onMoved(event: DragAndDropEvent) {
                val drag = event.toAndroidDragEvent()
                dropHovered = currentVisible && inDropZone(Offset(drag.x, drag.y))
            }

            override fun onExited(event: DragAndDropEvent) { dropHovered = false }

            override fun onEnded(event: DragAndDropEvent) {
                dropHovered = false
                if (!dragDropped) currentOnDismiss()
                dragDropped = false
            }

            override fun onDrop(event: DragAndDropEvent): Boolean {
                dropHovered = false
                val drag = event.toAndroidDragEvent()
                if (!inDropZone(Offset(drag.x, drag.y))) {
                    // A release outside the terminal is a cancelled drop.
                    currentOnDismiss()
                    return false
                }
                val entry = drag.localState as? FileEntry ?: return false
                val tab = currentDropTab?.takeIf { currentVisible } ?: return false
                dragDropped = true
                currentOnDropAccepted()
                return tab.acceptDrop(entry)
            }
        }
    }

    fun showTabToast(tab: TerminalTab) {
        val index = tabs.indexOfFirst { it.id == tab.id }
        if (index < 0) return
        val yOffset = with(density) { 96.dp.roundToPx() }
        currentToast?.cancel()
        currentToast = Toast.makeText(context, "[${index + 1}] ${tab.title}", Toast.LENGTH_SHORT).apply {
            setGravity(android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL, 0, yOffset)
        }
        currentToast?.show()
    }

    fun closeTab(tab: TerminalTab) {
        val wasActive = Terminal.activeId == tab.id
        if (!Terminal.unload(tab.id)) return
        if (tabs.isEmpty()) {
            onDismiss()
            return
        }
        if (wasActive) Terminal.active?.let(::showTabToast)
    }

    // A tab body asks the shell to unload itself (connection-lost overlay).
    SideEffect {
        Terminal.setCloseRequester { id ->
            val tab = tabs.firstOrNull { it.id == id } ?: return@setCloseRequester
            closeTab(tab)
        }
    }

    fun newTab() {
        val create = newTerminal ?: return
        val tab = create(context) ?: return
        Terminal.open(tab)
        showDrawer = false
        showTabToast(tab)
    }

    val sheetHeightPx = remember { Animatable(0f) }
    var wasVisible by remember { mutableStateOf(false) }

    BackHandler(enabled = visible) { onDismiss() }

    LaunchedEffect(visible) {
        val isClosing = wasVisible && !visible
        wasVisible = visible
        if (visible) {
            sheetHeightPx.animateTo(smallTargetPx, spring(dampingRatio = Spring.DampingRatioLowBouncy))
        } else {
            if (isClosing) {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(view.windowToken, 0)
                focusManager.clearFocus(force = true)
                (context as? Activity)?.currentFocus?.clearFocus()
                // The sheet owns this drawer overlay; it must not reappear
                // next time the terminal is opened.
                showDrawer = false
            }
            sheetHeightPx.animateTo(0f, spring(dampingRatio = Spring.DampingRatioNoBouncy))
        }
    }

    LaunchedEffect(visible) {
        if (visible) DaemonManager.warmUp()
    }

    // A tab can retire itself when its page closes (DevTools). When that
    // leaves an open sheet empty, create the terminal for the current page
    // instead of retracting the sheet. A page that cannot host a terminal
    // keeps the empty state; one automatic fill per opening prevents a
    // factory that cannot keep a tab from spinning.
    var autoFillUsed by remember { mutableStateOf(false) }
    LaunchedEffect(visible, tabs.size) {
        if (!visible) {
            autoFillUsed = false
            return@LaunchedEffect
        }
        if (tabs.isNotEmpty() || autoFillUsed) return@LaunchedEffect
        autoFillUsed = true
        if (newTerminal == null) return@LaunchedEffect
        DaemonManager.warmUp()
        newTab()
    }

    DisposableEffect(Unit) {
        onDispose {
            // These hooks live in the process-scoped Terminal object; leaving
            // this composition's lambdas installed would keep a disposed
            // composition (and its Activity) reachable.
            Terminal.setDrawerOpener(null)
            Terminal.setCloseRequester(null)
            Terminal.resetTabs()
            currentToast?.cancel()
            currentToast = null
        }
    }

    // Observe the whole drag area even while the terminal is collapsed.
    Box(modifier = Modifier
        .fillMaxSize()
        .onGloballyPositioned { dragBounds = it.boundsInRoot() }
        .dragAndDropTarget(
            shouldStartDragAndDrop = { it.toAndroidDragEvent().localState is FileEntry },
            target = dropTarget,
        )
    ) {
        if (sheetHeightPx.value > 0f) {
            val alpha = ((sheetHeightPx.value / smallTargetPx).coerceIn(0f, 1f) * 0.4f)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = alpha))
                    .pointerInput(Unit) { detectTapGestures { currentOnDismiss() } },
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // adjustResize keeps this height bounded by the space above the IME.
                    .height(with(density) { sheetHeightPx.value.toDp() })
                    .shadow(elevation = 16.dp, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .background(
                        MaterialTheme.colorScheme.surfaceContainer,
                        RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    )
                    .then(
                        if (dropHovered) {
                            Modifier.border(
                                2.dp,
                                MaterialTheme.colorScheme.primary,
                                RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                            )
                        } else {
                            Modifier
                        },
                    ),
            ) {
                // Handle row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp)
                        .pointerInput(smallTargetPx, largeTargetPx) {
                            var horizontalDrag = 0f
                            detectDragGestures(
                                onDragStart = { horizontalDrag = 0f },
                                onDragEnd = {
                                    horizontalDrag = 0f
                                    val current = sheetHeightPx.value
                                    val target = when {
                                        current >= (smallTargetPx + largeTargetPx) / 2 -> largeTargetPx
                                        current >= smallTargetPx * 0.5f -> smallTargetPx
                                        else -> 0f
                                    }
                                    if (target == 0f) {
                                        onDismiss()
                                    } else {
                                        scope.launch {
                                            sheetHeightPx.animateTo(
                                                target,
                                                spring(dampingRatio = Spring.DampingRatioLowBouncy),
                                            )
                                        }
                                    }
                                },
                                onDragCancel = { horizontalDrag = 0f },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    // Touch slop and frame deltas are small; the
                                    // gesture must accumulate before it counts.
                                    horizontalDrag += dragAmount.x
                                    if (!showDrawer && abs(horizontalDrag) > DRAWER_DRAG_THRESHOLD) {
                                        showDrawer = true
                                    }
                                    scope.launch {
                                        val newHeight = (sheetHeightPx.value - dragAmount.y)
                                            .coerceIn(0f, largeTargetPx)
                                        sheetHeightPx.snapTo(newHeight)
                                    }
                                },
                            )
                        },
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .width(32.dp)
                                .height(4.dp)
                                .background(
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                    RoundedCornerShape(2.dp),
                                ),
                        )
                    }
                }

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    val drawerGesture = Terminal.active is TerminalViewTab
                    val drawerDrag = Modifier.pointerInput(Unit) {
                        var dragged = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { dragged = 0f },
                            onDragEnd = { dragged = 0f },
                            onDragCancel = { dragged = 0f },
                            onHorizontalDrag = { change, dragAmount ->
                                // A drag is delivered as small per-frame deltas;
                                // only the accumulated distance crosses the
                                // threshold that opens the tab drawer.
                                dragged += dragAmount
                                if (!showDrawer && dragged > DRAWER_DRAG_THRESHOLD) {
                                    change.consume()
                                    showDrawer = true
                                }
                            },
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(if (drawerGesture) drawerDrag else Modifier),
                    ) {
                        val active = Terminal.active
                        if (active != null) {
                            TerminalBodyHost(
                                active,
                                visible,
                                Modifier.fillMaxSize(),
                            )
                        } else if (visible) {
                            // A closing sheet has no tabs to describe; showing
                            // the empty state here only flashes before it hides.
                            TerminalEmptyState(
                                canCreateTerminal = newTerminal != null,
                                onNewTab = ::newTab,
                            )
                        }
                    }

                    // Overlay to dismiss drawer when tapping right half
                    if (showDrawer) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) { detectTapGestures { showDrawer = false } }
                                .background(androidx.compose.ui.graphics.Color.Transparent),
                        )
                    }

                    TerminalDrawerOverlay(
                        visible = showDrawer,
                        tabs = tabs,
                        activeId = Terminal.activeId,
                        canCreateTerminal = newTerminal != null,
                        onSwitchTab = { tab ->
                            Terminal.activate(tab.id)
                            showDrawer = false
                            showTabToast(tab)
                        },
                        onCloseTab = ::closeTab,
                        onNewTab = ::newTab,
                    )
                }
            }
        }
    }
}

/**
 * The active tab's body: every Termux tab shares this one terminal surface, so
 * a tab switch rebinds the session instead of moving an AndroidView between
 * parents (Compose cannot move a view that still has a parent). Tabs that own
 * their own body (the WebView DevTools tab) keep their committed content.
 */
@Composable
private fun TerminalBodyHost(
    tab: TerminalTab,
    enabled: Boolean,
    modifier: Modifier,
) {
    if (tab is TerminalViewTab) {
        TerminalSurfaceBody(tab, enabled, modifier)
    } else {
        val content = Terminal.contentFor(tab.id)
        if (content != null) content(enabled, modifier) else Box(modifier)
    }
}

@Suppress("DEPRECATION") // ADJUST_RESIZE is still the IME contract below API 30; targetSdk is 28.
@Composable
private fun TerminalSurfaceBody(
    tab: TerminalViewTab,
    enabled: Boolean,
    modifier: Modifier,
) {
    val activity = LocalContext.current as? Activity
    // The terminal is the only screen that holds the IME above its content.
    // The mode follows the active tab instead of the sheet flag, so it cannot
    // leak to a tab that is not showing a terminal.
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
    val current = tab.session
    val failure = tab.error
    // The surface is bound before the PTY exists. Focus it as soon as the
    // sheet is shown so the first tap only has to raise the IME instead of
    // racing the focus hand-off; a newly connected session asks for the IME
    // itself once it becomes usable.
    LaunchedEffect(tab, enabled, current) {
        if (!enabled) return@LaunchedEffect
        tab.inputState.requestFocus()
        if (current != null && !tab.connectionLost) tab.inputState.requestIme()
    }
    Column(modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            TerminalViewComposable(
                session = current,
                modifier = Modifier.fillMaxSize(),
                enabled = enabled && current != null,
                inputState = tab.inputState,
            )
            if (current == null && failure != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Terminal,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.size(48.dp),
                        )
                        Text(
                            failure,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        if (!tab.connecting) {
                            TextButton(onClick = tab::retry, modifier = Modifier.padding(top = 4.dp)) {
                                Text(stringResource(R.string.terminal_retry))
                            }
                        }
                    }
                }
            }
            if (tab.connectionLost) {
                // Plain in-content overlay: the terminal drawer stays usable.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Terminal,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                            modifier = Modifier.size(48.dp),
                        )
                        Text(
                            stringResource(R.string.terminal_connection_lost_title),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                        Text(
                            stringResource(
                                if (tab.remote) R.string.terminal_connection_lost
                                else R.string.terminal_exited_abnormally,
                            ),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 4.dp, start = 16.dp, end = 16.dp),
                        )
                        TextButton(onClick = tab::requestClose, modifier = Modifier.padding(top = 4.dp)) {
                            Text(stringResource(R.string.terminal_close))
                        }
                    }
                }
            }
        }
        // The row stays in the layout while a session is connecting or has
        // been lost: hiding it would shrink the terminal and then move it
        // again once the session arrives.
        TerminalExtraKeys(
            inputState = tab.inputState,
            enabled = enabled && current != null && !tab.connectionLost,
            onOpenDrawer = tab::requestOpenDrawer,
        )
    }
}
