package io.furryr.file.ui.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.furryr.file.R
import io.furryr.file.core.OpenIcon
import io.furryr.file.core.OpenOption

/** Every opener describes its own icon and localized label; the shell maps icons. */
private fun iconOf(icon: OpenIcon): ImageVector = when (icon) {
    OpenIcon.ARCHIVE -> Icons.Default.Archive
    OpenIcon.PAGE -> Icons.Default.Language
    OpenIcon.EDITOR -> Icons.Default.Edit
    OpenIcon.SYSTEM -> Icons.AutoMirrored.Filled.OpenInNew
    OpenIcon.GENERIC -> Icons.AutoMirrored.Filled.InsertDriveFile
}

/**
 * Generic loader: a fixed three-column grid of embedded open-with entries,
 * icon above the localized name, one tap to open with that entry. The title
 * row owns a search toggle that filters the grid. The system hand-off sits at
 * the bottom left, Cancel at the bottom right.
 */
@Composable
fun GenericLoaderDialog(
    title: String,
    options: List<OpenOption>,
    onPick: (OpenOption) -> Unit,
    onSystem: () -> Unit,
    onDismiss: () -> Unit,
) {
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val searchFocus = remember { FocusRequester() }
    val visibleOptions = if (query.isBlank()) {
        options
    } else {
        options.filter { it.label.contains(query.trim(), ignoreCase = true) }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.widthIn(max = 400.dp).fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                tonalElevation = 6.dp,
            ) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (searching) {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.titleMedium,
                                placeholder = { Text(stringResource(R.string.open_with_search)) },
                                modifier = Modifier
                                    .weight(1f)
                                    .focusRequester(searchFocus),
                            )
                            LaunchedEffect(Unit) { searchFocus.requestFocus() }
                        } else {
                            Text(
                                title,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        IconButton(
                            onClick = {
                                searching = !searching
                                if (!searching) query = ""
                            },
                        ) {
                            Icon(
                                if (searching) Icons.Default.Close else Icons.Default.Search,
                                contentDescription = stringResource(
                                    if (searching) R.string.cd_close_search
                                    else R.string.open_with_search,
                                ),
                            )
                        }
                    }
                    if (visibleOptions.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(96.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                stringResource(R.string.no_results),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    } else {
                        Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            visibleOptions.chunked(3).forEach { rowOptions ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    rowOptions.forEach { option ->
                                        OpenOptionCell(
                                            option = option,
                                            modifier = Modifier.weight(1f),
                                            onClick = { onPick(option) },
                                        )
                                    }
                                    repeat(3 - rowOptions.size) {
                                        Spacer(Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.padding(top = 12.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = onSystem) {
                            Text(stringResource(R.string.open_with_system))
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OpenOptionCell(
    option: OpenOption,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
    ) {
        Icon(
            iconOf(option.icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(32.dp),
        )
        Text(
            option.label,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}
