package io.furryr.file.model
import android.content.Context
import io.furryr.file.R
import io.furryr.file.ui.util.formatSize
import io.furryr.file.core.DirectoryStats
import io.furryr.file.core.Path

data class FileTab(
    val path: String,
    val backStack: List<String> = emptyList(),
    val forwardStack: List<String> = emptyList(),
    val selectedPaths: Set<String> = emptySet(),
    val lastSelectedIndex: Int = -1
) {
    val title: String
        get() {
            val p = Path.parse(path)
            p.name?.let { return it }
            p.host?.let { return it }
            return if (p.isRoot) "/" else path
        }
}

fun DirectoryStats.summary(context: Context): String = context.getString(
    R.string.directory_summary,
    folderCount,
    fileCount,
    formatSize(usableBytes),
    formatSize(totalBytes),
)

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
