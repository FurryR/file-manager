package io.furryr.file

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.termux.terminal.TerminalSession
import io.furryr.file.agent.SessionManager
import io.furryr.file.container.ContainerUriResolver
import io.furryr.file.container.ContainerPaths
import io.furryr.file.core.model.FileEntry
import io.furryr.file.daemon.shellQuote
import io.furryr.file.agent.TerminalInputState
import io.furryr.file.agent.TerminalViewComposable
import io.furryr.file.daemon.DaemonManager
import android.app.Activity
import android.content.Context
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val SmallRatio = 0.50f
private const val LargeRatio = 0.90f
internal const val KEY_REPEAT_DELAY_MS = 400L
internal const val KEY_REPEAT_INTERVAL_MS = 60L
internal val DrawerWidth = 180.dp
private const val TAG = "TerminalSheet"
internal const val DRAWER_DRAG_THRESHOLD = 50f

internal data class TerminalTab(
    val sessionId: String,
    val session: TerminalSession,
    val baseLabel: String,
    val title: String? = null,
) {
    val label: String
        get() = title?.takeIf { it.isNotBlank() } ?: baseLabel
}

private fun sanitizedTitle(raw: String?): String? =
    raw?.filterNot { it.isISOControl() }?.trim()?.takeIf { it.isNotEmpty() }

