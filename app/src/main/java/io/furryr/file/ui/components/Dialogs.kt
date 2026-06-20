package io.furryr.file.ui.components
import io.furryr.file.ui.theme.*
import io.furryr.file.ui.screens.*
import io.furryr.file.ui.components.*
import io.furryr.file.ui.util.*
import io.furryr.file.daemon.*
import io.furryr.file.model.*
import io.furryr.file.ui.screens.SortMode
import io.furryr.file.R

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.furryr.file.DefaultPath
import io.furryr.file.model.DirectoryInfo
import io.furryr.file.model.FileEntry
import io.furryr.file.model.OperationProgress
import io.furryr.file.ui.util.formatSize

import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow

@Composable
fun FrontEllipsisText(
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = 1
) {
    var displayText by remember(text) { mutableStateOf(text) }
    Text(
        text = displayText,
        style = style,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
        onTextLayout = { result: TextLayoutResult ->
            val visibleEnd = result.getLineEnd(lineIndex = 0, visibleEnd = true)
            if (visibleEnd in 1 until displayText.length) {
                val candidate = "\u2026" + displayText.takeLast(visibleEnd - 1)
                if (candidate.length < displayText.length) {
                    displayText = candidate
                }
            }
        }
    )
}

@Composable
fun JumpDialog(initialPath: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember(initialPath) { mutableStateOf(initialPath) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("跳转") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text("路径") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.ifBlank { DefaultPath }) }) { Text("确定") }
        }
    )
}

@Composable
fun InfoDialog(path: String, info: DirectoryInfo, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Info") },
        text = { Text("$path\n${info.summary}") },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    )
}

@Composable
fun EntryInfoDialog(entry: FileEntry, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(entry.name) },
        text = {
            Column {
                Text("Path: ${entry.path}")
                Text("Type: ${if (entry.isDirectory) "Folder" else "File"}${if (entry.isSymlink) " (symlink)" else ""}")
                Text("Modified: ${formatModifiedTime(entry.modifiedAt)}")
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    )
}

@Composable
fun CreateDialog(
    initialPath: String,
    onDismiss: () -> Unit,
    onCreateFile: (String) -> Unit,
    onCreateFolder: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.create_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.name_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        dismissButton = {},
        confirmButton = {
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = { if (name.isNotBlank()) onCreateFile(name) }
                ) { Text(stringResource(R.string.file_button)) }
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = { if (name.isNotBlank()) onCreateFolder(name) }
                ) { Text(stringResource(R.string.folder_button)) }
            }
        }
    )
}

@Composable
fun ContextMenu(
    entry: FileEntry,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onProperties: () -> Unit,
    onShare: () -> Unit,
    canShare: Boolean = true
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    ActionButton(stringResource(R.string.copy), Icons.Default.ContentCopy, onCopy, Modifier.weight(1f))
                    ActionButton(stringResource(R.string.move_), Icons.Default.ContentCut, onMove, Modifier.weight(1f))
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    ActionButton(stringResource(R.string.rename), Icons.Default.DriveFileRenameOutline, onRename, Modifier.weight(1f))
                    ActionButton(stringResource(R.string.delete), Icons.Default.Delete, onDelete, Modifier.weight(1f))
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    ActionButton(stringResource(R.string.properties), Icons.Default.Info, onProperties, Modifier.weight(1f))
                    ActionButton(stringResource(R.string.share), Icons.Default.Share, onClick = onShare, modifier = Modifier.weight(1f), enabled = canShare)
                }
            }
        },
        confirmButton = {},
        dismissButton = {}
    )
}

