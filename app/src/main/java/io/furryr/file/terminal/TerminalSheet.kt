package io.furryr.file.terminal

import android.app.Activity
import android.content.Context
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
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
        Terminal.close(tab.id)
        if (tabs.isEmpty()) {
            onDismiss()
            return
        }
        if (wasActive) Terminal.active?.let(::showTabToast)
    }

    fun newTab() {
        val create = newTerminal ?: return
        val tab = create(context) ?: return
        Terminal.open(tab)
        showDrawer = false
        showTabToast(tab)
    }

    /** Drop tabs whose backing session is gone; the PTY map is the truth. */
    fun pruneTabs() {
        Terminal.prune()
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
        if (!visible) return@LaunchedEffect
        DaemonManager.warmUp()
        pruneTabs()
        if (tabs.isEmpty()) newTab()
        if (tabs.isEmpty()) onDismiss()
    }

    DisposableEffect(Unit) {
        val sessionListener: () -> Unit = {
            pruneTabs()
            if (tabs.isEmpty()) currentOnDismiss()
        }
        SessionManager.addSessionChangeListener(sessionListener)
        onDispose {
            SessionManager.removeSessionChangeListener(sessionListener)
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
                            detectDragGestures(
                                onDragEnd = {
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
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    if (!showDrawer && abs(dragAmount.x) > DRAWER_DRAG_THRESHOLD) {
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
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectHorizontalDragGestures(
                                    onDragEnd = { },
                                    onHorizontalDrag = { change, dragAmount ->
                                        if (!showDrawer && dragAmount > DRAWER_DRAG_THRESHOLD) {
                                            change.consume()
                                            showDrawer = true
                                        }
                                    },
                                )
                            },
                    ) {
                        val active = Terminal.active
                        if (active != null) {
                            val content = Terminal.contentFor(active.id)
                            if (content != null) {
                                content(visible, Modifier.fillMaxSize())
                            } else {
                                Box(Modifier.fillMaxSize())
                            }
                        } else {
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
