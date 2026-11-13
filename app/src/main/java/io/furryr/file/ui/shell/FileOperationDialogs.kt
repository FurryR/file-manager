package io.furryr.file.ui.shell

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import io.furryr.file.R
import io.furryr.file.core.VFolder
import io.furryr.file.core.VNode
import io.furryr.file.core.model.FileEntry
import io.furryr.file.model.FileTab
import io.furryr.file.ui.components.BatchRenameDialog
import io.furryr.file.ui.components.DeleteConfirmDialog
import io.furryr.file.ui.components.RenameDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Delete dialog plus the shell-owned operation it starts. */
@Composable
internal fun DeleteEntriesDialog(
    entries: List<FileEntry>?,
    context: Context,
    scope: CoroutineScope,
    currentTab: () -> FileTab,
    folderOf: suspend (FileTab) -> VFolder?,
    nodeOf: suspend (VFolder?, String) -> VNode?,
    onDismiss: () -> Unit,
    onSelectionClear: () -> Unit,
    onRefresh: () -> Unit,
) {
    if (entries == null) return
    DeleteConfirmDialog(
        entries = entries,
        onDismiss = onDismiss,
        onConfirm = {
            onDismiss()
            val targets = entries
            val tab = currentTab()
            scope.launch {
                var failure: Throwable? = null
                withContext(Dispatchers.IO) {
                    val folder = folderOf(tab)
                    for (entry in targets) {
                        val node = nodeOf(folder, entry.name)
                        val result = runCatching {
                            (node ?: error("missing ${entry.name}")).delete().getOrThrow()
                        }
                        if (result.isFailure) {
                            failure = result.exceptionOrNull()
                            break
                        }
                    }
                }
                val error = failure
                val message = when {
                    error != null -> error.message ?: context.getString(R.string.toast_failed)
                    targets.size == 1 -> context.getString(R.string.toast_deleted, targets.first().name)
                    else -> context.getString(R.string.toast_deleted_count, targets.size)
                }
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                onSelectionClear()
                onRefresh()
            }
        },
    )
}

/** Single-entry rename dialog plus the rename operation. */
@Composable
internal fun RenameEntryDialog(
    entry: FileEntry?,
    context: Context,
    scope: CoroutineScope,
    currentTab: () -> FileTab,
    folderOf: suspend (FileTab) -> VFolder?,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
) {
    if (entry == null) return
    RenameDialog(
        entry = entry,
        onDismiss = onDismiss,
        onConfirm = { newName ->
            val tab = currentTab()
            val oldName = entry.name
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val folder = folderOf(tab) ?: error("cannot rename here")
                        folder.child(oldName).getOrThrow().rename(newName).getOrThrow()
                    }
                }
                Toast.makeText(
                    context,
                    if (result.isSuccess) context.getString(R.string.toast_renamed, newName)
                    else result.exceptionOrNull()?.message ?: context.getString(R.string.toast_failed),
                    Toast.LENGTH_SHORT
                ).show()
                onDismiss()
                onRefresh()
            }
        },
    )
}

/** Batch rename dialog plus the regex rename operation. */
@Composable
internal fun BatchRenameEntriesDialog(
    visible: Boolean,
    entries: List<FileEntry>,
    context: Context,
    scope: CoroutineScope,
    currentTab: () -> FileTab,
    folderOf: suspend (FileTab) -> VFolder?,
    onDismiss: () -> Unit,
    onSelectionClear: () -> Unit,
    onRefresh: () -> Unit,
) {
    if (!visible) return
    BatchRenameDialog(
        count = entries.size,
        onDismiss = onDismiss,
        onConfirm = { pattern, replacement ->
            val regex = runCatching { Regex(pattern) }.getOrNull()
            val targets = entries
            val tab = currentTab()
            scope.launch {
                val failures = if (regex == null) {
                    listOf(IllegalArgumentException(context.getString(R.string.toast_failed)))
                } else {
                    withContext(Dispatchers.IO) {
                        val folder = folderOf(tab)
                        targets.mapNotNull { entry ->
                            val newName = regex.replace(entry.name, replacement)
                            if (newName.isBlank() || newName == entry.name) {
                                null
                            } else {
                                runCatching {
                                    folder?.child(entry.name)?.getOrThrow()
                                        ?.rename(newName)?.getOrThrow()
                                }.exceptionOrNull()
                            }
                        }
                    }
                }
                Toast.makeText(
                    context,
                    if (failures.isEmpty()) context.getString(R.string.toast_renamed, targets.size.toString())
                    else failures.first().message ?: context.getString(R.string.toast_failed),
                    Toast.LENGTH_SHORT
                ).show()
                onDismiss()
                if (failures.isEmpty()) onSelectionClear()
                onRefresh()
            }
        },
    )
}
