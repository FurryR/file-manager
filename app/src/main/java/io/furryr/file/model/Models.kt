package io.furryr.file.model
import io.furryr.file.ui.theme.*
import io.furryr.file.ui.screens.*
import io.furryr.file.ui.components.*
import io.furryr.file.ui.util.*
import io.furryr.file.daemon.*
import io.furryr.file.model.*
import io.furryr.file.ui.util.formatSize
import io.furryr.file.util.Path

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
            if (p.host != null) return p.host
            return if (p.isRoot) "/" else path
        }
}

data class FileEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val modifiedAt: Long,
    val isSymlink: Boolean = false,
    val realPath: String = "",
    val mode: Int = 0,
    val size: Long = 0L
)

data class DirectoryInfo(
    val folderCount: Int = 0,
    val fileCount: Int = 0,
    val usableBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val refreshToken: Int = 0
) {
    val summary: String
        get() = "文件夹 $folderCount | 文件 $fileCount | 可用 ${formatSize(usableBytes)}/${formatSize(totalBytes)}"
}

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
