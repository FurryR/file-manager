package io.furryr.file.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.furryr.file.R
import io.furryr.file.model.FileTab
import io.furryr.file.model.PageKind
import io.furryr.file.render.RendererBase
import io.furryr.file.render.RendererContent
import io.furryr.file.shell.ShellRendererContext

/**
 * The one tab content slot: the pending renderer loads behind the committed
 * page, the live renderer draws its own document, and the 300 ms cover blocks
 * input until the pending renderer commits.
 */
@Composable
internal fun ShellContentHost(
    tab: FileTab,
    activeRenderer: RendererBase<*>?,
    pageContents: Map<String, RendererContent>,
    pending: FileTab?,
    pendingSpinner: Boolean,
    darkTheme: Boolean,
    contextFor: (String, String?) -> ShellRendererContext,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    Box(Modifier.fillMaxSize()) {
        // Pending renderer: composed behind the committed page at
        // zero alpha so it can load its own resources while the
        // old view stays visible and interactive.
        val pendingRenderer = pending?.currentPage?.renderer
        if (pending != null && pendingRenderer != null) {
            val pendingHost = contextFor(tab.id, pending.currentPage.id)
            pendingHost.bindPage(pending.currentPage.id)
            LaunchedEffect(pending.currentPage.id) {
                pendingRenderer.enter(pendingHost)
            }
        }
        when {
            // The live renderer owns the tab view. It decides how its
            // document/page is drawn; the shell only supplies the slot.
            activeRenderer != null -> {
                val pageId = tab.currentPage.id
                val renderHost = contextFor(tab.id, null)
                renderHost.bindPage(pageId)
                pageContents[pageId]?.let { content ->
                    content(renderHost, darkTheme, Modifier.fillMaxSize())
                    activeRenderer.pathBar(context)?.let { bar ->
                        SideEffect { renderHost.submitPath(bar) }
                    }
                    SideEffect { renderHost.submitTitle(activeRenderer.title) }
                }
                LaunchedEffect(pageId, activeRenderer) {
                    activeRenderer.enter(renderHost)
                }
            }
            // A page owns the location even before its renderer is
            // rebuilt; draw nothing rather than falling back to Home.
            tab.currentPage.kind == PageKind.PAGE && tab.currentPage.renderer == null -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
            )
            // Every directory tab has a FolderRendererSession. This
            // branch only covers the frame between a location being
            // superseded and its next renderer being built.
            else -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
            )
        }
        if (pendingSpinner && pendingRenderer != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent().changes.forEach { it.consume() }
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    TextButton(onClick = onCancel) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            }
        }
    }
}
