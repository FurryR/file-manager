package io.furryr.file.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.furryr.file.R
import io.furryr.file.core.DirectoryStats
import io.furryr.file.model.FileTab
import io.furryr.file.model.OperationProgress
import io.furryr.file.model.PageKind
import io.furryr.file.provider.web.WebTransfers
import io.furryr.file.render.NodeAddress
import io.furryr.file.render.RendererBase
import io.furryr.file.render.RendererMenuItem
import io.furryr.file.render.RendererMenuItemRow
import io.furryr.file.ui.components.TitleBlock
import io.furryr.file.ui.util.PathSuggestion
import io.furryr.file.ui.util.PathSuggestionSource
import kotlinx.coroutines.delay

/**
 * Top bar: the one 2 dp progress strip, the path/title slot and the renderer
 * overflow menu. Tab and page state stay in the shell; this composable only
 * projects them and reports navigation back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShellTopBar(
    activeTab: FileTab,
    activeRenderer: RendererBase<*>?,
    tabRenderer: RendererBase<*>?,
    operationProgress: OperationProgress?,
    operationHidden: Boolean,
    copyMode: Boolean,
    remoteTransfers: List<WebTransfers.Progress>,
    suggestionSource: PathSuggestionSource,
    suggestionAnchor: Rect?,
    onBounds: (Rect?) -> Unit,
    onTitleBounds: (Rect?) -> Unit,
    onNavigate: (String) -> Unit,
    onSuggestion: (PathSuggestion) -> Unit,
    onEditingChange: (Boolean) -> Unit,
    onOpenDrawer: () -> Unit,
    onSettings: () -> Unit,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val webLoading = activeRenderer?.loading == true
    val webProgress = (activeRenderer?.progress ?: 0) / 100f
    // Idle handles stay registered until they close, so track the
    // one that produced the newest block instead of summing all.
    val transfer = remoteTransfers.maxByOrNull { it.updatedAt }
    val transferReading = (transfer?.reading ?: 0) > 0
    var transferLinger by remember { mutableStateOf(false) }
    // The strip disappears right after the last block lands, but a
    // short hold keeps it from flickering between 128 KiB blocks.
    LaunchedEffect(remoteTransfers) {
        if (transferReading) {
            transferLinger = true
        } else if (transferLinger) {
            delay(700)
            transferLinger = false
        }
    }
    val transferProgress = if (transfer != null && transfer.totalBytes > 0L) {
        (transfer.fetchedBytes.toFloat() / transfer.totalBytes).coerceIn(0f, 1f)
    } else {
        0f
    }
    var showMoreMenu by remember { mutableStateOf(false) }
    var menuChildren by remember { mutableStateOf<List<RendererMenuItem>?>(null) }
    Column(
        modifier = Modifier.onGloballyPositioned {
            onBounds(it.boundsInWindow())
        },
    ) {
        if (operationProgress != null && !operationHidden) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(MaterialTheme.colorScheme.primary)
            )
        } else if (copyMode) {
            // A frozen copy/move payload outlives the tab that made
            // it; the strip keeps the mode visible after switching.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(MaterialTheme.colorScheme.primary)
            )
        } else if (transfer != null && (transferReading || transferLinger)) {
            // Remote block reads share the same 2 dp strip as page
            // and copy progress: one bar, no extra layout.
            LinearProgressIndicator(
                progress = { transferProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
            )
        } else if (webLoading) {
            // Same top strip as copy/move progress, so page loading
            // never adds a second bar or re-measures the content.
            // A fresh navigation reports progress 0 until the first
            // byte arrives; a determinate bar at 0 would draw
            // nothing, so keep an indeterminate animation visible
            // for that whole wait.
            if (webProgress > 0f) {
                LinearProgressIndicator(
                    progress = { webProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                )
            }
        }
        TopAppBar(
            title = {
                val titleSlot = activeRenderer?.titleSlot()
                if (titleSlot != null) {
                    titleSlot(Modifier)
                } else {
                    val titleFolder = activeTab.folder
                    // Renderers own their path bar; the page caches
                    // the last projection so a closed/restored page
                    // still has a title and address.
                    val titlePageState = activeTab.currentPage
                    val rendererPath = titlePageState.pathInfo
                    val titleDisplay = rendererPath?.title
                        ?: titlePageState.path
                        ?: titleFolder?.path?.toString()
                        ?: activeTab.path
                    // Any location that round-trips through the
                    // opener route is editable; a view without an
                    // address stays a read-only breadcrumb.
                    val titleEditable = rendererPath?.editable ?: (titleFolder == null ||
                        runCatching {
                            NodeAddress.format(titleFolder!!.path)
                        }.getOrNull() != null)
                    TitleBlock(
                        path = activeTab.path,
                        info = if (rendererPath == null && titlePageState.kind == PageKind.LOCATION) {
                            tabRenderer?.locationInfo ?: DirectoryStats()
                        } else {
                            DirectoryStats()
                        },
                        onNavigate = onNavigate,
                        onBounds = onTitleBounds,
                        displayPath = titleDisplay,
                        editText = rendererPath?.editText ?: titleDisplay,
                        editable = titleEditable,
                        detail = rendererPath?.caption,
                        suggestionSource = suggestionSource,
                        suggestionAnchor = suggestionAnchor,
                        onSuggestion = onSuggestion,
                        onEditingChange = onEditingChange,
                    )
                }
            },
            navigationIcon = {
                IconButton(onClick = onOpenDrawer) {
                    Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.cd_menu))
                }
            },
            actions = {
                val menuItems = activeRenderer?.menuItems(context).orEmpty()
                Box {
                    IconButton(onClick = {
                        menuChildren = null
                        showMoreMenu = true
                    }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.cd_more))
                    }
                    DropdownMenu(
                        expanded = showMoreMenu,
                        onDismissRequest = {
                            showMoreMenu = false
                            menuChildren = null
                        }
                    ) {
                        val children = menuChildren
                        if (children != null) {
                            children.forEach { child ->
                                RendererMenuItemRow(
                                    item = child,
                                    onAction = { chosen ->
                                        chosen.onClick()
                                        showMoreMenu = false
                                        menuChildren = null
                                    },
                                    onSubmenu = { parent ->
                                        menuChildren = parent.children
                                    },
                                )
                            }
                        } else {
                            menuItems.forEach { item ->
                                RendererMenuItemRow(
                                    item = item,
                                    onAction = { chosen ->
                                        chosen.onClick()
                                        showMoreMenu = false
                                        menuChildren = null
                                    },
                                    onSubmenu = { parent ->
                                        menuChildren = parent.children
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.settings)) },
                                leadingIcon = { Icon(Icons.Default.Settings, null) },
                                onClick = {
                                    onSettings()
                                    showMoreMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.exit)) },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.ExitToApp, null) },
                                onClick = {
                                    onExit()
                                    showMoreMenu = false
                                }
                            )
                        }
                    }
                }
            }
        )
    }
}
