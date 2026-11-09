package io.furryr.file.render

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Refresh
import io.furryr.file.R
import io.furryr.file.core.VFolder
import io.furryr.file.core.VNode

/**
 * Minimal directory controller shared by provider folders and nested container
 * folders. The Compose layer can render [entries] with the existing list UI;
 * it must not call Vfs or parse paths.
 */
class DirectorySession(
    val folder: VFolder,
) : RendererSession {
    var entries: List<VNode> = emptyList()
        private set

    override val capabilities: RenderCapabilities
        get() = RenderCapabilities(
            canGoParent = folder.parent != null,
            canRefresh = true,
        )

    suspend fun load(): Result<List<VNode>> = folder.listNodes().onSuccess { entries = it }

    override fun close() {
        // Provider folders hold no independent resources. Container sources are
        // owned by Archives and released when the tab/session is gone.
    }
}

/** Menu shared by both the provider-backed list and the legacy list screen. */
object DirectoryRenderer {
    fun menuItems(
        context: Context,
        host: RenderHost,
        canRefresh: Boolean,
    ): List<RendererMenuItem> = buildList {
        if (canRefresh) {
            add(
                RendererMenuItem(
                    title = context.getString(R.string.refresh),
                    icon = Icons.Default.Refresh,
                ) { host.refresh() },
            )
        }
        add(
            RendererMenuItem(
                title = context.getString(R.string.filter_title),
                icon = Icons.Default.FilterAlt,
            ) { host.toggleFilter() },
        )
        add(
            RendererMenuItem(
                title = context.getString(R.string.sort_title),
                icon = Icons.AutoMirrored.Filled.Sort,
            ) { host.openSort() },
        )
    }
}

class DirectoryRendererFactory : RendererFactory {
    override val id: String = "directory"

    override fun supports(target: RenderTarget): Boolean =
        target is RenderTarget.Node && target.node is VFolder

    override suspend fun create(
        target: RenderTarget,
        host: RenderHost,
    ): Result<RendererSession> = runCatching {
        val folder = (target as? RenderTarget.Node)?.node as? VFolder
            ?: error("directory renderer requires a folder")
        DirectorySession(folder).also { it.load().getOrThrow() }
    }
}
