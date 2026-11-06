package io.furryr.file.ui.util
import io.furryr.file.ui.theme.*
import io.furryr.file.ui.screens.*
import io.furryr.file.ui.components.*
import io.furryr.file.ui.util.*
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

private enum class FileKind {
    Folder, Image, Video, Audio, Pdf, Archive, Code, Text, Other
}

private fun kindOfFile(name: String, isDirectory: Boolean): FileKind {
    if (isDirectory) return FileKind.Folder

    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        // Images
        "jpg", "jpeg", "png", "gif", "bmp", "webp", "svg", "ico",
        "heic", "heif", "tiff", "tif" -> FileKind.Image
        // Videos
        "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "3gp",
        "m4v", "mpg", "mpeg" -> FileKind.Video
        // Audio
        "mp3", "wav", "flac", "aac", "ogg", "wma", "m4a", "opus" -> FileKind.Audio
        // Documents
        "pdf" -> FileKind.Pdf
        // Archives (an APK is a package, not a browsable container)
        "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "jar" -> FileKind.Archive
        // Code
        "kt", "java", "py", "js", "ts", "html", "css", "xml",
        "json", "yaml", "yml", "toml", "rs", "go", "c", "cpp",
        "h", "hpp", "sh", "bash", "zsh", "sql", "swift" -> FileKind.Code
        // Text
        "txt", "md", "log", "csv", "ini", "cfg", "conf" -> FileKind.Text
        else -> FileKind.Other
    }
}

fun iconForFile(name: String, isDirectory: Boolean): ImageVector = when (kindOfFile(name, isDirectory)) {
    FileKind.Folder -> Icons.Default.Folder
    FileKind.Image -> Icons.Default.Image
    FileKind.Video -> Icons.Default.VideoFile
    FileKind.Audio -> Icons.Default.AudioFile
    FileKind.Pdf -> Icons.Default.PictureAsPdf
    FileKind.Archive -> Icons.Default.Archive
    FileKind.Code -> Icons.Default.Code
    FileKind.Text -> Icons.AutoMirrored.Filled.TextSnippet
    FileKind.Other -> Icons.Default.Description
}

// Icons carry the file kind through hue, not just shape: folders use the
// primary accent, media the warm tertiary accent and code/archives the
// secondary accent, so a directory listing is scannable at a glance while
// every tint still comes from a Material 3 role.
@Composable
fun fileIconTint(name: String, isDirectory: Boolean): Color {
    val scheme = MaterialTheme.colorScheme
    return when (kindOfFile(name, isDirectory)) {
        FileKind.Folder -> scheme.primary
        FileKind.Image, FileKind.Video, FileKind.Audio -> scheme.tertiary
        FileKind.Archive, FileKind.Code -> scheme.secondary
        FileKind.Pdf, FileKind.Text, FileKind.Other -> scheme.onSurfaceVariant
    }
}