private fun sessionLabel(type: SessionManager.SessionType): String = when (type) {
    is SessionManager.SessionType.Container -> type.containerName
    is SessionManager.SessionType.AppShell -> "Local"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TerminalSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    onShow: () -> Unit,
    onDropAccepted: () -> Unit = {},
    currentPath: String = "",
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val focusManager = LocalFocusManager.current

    val tabs = remember { mutableStateListOf<TerminalTab>() }
    var activeTabIndex by remember { mutableIntStateOf(-1) }
    var showDrawer by remember { mutableStateOf(false) }
    var sessionError by remember { mutableStateOf<String?>(null) }
    var dropHovered by remember { mutableStateOf(false) }
    var dragDropped by remember { mutableStateOf(false) }
    var dragBounds by remember { mutableStateOf(Rect.Zero) }
    var currentToast by remember { mutableStateOf<Toast?>(null) }
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
    val smallTargetPx = screenHeightPx * SmallRatio
    val largeTargetPx = screenHeightPx * LargeRatio
    val currentSmallTargetPx by rememberUpdatedState(smallTargetPx)
    val currentDropTab by rememberUpdatedState(tabs.getOrNull(activeTabIndex))
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
                dragDropped = true
                currentOnDropAccepted()
                val tab = currentDropTab?.takeIf { currentVisible && it.session.isRunning } ?: return false
                val target = SessionManager.terminalTarget(tab.sessionId)
                scope.launch {
                    try {
                        val path = withContext(Dispatchers.IO) {
                            ContainerPaths.terminalPath(context, entry.path, target, entry.isDirectory)
                        }
                        check(path.none { it.code < 32 || it.code == 127 }) { "path contains terminal control characters" }
                        check(currentDropTab?.session === tab.session && tab.session.isRunning &&
                            currentVisible && SessionManager.terminalTarget(tab.sessionId) == target) {
                            "terminal target changed during path resolution"
                        }
                        val completion = path + if (entry.isDirectory && !path.endsWith('/')) "/" else ""
                        tab.session.emulator?.paste(shellQuote(completion) + " ")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Cannot insert dropped path", e)
                        Toast.makeText(context, e.message ?: "Cannot insert path", Toast.LENGTH_LONG).show()
                    }
                }
                return true
            }
        }
    }

    fun showTabToast(index: Int) {
        val tab = tabs.getOrNull(index) ?: return
        val yOffset = with(density) { 96.dp.roundToPx() }
        currentToast?.cancel()
        currentToast = Toast.makeText(context, "[${index + 1}] ${tab.label}", Toast.LENGTH_SHORT).apply {
            setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, yOffset)
        }
        currentToast?.show()
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
            }
            sheetHeightPx.animateTo(0f, spring(dampingRatio = Spring.DampingRatioNoBouncy))
        }
    }

    // Listener for session finished events
    val sessionFinishedListener = { finishedSession: TerminalSession ->
        tabs.find { it.session === finishedSession }?.let {
            SessionManager.destroySession(it.sessionId)
        }
        Unit
    }

    val titleChangedListener = { changedSession: TerminalSession ->
        val index = tabs.indexOfFirst { it.session === changedSession }
        if (index >= 0) {
            val title = sanitizedTitle(changedSession.title)
            val tab = tabs[index]
            if (tab.title != title) tabs[index] = tab.copy(title = title)
        }
        Unit
    }

    DisposableEffect(Unit) {
        val sessionChangeListener: () -> Unit = {
            val activeId = tabs.getOrNull(activeTabIndex)?.sessionId
            val sessionIds = SessionManager.getAllSessionInfos().map { it.id }.toSet()
            val removed = tabs.removeAll { it.sessionId !in sessionIds }
            if (removed) {
                activeTabIndex = tabs.indexOfFirst { it.sessionId == activeId }
                    .takeIf { it >= 0 } ?: tabs.lastIndex
                if (tabs.isEmpty()) onDismiss() else showTabToast(activeTabIndex)
            }
        }
        SessionManager.addSessionFinishedListener(sessionFinishedListener)
        SessionManager.addSessionChangeListener(sessionChangeListener)
        SessionManager.addTitleChangedListener(titleChangedListener)
        onDispose {
            SessionManager.removeSessionFinishedListener(sessionFinishedListener)
            SessionManager.removeSessionChangeListener(sessionChangeListener)
            SessionManager.removeTitleChangedListener(titleChangedListener)
            currentToast?.cancel()
            currentToast = null
            // Sessions are owned by SessionManager and kept alive by
            // RuntimeForegroundService; they must survive the UI being torn
            // down so the user can reopen them from the notification.
            tabs.clear()
            activeTabIndex = -1
            sessionError = null
        }
    }

    fun resolveSessionType(path: String): Pair<SessionManager.SessionType, String> {
        return if (path.startsWith("container://")) {
            val resolved = ContainerUriResolver.resolveContainerUri(path).getOrThrow()
            val name = resolved.containerName
            if (name != null) {
                SessionManager.SessionType.Container(name, resolved.containerPath) to name
            } else {
                SessionManager.SessionType.AppShell(cwd = "/sdcard") to "Local"
            }
        } else if (path.startsWith("/")) {
            SessionManager.SessionType.AppShell(cwd = path) to "Local"
        } else {
            SessionManager.SessionType.AppShell(cwd = "/sdcard") to "Local"
        }
    }

    /**
     * Repopulate [tabs] from sessions that outlived the previous UI (e.g. after
     * an activity recreation) so the tabs and the notification count agree.
     */
    fun restoreTabs() {
        val infos = SessionManager.getAllSessionInfos()
        if (infos.isEmpty()) return
        infos.forEach { info ->
            if (tabs.none { it.sessionId == info.id }) {
                tabs.add(
                    TerminalTab(
                        sessionId = info.id,
                        session = info.session,
                        baseLabel = sessionLabel(info.type),
                        title = sanitizedTitle(info.session.title),
                    )
                )
            }
        }
        if (activeTabIndex !in tabs.indices) activeTabIndex = tabs.lastIndex
    }

    fun createNewTab() {
        if (tabs.isEmpty() && activeTabIndex < 0) {
            scope.launch {
                try {
                    val (type, label) = resolveSessionType(currentPath)
                    val session = SessionManager.createSession(type, context).getOrThrow()
                    val infos = SessionManager.getAllSessionInfos()
                    val info = infos.find { it.session === session }
                    val sessionId = info?.id ?: return@launch
                    tabs.add(
                        TerminalTab(
                            sessionId = sessionId,
                            session = session,
                            baseLabel = label,
                            title = sanitizedTitle(session.title),
                        )
                    )
                    activeTabIndex = tabs.lastIndex
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to create terminal", e)
                    sessionError = e.message ?: "Failed to create terminal"
                }
            }
        }
    }

    LaunchedEffect(visible) {
        if (visible) {
            DaemonManager.warmUp()
            restoreTabs()
            createNewTab()
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
                    .background(Color.Black.copy(alpha = alpha))
                    .pointerInput(Unit) { detectTapGestures { currentOnDismiss() } }
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // adjustResize keeps this height bounded by the space above the IME.
                    .height(with(density) { sheetHeightPx.value.toDp() })
                    .shadow(elevation = 16.dp, shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .background(
                        MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp),
                        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                    )
                    .then(if (dropHovered) Modifier.border(
                        2.dp, MaterialTheme.colorScheme.primary,
                        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                    ) else Modifier)
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
                                            sheetHeightPx.animateTo(target, spring(dampingRatio = Spring.DampingRatioLowBouncy))
                                        }
                                    }
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    if (!showDrawer && abs(dragAmount.x) > DRAWER_DRAG_THRESHOLD) {
                                        showDrawer = true
                                    }
                                    scope.launch {
                                        val newHeight = (sheetHeightPx.value - dragAmount.y).coerceIn(0f, largeTargetPx)
                                        sheetHeightPx.snapTo(newHeight)
                                    }
                                }
                            )
                        },
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .width(32.dp)
                                .height(4.dp)
                                .background(
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                    RoundedCornerShape(2.dp)
                                )
                        )
                    }
                }

                // Content: terminal + overlay drawer
                ContentWithDrawer(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    showDrawer = showDrawer,
                    sessionError = sessionError,
                    tabs = tabs,
                    activeTabIndex = activeTabIndex,
                    enabled = visible,
                    onSwitchTab = { idx ->
                        activeTabIndex = idx
                        showDrawer = false
                        showTabToast(idx)
                    },
                    onCloseTab = { tab ->
                        SessionManager.destroySession(tab.sessionId)
                    },
                    onDismissDrawer = { showDrawer = false },
                    onOpenDrawer = { showDrawer = true },
                    onNewTab = {
                        scope.launch {
                            try {
                                val (type, label) = resolveSessionType(currentPath)
                                val session = SessionManager.createSession(type, context).getOrThrow()
                                val infos = SessionManager.getAllSessionInfos()
                                val info = infos.find { it.session === session }
                                val sessionId = info?.id ?: return@launch
                                tabs.add(
                                    TerminalTab(
                                        sessionId = sessionId,
                                        session = session,
                                        baseLabel = label,
                                        title = sanitizedTitle(session.title),
                                    )
                                )
                                activeTabIndex = tabs.lastIndex
                                showDrawer = false
                                showTabToast(activeTabIndex)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to create tab", e)
                                sessionError = e.message ?: "Failed to create tab"
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
internal fun TerminalExtraKeys(
    inputState: TerminalInputState,
    enabled: Boolean,
    onOpenDrawer: () -> Unit,
) {
    val rows = listOf(
        listOf(
            "ESC" to KeyEvent.KEYCODE_ESCAPE, "TAB" to KeyEvent.KEYCODE_TAB,
            "PgUp" to KeyEvent.KEYCODE_PAGE_UP, "HOME" to KeyEvent.KEYCODE_MOVE_HOME,
            "\u2191" to KeyEvent.KEYCODE_DPAD_UP, "END" to KeyEvent.KEYCODE_MOVE_END,
        ),
        listOf(
            "CTRL" to KeyEvent.KEYCODE_CTRL_LEFT, "ALT" to KeyEvent.KEYCODE_ALT_LEFT,
            "PgDn" to KeyEvent.KEYCODE_PAGE_DOWN, "\u2190" to KeyEvent.KEYCODE_DPAD_LEFT,
            "\u2193" to KeyEvent.KEYCODE_DPAD_DOWN, "\u2192" to KeyEvent.KEYCODE_DPAD_RIGHT,
        ),
    )
    val imeSwipeThreshold = with(LocalDensity.current) { 24.dp.toPx() }
    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .pointerInput(enabled, imeSwipeThreshold) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(
                        requireUnconsumed = false,
                        pass = PointerEventPass.Initial,
                    )
                    val startX = down.position.x
                    val startY = down.position.y
                    while (true) {
                        val change = awaitPointerEvent(PointerEventPass.Initial)
                            .changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val dy = change.position.y - startY
                        val dx = change.position.x - startX
                        if (dy <= -imeSwipeThreshold && abs(dy) >= abs(dx)) {
                            inputState.requestIme()
                            change.consume()
                            break
                        }
                    }
                }
            }
    ) {
        rows.forEachIndexed { rowIndex, keys ->
            Row(Modifier.fillMaxWidth()) {
                keys.forEach { (label, keyCode) ->
                    val isSelected = when (keyCode) {
                        KeyEvent.KEYCODE_CTRL_LEFT -> inputState.ctrl
                        KeyEvent.KEYCODE_ALT_LEFT -> inputState.alt
                        KeyEvent.KEYCODE_SHIFT_LEFT -> inputState.shift
                        else -> false
                    }
                    val isModifier = KeyEvent.isModifierKey(keyCode)
                    val interactionSource = remember { MutableInteractionSource() }
                    val pressed by interactionSource.collectIsPressedAsState()
                    var repeated by remember { mutableStateOf(false) }

                    LaunchedEffect(pressed, enabled, isModifier) {
                        if (!pressed || !enabled || isModifier) return@LaunchedEffect
                        repeated = false
                        delay(KEY_REPEAT_DELAY_MS)
                        repeated = true
                        while (true) {
                            inputState.sendKey(keyCode)
                            delay(KEY_REPEAT_INTERVAL_MS)
                        }
                    }

                    TextButton(
                        onClick = {
                            when {
                                isModifier -> when (keyCode) {
                                    KeyEvent.KEYCODE_CTRL_LEFT -> inputState.ctrl = !inputState.ctrl
                                    KeyEvent.KEYCODE_ALT_LEFT -> inputState.alt = !inputState.alt
                                    KeyEvent.KEYCODE_SHIFT_LEFT -> inputState.shift = !inputState.shift
                                }
                                repeated -> repeated = false
                                else -> inputState.sendKey(keyCode)
                            }
                        },
                        enabled = enabled,
                        contentPadding = PaddingValues(0.dp),
                        shape = MaterialTheme.shapes.small,
                        colors = ButtonDefaults.textButtonColors(
                            containerColor = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                            contentColor = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                        ),
                        interactionSource = interactionSource,
                        modifier = Modifier.weight(1f).height(36.dp)
                            .focusProperties { canFocus = false }
                            .semantics {
                                contentDescription = label
                                if (isModifier) selected = isSelected
                            },
                    ) {
                        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    }
                }
                val enterSource = remember { MutableInteractionSource() }
                val enterPressed by enterSource.collectIsPressedAsState()
                var enterRepeated by remember { mutableStateOf(false) }

                LaunchedEffect(enterPressed, enabled, rowIndex) {
                    if (!enterPressed || !enabled || rowIndex == 0) return@LaunchedEffect
                    enterRepeated = false
                    delay(KEY_REPEAT_DELAY_MS)
                    enterRepeated = true
                    while (true) {
                        inputState.sendKey(KeyEvent.KEYCODE_ENTER)
                        delay(KEY_REPEAT_INTERVAL_MS)
                    }
                }

                TextButton(
                    onClick = {
                        when {
                            rowIndex == 0 -> onOpenDrawer()
                            enterRepeated -> enterRepeated = false
                            else -> inputState.sendKey(KeyEvent.KEYCODE_ENTER)
                        }
                    },
                    enabled = enabled,
                    contentPadding = PaddingValues(0.dp),
                    shape = MaterialTheme.shapes.small,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                    interactionSource = enterSource,
                    modifier = Modifier.weight(1f).height(36.dp)
                        .focusProperties { canFocus = false },
                ) {
                    Icon(
                        imageVector = if (rowIndex == 0) Icons.Default.Menu else Icons.AutoMirrored.Filled.KeyboardReturn,
                        modifier = Modifier.size(16.dp),
                        contentDescription = stringResource(
                            if (rowIndex == 0) R.string.terminal_drawer_title else R.string.terminal_key_enter,
                        ),
                    )
                }
            }
        }
    }
}

