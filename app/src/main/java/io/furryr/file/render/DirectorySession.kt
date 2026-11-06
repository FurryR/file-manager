package io.furryr.file.render

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
