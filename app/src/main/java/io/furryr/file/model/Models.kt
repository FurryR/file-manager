package io.furryr.file.model
import android.content.Context
import io.furryr.file.R
import io.furryr.file.isHomePath
import io.furryr.file.ui.util.BrowseAddress
import io.furryr.file.ui.util.formatSize
import io.furryr.file.core.DirectoryStats
import io.furryr.file.core.Path
import io.furryr.file.core.VFolder

/** What a history stop points at. */
enum class StopKind {
    /** A directory or Home: the tab's own location. */
    LOCATION,

    /** A document drawn over its folder (the editor). */
    FILE,

    /** A page drawn over its folder (a live URL or a stored HTML file). */
    PAGE,
}

/**
 * One stop of a tab's history.
 *
 * [id] is the session key: a stop keeps its renderer state while the tab moves
 * away, and Back returns to the same instance.
 */
data class HistoryStop(
    val id: String = java.util.UUID.randomUUID().toString(),
    /** Vfs address or page URL this stop shows. */
    val path: String,
    /** The view behind a folder stop, when it is not a plain provider path. */
    val folder: VFolder? = null,
    val kind: StopKind = StopKind.LOCATION,
    /** Last title a renderer reported; kept after the session is gone. */
    val title: String? = null,
)

/** Newest stops a tab keeps; the current location always stays in the list. */
private const val MAX_HISTORY_STOPS = 64

data class FileTab(
    /**
     * Stable identity. Tab order changes when a tab is closed, so renderer
     * sessions and history are keyed by this value instead of an index.
     */
    val id: String = java.util.UUID.randomUUID().toString(),
    val path: String,
    /** Non-null while this tab is inside a container (zip, ...). */
    val folder: VFolder? = null,
    /**
     * Every stop of this tab, oldest first. The current one is at
     * [historyIndex]; Back and Forward only move that index.
     */
    val history: List<HistoryStop> = listOf(HistoryStop(path = path)),
    val historyIndex: Int = 0,
    val selectedPaths: Set<String> = emptySet(),
    val lastSelectedIndex: Int = -1,
    /**
     * The page this tab is showing, or null when it shows a directory/home.
     *
     * History is the tab's, so this is a history stop like a folder path: the
     * live page's `Path` (or its URL) as the address bar shows it.
     */
    val webUrl: String? = null,
    /** Page title reported by the WebView, preferred over the path label. */
    val webTitle: String? = null,
) {
    val title: String
        get() {
            webTitle?.takeIf { it.isNotBlank() }?.let { return it }
            // Before the page reports a title (and for a text file, which never
            // gets one), the address still names the document: its last
            // segment, not the host it happens to live on.
            webUrl?.let { url -> BrowseAddress.displayName(url)?.let { return it } }
            // Home is the fallback location behind a page, so it must not
            // override the page's own title.
            if (isHomePath(path)) return "Home"
            val p = Path.parse(path)
            p.name?.let { return it }
            p.host?.let { return it }
            return if (p.isRoot) "/" else path
        }

    /** The stop this tab is showing. History is never empty in practice. */
    val currentStop: HistoryStop
        get() = history.getOrNull(historyIndex)
            ?: HistoryStop(id = "tab-$id", path = path)

    val canGoBack: Boolean get() = historyIndex > 0
    val canGoForward: Boolean get() = historyIndex < history.lastIndex

    /** Truncate the forward stops and make [stop] the current one. */
    fun appended(stop: HistoryStop): FileTab {
        val at = if (history.isEmpty()) -1 else historyIndex.coerceIn(0, history.lastIndex)
        val kept = if (at >= 0) history.take(at + 1) else emptyList()
        var next = kept + stop
        var index = next.lastIndex
        if (next.size > MAX_HISTORY_STOPS) {
            val drop = next.size - MAX_HISTORY_STOPS
            next = next.drop(drop)
            index -= drop
        }
        return copy(
            history = next,
            historyIndex = index,
            // A location is the tab's own address; an overlay keeps the
            // folder it was opened from underneath.
            path = if (stop.kind == StopKind.LOCATION) stop.path else path,
            folder = if (stop.kind == StopKind.LOCATION) stop.folder else folder,
            webUrl = if (stop.kind == StopKind.PAGE) stop.path else null,
            webTitle = stop.title,
            selectedPaths = emptySet(),
            lastSelectedIndex = -1,
        )
    }

    /** Update the current stop in place (title, kind, redirect target). */
    fun replacedCurrent(stop: HistoryStop): FileTab {
        if (history.isEmpty()) return appended(stop)
        val index = historyIndex.coerceIn(0, history.lastIndex)
        val stops = history.toMutableList()
        stops[index] = stop
        return copy(
            history = stops,
            path = if (stop.kind == StopKind.LOCATION) stop.path else path,
            folder = if (stop.kind == StopKind.LOCATION) stop.folder else folder,
            webUrl = if (stop.kind == StopKind.PAGE) stop.path else null,
            webTitle = stop.title,
        )
    }

    /** Make history stop [index] current, deriving the tab's location fields. */
    fun movedTo(index: Int): FileTab {
        if (history.isEmpty()) return this
        val targetIndex = index.coerceIn(0, history.lastIndex)
        val target = history[targetIndex]
        val base = if (target.kind == StopKind.LOCATION) target
        else history.subList(0, targetIndex).lastOrNull { it.kind == StopKind.LOCATION }
        return copy(
            historyIndex = targetIndex,
            path = base?.path ?: path,
            folder = when {
                target.kind == StopKind.LOCATION -> target.folder
                base != null -> base.folder
                else -> folder
            },
            webUrl = if (target.kind == StopKind.PAGE) target.path else null,
            webTitle = target.title,
            selectedPaths = emptySet(),
            lastSelectedIndex = -1,
        )
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

/**
 * One pending transfer is now a clipboard payload (`:app` clipboard package);
 * the source is always a [VNode] and the destination is another node
 * (`VFolder.destination`) handed to `NodeOps.copyTo`.
 */
data class OperationProgress(
    val totalBytes: Long,
    val copiedBytes: Long,
    val currentName: String,
    val isCopy: Boolean,
    val speedBytesPerSec: Long = 0L
)
