package io.furryr.file.ui.util
import io.furryr.file.ui.theme.*
import io.furryr.file.ui.screens.*
import io.furryr.file.ui.components.*
import io.furryr.file.ui.util.*
import io.furryr.file.daemon.*
import io.furryr.file.model.*

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.material3.Icon
import androidx.compose.ui.Modifier

fun iconForFile(name: String, isDirectory: Boolean): ImageVector {
    if (isDirectory) return Icons.Default.Folder

    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        // Images
        "jpg", "jpeg", "png", "gif", "bmp", "webp", "svg", "ico",
        "heic", "heif", "tiff", "tif" -> Icons.Default.Image
        // Videos
        "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "3gp",
        "m4v", "mpg", "mpeg" -> Icons.Default.VideoFile
        // Audio
        "mp3", "wav", "flac", "aac", "ogg", "wma", "m4a", "opus" -> Icons.Default.AudioFile
        // Documents
        "pdf" -> Icons.Default.PictureAsPdf
        // Archives
        "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "apk", "jar" -> Icons.Default.Archive
        // Code
        "kt", "java", "py", "js", "ts", "html", "css", "xml",
        "json", "yaml", "yml", "toml", "rs", "go", "c", "cpp",
        "h", "hpp", "sh", "bash", "zsh", "sql", "swift" -> Icons.Default.Code
        // Text
        "txt", "md", "log", "csv", "ini", "cfg", "conf" -> Icons.AutoMirrored.Filled.TextSnippet
        else -> Icons.Default.Description
    }
}
