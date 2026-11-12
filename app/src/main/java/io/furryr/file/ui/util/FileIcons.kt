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
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import io.furryr.file.core.ContentKind
import io.furryr.file.core.ContentTypes

/**
 * The MIME table decides what a file is; this file only chooses a glyph for
 * the resulting [ContentKind]. A plugin that registers an extension gets an
 * icon without touching the UI.
 */
private fun kindOfFile(name: String, isDirectory: Boolean): ContentKind? {
    if (isDirectory) return null
    return ContentTypes.kindOf(ContentTypes.fromName(name), name)
}

fun iconForFile(name: String, isDirectory: Boolean): ImageVector = when (kindOfFile(name, isDirectory)) {
    null -> Icons.Default.Folder
    ContentKind.IMAGE -> Icons.Default.Image
    ContentKind.VIDEO -> Icons.Default.VideoFile
    ContentKind.AUDIO -> Icons.Default.AudioFile
    ContentKind.PDF -> Icons.Default.PictureAsPdf
    ContentKind.ARCHIVE -> Icons.Default.Archive
    ContentKind.CODE -> Icons.Default.Code
    ContentKind.TEXT -> Icons.AutoMirrored.Filled.TextSnippet
    ContentKind.BINARY -> Icons.Default.Description
}

// Icons carry the file kind through hue, not just shape: folders use the
// primary accent, media the warm tertiary accent and code/archives the
// secondary accent, so a directory listing is scannable at a glance while
// every tint still comes from a Material 3 role.
@Composable
fun fileIconTint(name: String, isDirectory: Boolean): Color {
    val scheme = MaterialTheme.colorScheme
    return when (kindOfFile(name, isDirectory)) {
        null -> scheme.primary
        ContentKind.IMAGE, ContentKind.VIDEO, ContentKind.AUDIO -> scheme.tertiary
        ContentKind.ARCHIVE, ContentKind.CODE -> scheme.secondary
        ContentKind.PDF, ContentKind.TEXT, ContentKind.BINARY -> scheme.onSurfaceVariant
    }
}
