package io.furryr.file

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.termux.terminal.TerminalSession
import io.furryr.file.agent.SessionManager
import io.furryr.file.agent.TerminalViewComposable
import io.furryr.file.daemon.DaemonLauncher
import android.util.Log
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val SmallRatio = 0.50f
private const val LargeRatio = 0.90f
private val DrawerWidth = 180.dp
private const val TAG = "AgentSheet"
private const val DRAWER_DRAG_THRESHOLD = 50f

private data class TerminalTab(
    val sessionId: String,
    val session: TerminalSession,
    val label: String,
)

@Composable
fun AgentSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    currentPath: String = "",
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    val tabs = remember { mutableStateListOf<TerminalTab>() }
    var activeTabIndex by remember { mutableIntStateOf(-1) }
    var showDrawer by remember { mutableStateOf(false) }
    var sessionError by remember { mutableStateOf<String?>(null) }

    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
    val smallTargetPx = screenHeightPx * SmallRatio
    val largeTargetPx = screenHeightPx * LargeRatio
    val sheetHeightPx = remember { Animatable(0f) }

    BackHandler(enabled = visible) { onDismiss() }

    LaunchedEffect(visible) {
        if (visible) {
            if (sheetHeightPx.value == 0f) {
                sheetHeightPx.animateTo(smallTargetPx, spring(dampingRatio = Spring.DampingRatioLowBouncy))
            }
        } else {
            sheetHeightPx.animateTo(0f, spring(dampingRatio = Spring.DampingRatioNoBouncy))
        }
    }

    // Listener for session finished events
    val sessionFinishedListener = { finishedSession: TerminalSession ->
        val idx = tabs.indexOfFirst { it.session === finishedSession }
        if (idx >= 0) {
            val tab = tabs[idx]
            scope.launch {
                SessionManager.destroySession(tab.sessionId)
            }
            tabs.removeAt(idx)
            if (tabs.isEmpty()) {
                activeTabIndex = -1
                onDismiss()
            } else {
                activeTabIndex = idx.coerceAtMost(tabs.lastIndex)
            }
        }
    }

    DisposableEffect(Unit) {
        SessionManager.addSessionFinishedListener(sessionFinishedListener)
        onDispose {
            SessionManager.removeSessionFinishedListener(sessionFinishedListener)
            scope.launch {
                for (tab in tabs) {
                    SessionManager.destroySession(tab.sessionId)
                }
            }
            tabs.clear()
            activeTabIndex = -1
            sessionError = null
        }
    }

    fun resolveSessionType(path: String): Pair<SessionManager.SessionType, String> {
        return if (path.startsWith("container://")) {
            val name = path.removePrefix("container://").substringBefore('/')
            if (name.isNotBlank()) {
                SessionManager.SessionType.Container(name) to name
            } else {
                SessionManager.SessionType.AppShell(cwd = "/sdcard") to "Local"
            }
        } else if (path.startsWith("/")) {
            SessionManager.SessionType.AppShell(cwd = path) to "Local"
        } else {
            SessionManager.SessionType.AppShell(cwd = "/sdcard") to "Local"
        }
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
                    tabs.add(TerminalTab(sessionId = sessionId, session = session, label = label))
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
            DaemonLauncher.connect()
            createNewTab()
        }
    }

    if (sheetHeightPx.value == 0f && !visible) return



    Box(modifier = Modifier.fillMaxSize()) {
        if (sheetHeightPx.value > 0f) {
            val alpha = ((sheetHeightPx.value / smallTargetPx).coerceIn(0f, 1f) * 0.4f)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = alpha))
                    .pointerInput(Unit) { detectTapGestures { onDismiss() } }
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
                    .fillMaxHeight(sheetHeightPx.value / screenHeightPx)
                    .shadow(elevation = 16.dp, shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .background(
                        MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp),
                        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                    )
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
                                    scope.launch {
                                        if (target == 0f) {
                                            sheetHeightPx.animateTo(0f, spring(dampingRatio = Spring.DampingRatioNoBouncy))
                                            onDismiss()
                                        } else {
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
                    onSwitchTab = { idx -> activeTabIndex = idx; showDrawer = false },
                    onCloseTab = { tab ->
                        val i = tabs.indexOf(tab)
                        if (i >= 0) {
                            scope.launch { SessionManager.destroySession(tab.sessionId) }
                            tabs.removeAt(i)
                            if (tabs.isEmpty()) { activeTabIndex = -1; onDismiss() }
                            else { activeTabIndex = i.coerceAtMost(tabs.lastIndex) }
                        }
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
                                tabs.add(TerminalTab(sessionId = sessionId, session = session, label = label))
                                activeTabIndex = tabs.lastIndex
                                showDrawer = false
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
private fun ContentWithDrawer(
    modifier: Modifier = Modifier,
    showDrawer: Boolean,
    sessionError: String?,
    tabs: List<TerminalTab>,
    activeTabIndex: Int,
    onSwitchTab: (Int) -> Unit,
    onCloseTab: (TerminalTab) -> Unit,
    onDismissDrawer: () -> Unit,
    onOpenDrawer: () -> Unit,
    onNewTab: () -> Unit,
) {
    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 4.dp)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = { },
                        onHorizontalDrag = { change, dragAmount ->
                            if (!showDrawer && dragAmount > DRAWER_DRAG_THRESHOLD) {
                                change.consume()
                                onOpenDrawer()
                            }
                        }
                    )
                }
        ) {
            when {
                sessionError != null -> {
                    Text(
                        sessionError,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.Center).padding(16.dp),
                    )
                }
                tabs.getOrNull(activeTabIndex) != null -> {
                    val currentTab = tabs[activeTabIndex]
                    TerminalViewComposable(
                        session = currentTab.session,
                        modifier = Modifier.fillMaxSize(),
                        enabled = true,
                    )
                }
            }
        }

        // Overlay to dismiss drawer when tapping right half
        if (showDrawer) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures { onDismissDrawer() } }
                    .background(Color.Transparent)
            )
        }

        AnimatedVisibility(
            visible = showDrawer,
            enter = slideInHorizontally { -it },
            exit = slideOutHorizontally { -it }
        ) {
            Box(
                modifier = Modifier
                    .width(DrawerWidth)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp))
            ) {
                DrawerContent(
                    tabs = tabs,
                    activeTabIndex = activeTabIndex,
                    onSwitchTab = onSwitchTab,
                    onCloseTab = onCloseTab,
                    onNewTab = onNewTab,
                )
            }
        }
    }
}

@Composable
private fun DrawerContent(
    tabs: List<TerminalTab>,
    activeTabIndex: Int,
    onSwitchTab: (Int) -> Unit,
    onCloseTab: (TerminalTab) -> Unit,
    onNewTab: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            stringResource(R.string.terminal_drawer_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            itemsIndexed(tabs, key = { i, t -> "${i}_${t.sessionId}" }) { idx, tab ->
                val isActive = idx == activeTabIndex
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSwitchTab(idx) }
                        .background(
                            if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                            else Color.Transparent
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        "[${idx + 1}] ${tab.label}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                        color = if (isActive) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (tabs.size > 1) {
                        Text(
                            "×",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 16.sp,
                            modifier = Modifier.clickable { onCloseTab(tab) }
                        )
                    }
                }
            }
        }
        Button(
            onClick = onNewTab,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
            Text(stringResource(R.string.drawer_new_terminal), maxLines = 1)
        }
    }
}