@Composable
private fun ActionButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, modifier: Modifier, enabled: Boolean = true) {
    val alpha = if (enabled) 1f else 0.38f
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 5.dp)
    ) {
        Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
        Spacer(Modifier.height(1.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
    }
}

@Composable
fun OperationProgressDialog(
    progress: OperationProgress,
    sourceParent: String,
    targetPath: String,
    onHide: () -> Unit,
    onCancel: () -> Unit
) {
    val computing = progress.totalBytes == 0L
    val remaining = progress.totalBytes - progress.copiedBytes
    AlertDialog(
        onDismissRequest = {},
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (progress.isCopy) stringResource(R.string.copying_title)
                    else stringResource(R.string.moving_title),
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onHide) {
                    Text(stringResource(R.string.hide))
                }
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.cancel))
                }
            }
        },
        text = {
            Column {
                if (computing) {
                    Text(stringResource(R.string.computing_size), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    FrontEllipsisText(
                        text = "${stringResource(R.string.name_label)}: ${progress.currentName}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${stringResource(R.string.from_label)}: $sourceParent",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${stringResource(R.string.to_label)}: $targetPath",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${stringResource(R.string.remaining_label)}: ${formatSize(remaining)} / ${formatSize(progress.totalBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${stringResource(R.string.speed_label)}: ${formatSize(progress.speedBytesPerSec)}/s",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { (progress.copiedBytes.toFloat() / progress.totalBytes.toFloat()).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {}
    )
}

@Composable
fun BatchRenameDialog(
    count: Int,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    var pattern by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.batch_rename_title, count)) },
        text = {
            Column {
                OutlinedTextField(
                    value = pattern,
                    onValueChange = { pattern = it },
                    label = { Text(stringResource(R.string.regex_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = replacement,
                    onValueChange = { replacement = it },
                    label = { Text(stringResource(R.string.replacement_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
        confirmButton = {
            TextButton(onClick = { if (pattern.isNotBlank()) onConfirm(pattern, replacement) }) {
                Text(stringResource(R.string.confirm))
            }
        }
    )
}

@Composable
fun DeleteConfirmDialog(entry: FileEntry, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete)) },
        text = { Text("${stringResource(R.string.delete_confirm)}\n\n${entry.name}") },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.delete)) }
        }
    )
}

@Composable
fun RenameDialog(entry: FileEntry, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var newName by remember { mutableStateOf(entry.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_title)) },
        text = {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = { Text(stringResource(R.string.new_name_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
        confirmButton = {
            TextButton(
                onClick = { if (newName.isNotBlank() && newName != entry.name) onConfirm(newName) }
            ) { Text(stringResource(R.string.confirm)) }
        }
    )
}

@Composable
fun CopyMovePlaceDialog(
    isCopy: Boolean,
    sourceName: String,
    targetPath: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (isCopy) stringResource(R.string.copy_to_here) else stringResource(R.string.move_to_here))
        },
        text = {
            Column {
                Text(sourceName, style = MaterialTheme.typography.bodyLarge)
                Text("-> $targetPath", style = MaterialTheme.typography.bodySmall)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.confirm)) }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SortDialog(
    currentMode: SortMode,
    currentReverse: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (SortMode, Boolean) -> Unit
) {
    var selectedMode by remember { mutableStateOf(currentMode) }
    var reverse by remember { mutableStateOf(currentReverse) }
    var expanded by remember { mutableStateOf(false) }

    val modes = listOf(
        SortMode.NAME to stringResource(R.string.sort_name),
        SortMode.DATE to stringResource(R.string.sort_date),
        SortMode.SIZE to stringResource(R.string.sort_size),
        SortMode.TYPE to stringResource(R.string.sort_type)
    )
    val selectedLabel = modes.first { it.first == selectedMode }.second

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sort_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.sort_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it }
                ) {
                    OutlinedTextField(
                        value = selectedLabel,
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        modes.forEach { (mode, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    selectedMode = mode
                                    expanded = false
                                }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { reverse = !reverse }
                ) {
                    Checkbox(checked = reverse, onCheckedChange = { reverse = it })
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.reverse_order))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selectedMode, reverse) }) {
                Text(stringResource(R.string.confirm))
            }
        }
    )
}
