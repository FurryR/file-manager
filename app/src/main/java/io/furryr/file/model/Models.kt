package io.furryr.file.model
import android.content.Context
import io.furryr.file.R
import io.furryr.file.isHomePath
import io.furryr.file.ui.util.formatSize
import io.furryr.file.core.DirectoryStats
import io.furryr.file.core.Path
import io.furryr.file.core.VFolder

data class FileTab(
    /**
     * Stable identity. Tab order changes when a tab is closed, so renderer
     * sessions and history are keyed by this value instead of an index.
     */
    val id: String = java.util.UUID.randomUUID().toString(),
    val path: String,
    /** Non-null while this tab is inside a container (zip, ...). */
    val folder: VFolder? = null,
    val backStack: List<String> = emptyList(),
    /** Parallel to [backStack]: the node to restore for each history entry. */
    val folderBackStack: List<VFolder?> = emptyList(),
    val forwardStack: List<String> = emptyList(),
    val folderForwardStack: List<VFolder?> = emptyList(),
    val selectedPaths: Set<String> = emptySet(),
    val lastSelectedIndex: Int = -1,
    /** Web address shown by this tab's renderer, or null for a directory. */
    val webUrl: String? = null,
    /** Page title reported by the WebView, preferred over the path label. */
    val webTitle: String? = null,
) {
    val title: String
        get() {
            webTitle?.takeIf { it.isNotBlank() }?.let { return it }
            webUrl?.let { url ->
                runCatching { java.net.URI(url).host }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { return it }
            }
            // Home is the fallback location behind a page, so it must not
            // override the page's own title.
            if (isHomePath(path)) return "Home"
            val p = Path.parse(path)
            p.name?.let { return it }
            p.host?.let { return it }
            return if (p.isRoot) "/" else path
        }
}

// Only the volume usage is a useful directory summary; folder/file counts are
// already visible in the listing. Matches the drawer: used = total - usable.
fun DirectoryStats.summary(context: Context): String =
    if (totalBytes > 0) context.getString(
        R.string.directory_summary,
        formatSize(totalBytes - usableBytes),
        formatSize(totalBytes),
    ) else ""

data class CopyMoveSource(
    val path: String,
    val name: String
)

data class CopyMoveState(
    val sources: List<CopyMoveSource>,
    val isCopy: Boolean,
    val sourceParent: String = sources.firstOrNull()?.let {
        val parentFile = java.io.File(it.path).parentFile
        parentFile?.name ?: ""
    } ?: "",
    val targetPath: String = ""
) {
    val sourceName: String
        get() = if (sources.size == 1) sources.first().name else "${sources.size} items"
}

data class OperationProgress(
    val totalBytes: Long,
    val copiedBytes: Long,
    val currentName: String,
    val isCopy: Boolean,
    val speedBytesPerSec: Long = 0L
)
