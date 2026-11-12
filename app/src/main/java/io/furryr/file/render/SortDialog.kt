package io.furryr.file.render

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.furryr.file.R

/**
 * The directory renderer's own sort picker. Sort is session state, so this
 * dialog never travels through the shell.
 */
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
