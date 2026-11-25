package io.furryr.file.ui.components

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import io.furryr.file.R
import io.furryr.file.core.MetadataProvider
import io.furryr.file.core.Path
import io.furryr.file.core.SystemAccount
import io.furryr.file.core.SystemAccounts
import io.furryr.file.core.VNode
import io.furryr.file.core.model.FileEntry
import io.furryr.file.provider.LocalProvider
import io.furryr.file.ui.icons.Icons
import io.furryr.file.ui.util.formatModifiedTime
import io.furryr.file.ui.util.formatPermissions
import io.furryr.file.ui.util.formatSize
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.withContext

private const val LABEL_WIDTH_DP = 84

private fun ownerText(entry: FileEntry): String =
    entry.owner.ifBlank { entry.uid.takeIf { it >= 0 }?.toString().orEmpty() }

private fun groupText(entry: FileEntry): String =
    entry.group.ifBlank { entry.gid.takeIf { it >= 0 }?.toString().orEmpty() }

private fun parentText(entry: FileEntry): String =
    runCatching { Path.parse(entry.path).parent()?.toString().orEmpty() }.getOrDefault("")

/** A local uid/gid whose application label and package icon are addressable. */
private fun appAccount(
    context: Context,
    id: Int,
    name: String,
    local: Boolean,
): SystemAccount {
    if (!local) return SystemAccount(id, name)
    val packageName = context.packageManager.getPackagesForUid(id)?.firstOrNull()
        ?: return SystemAccount(id, name)
    val label = runCatching {
        context.packageManager.getApplicationLabel(
            context.packageManager.getApplicationInfo(packageName, 0),
        ).toString()
    }.getOrDefault("")
    return SystemAccount(id, name, label, packageName)
}

/**
 * The entry properties sheet. Read-side facts come from [entry]; write-side
 * operations go through the node's provider when it implements
 * [MetadataProvider], so a backend without POSIX metadata stays readable.
 */
@Composable
fun EntryPropertiesDialog(
    entry: FileEntry,
    node: VNode?,
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val provider = node?.provider as? MetadataProvider
    val innerPath = remember(node) { node?.let { it.provider?.innerPath(it.path) } }

    var mode by remember(entry) { mutableIntStateOf(entry.mode) }
    var owner by remember(entry) { mutableStateOf(ownerText(entry)) }
    var group by remember(entry) { mutableStateOf(groupText(entry)) }
    var uid by remember(entry) { mutableIntStateOf(entry.uid) }
    var gid by remember(entry) { mutableIntStateOf(entry.gid) }
    var modifiedAt by remember(entry) { mutableLongStateOf(entry.modifiedAt) }
    var accounts by remember { mutableStateOf<SystemAccounts?>(null) }
    var showTime by remember { mutableStateOf(false) }
    var showMode by remember { mutableStateOf(false) }
    var showOwner by remember { mutableStateOf(false) }

    LaunchedEffect(provider) {
        if (provider != null) {
            accounts = withContext(Dispatchers.IO) {
                provider.accounts(innerPath ?: "/").getOrNull()
            }
        }
    }

    fun copy(value: String) {
        clipboard.setText(AnnotatedString(value))
        Toast.makeText(context, context.getString(R.string.toast_copied, value), Toast.LENGTH_SHORT)
            .show()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.properties_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PropertyRow(stringResource(R.string.name_label), entry.name) {
                    copy(entry.name)
                }
                PropertyRow(stringResource(R.string.properties_location), parentText(entry)) {
                    copy(parentText(entry))
                }
                PropertyValueRow(
                    stringResource(R.string.info_type),
                    stringResource(
                        if (entry.isDirectory) R.string.info_folder else R.string.info_file,
                    ),
                )
                PropertyRow(
                    stringResource(R.string.properties_size),
                    "${formatSize(entry.size)} (${entry.size})",
                ) {
                    copy("${formatSize(entry.size)} (${entry.size})")
                }
                PropertyRow(
                    stringResource(R.string.info_modified),
                    formatModifiedTime(modifiedAt),
                    onClick = if (provider != null) {
                        { showTime = true }
                    } else {
                        null
                    },
                )
                PropertyRow(
                    stringResource(R.string.properties_permissions),
                    "${formatPermissions(mode, entry.isDirectory)} (${
                        String.format(Locale.ROOT, "%04o", mode and 0xFFF)
                    })",
                    onClick = if (provider != null) {
                        { showMode = true }
                    } else {
                        null
                    },
                )
                PropertyRow(
                    stringResource(R.string.properties_owner),
                    owner.ifBlank { "-" },
                    onClick = if (provider != null) {
                        { showOwner = true }
                    } else {
                        null
                    },
                )
                PropertyRow(
                    stringResource(R.string.properties_group),
                    group.ifBlank { "-" },
                    onClick = if (provider != null) {
                        { showOwner = true }
                    } else {
                        null
                    },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) }
        },
    )

    if (showTime) {
        ModifiedTimeDialog(
            modifiedAt = modifiedAt,
            showApplyScope = entry.isDirectory,
            onDismiss = { showTime = false },
            onConfirm = { value, applyFiles, applyDirs ->
                showTime = false
                if (provider == null || innerPath == null) return@ModifiedTimeDialog
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        provider.setModifiedTime(innerPath, value, applyFiles, applyDirs)
                    }
                    result.onSuccess {
                        modifiedAt = value
                        onChanged()
                    }.onFailure { error ->
                        Toast.makeText(
                            context,
                            error.message ?: context.getString(R.string.toast_failed),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            },
        )
    }

    if (showMode) {
        ModeDialog(
            mode = mode,
            showApplyScope = entry.isDirectory,
            onDismiss = { showMode = false },
            onConfirm = { value, applyFiles, applyDirs ->
                showMode = false
                if (provider == null || innerPath == null) return@ModeDialog
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        provider.setMode(innerPath, value, applyFiles, applyDirs)
                    }
                    result.onSuccess {
                        mode = value
                        onChanged()
                    }.onFailure { error ->
                        Toast.makeText(
                            context,
                            error.message ?: context.getString(R.string.toast_failed),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            },
        )
    }

    if (showOwner) {
        val known = accounts ?: SystemAccounts()
        val local = node?.provider is LocalProvider
        val users = remember(known, owner, uid, local) {
            if (known.users.any { it.id == uid }) {
                known.users
            } else if (owner.isNotBlank()) {
                known.users + appAccount(context, uid, owner, local)
            } else {
                known.users
            }
        }
        val groups = remember(known, group, gid, local) {
            if (known.groups.any { it.id == gid }) {
                known.groups
            } else if (group.isNotBlank()) {
                known.groups + appAccount(context, gid, group, local)
            } else {
                known.groups
            }
        }
        OwnerDialog(
            owner = owner,
            group = group,
            uid = uid,
            gid = gid,
            users = users,
            groups = groups,
            showApplyScope = entry.isDirectory,
            onDismiss = { showOwner = false },
            onConfirm = { newUid, newGid, newOwner, newGroup, applyFiles, applyDirs ->
                showOwner = false
                if (provider == null || innerPath == null) return@OwnerDialog
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        provider.setOwner(innerPath, newUid, newGid, applyFiles, applyDirs)
                    }
                    result.onSuccess {
                        uid = newUid
                        gid = newGid
                        owner = newOwner
                        group = newGroup
                        onChanged()
                    }.onFailure { error ->
                        Toast.makeText(
                            context,
                            error.message ?: context.getString(R.string.toast_failed),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            },
        )
    }
}

@Composable
private fun PropertyValueRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(LABEL_WIDTH_DP.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun PropertyRow(
    label: String,
    value: String,
    onClick: (() -> Unit)? = null,
) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(LABEL_WIDTH_DP.dp),
        )
        Text(
            value.ifEmpty { "-" },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .weight(1f)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        )
    }
}

@Composable
private fun ApplyScopeRow(
    applyFiles: Boolean,
    applyDirs: Boolean,
    onApplyFiles: (Boolean) -> Unit,
    onApplyDirs: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = applyFiles, onCheckedChange = onApplyFiles)
        Text(
            stringResource(R.string.properties_apply_files),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = applyDirs, onCheckedChange = onApplyDirs)
        Text(
            stringResource(R.string.properties_apply_dirs),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ModifiedTimeDialog(
    modifiedAt: Long,
    showApplyScope: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Long, Boolean, Boolean) -> Unit,
) {
    val formatter = remember { DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss") }
    var text by remember(modifiedAt) {
        mutableStateOf(
            LocalDateTime.ofInstant(Instant.ofEpochMilli(modifiedAt), ZoneId.systemDefault())
                .format(formatter),
        )
    }
    var applyFiles by remember { mutableStateOf(false) }
    var applyDirs by remember { mutableStateOf(false) }

    fun parsed(): Long? = runCatching {
        LocalDateTime.parse(text.trim(), formatter)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }.recoverCatching {
        LocalDateTime.parse(text.trim(), DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }.getOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.info_modified)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (showApplyScope) {
                    ApplyScopeRow(applyFiles, applyDirs, { applyFiles = it }, { applyDirs = it })
                }
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = {
                        text = LocalDateTime.now().format(formatter)
                    },
                ) { Text(stringResource(R.string.properties_current_time)) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                TextButton(
                    onClick = { parsed()?.let { onConfirm(it, applyFiles, applyDirs) } },
                ) { Text(stringResource(R.string.confirm)) }
            }
        },
    )
}

@Composable
private fun ModeDialog(
    mode: Int,
    showApplyScope: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Int, Boolean, Boolean) -> Unit,
) {
    var bits by remember(mode) { mutableIntStateOf(mode and 0xFFF) }
    var applyFiles by remember { mutableStateOf(false) }
    var applyDirs by remember { mutableStateOf(false) }

    fun bit(mask: Int) = bits and mask != 0
    fun toggle(mask: Int) {
        bits = if (bit(mask)) bits and mask.inv() else bits or mask
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.properties_permissions)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("", modifier = Modifier.width(56.dp))
                    ModeHeader(stringResource(R.string.properties_owner))
                    ModeHeader(stringResource(R.string.properties_group))
                    ModeHeader(stringResource(R.string.properties_other))
                }
                ModeRow(
                    stringResource(R.string.properties_read),
                    intArrayOf(0x100, 0x20, 0x4),
                    ::bit,
                    ::toggle,
                )
                ModeRow(
                    stringResource(R.string.properties_write),
                    intArrayOf(0x80, 0x10, 0x2),
                    ::bit,
                    ::toggle,
                )
                ModeRow(
                    stringResource(R.string.properties_execute),
                    intArrayOf(0x40, 0x8, 0x1),
                    ::bit,
                    ::toggle,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ModeCheck(stringResource(R.string.properties_setuid), 0x800, ::bit, ::toggle)
                    ModeCheck(stringResource(R.string.properties_setgid), 0x400, ::bit, ::toggle)
                    ModeCheck(stringResource(R.string.properties_sticky), 0x200, ::bit, ::toggle)
                }
                Spacer(Modifier.height(4.dp))
                if (showApplyScope) {
                    ApplyScopeRow(applyFiles, applyDirs, { applyFiles = it }, { applyDirs = it })
                }
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                TextButton(onClick = { onConfirm(bits, applyFiles, applyDirs) }) {
                    Text(stringResource(R.string.confirm))
                }
            }
        },
    )
}

@Composable
private fun ModeHeader(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.width(64.dp),
    )
}

@Composable
private fun ModeRow(
    label: String,
    masks: IntArray,
    bit: (Int) -> Boolean,
    toggle: (Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(56.dp),
        )
        for (mask in masks) {
            Checkbox(
                checked = bit(mask),
                onCheckedChange = { toggle(mask) },
            )
        }
    }
}

@Composable
private fun ModeCheck(
    label: String,
    mask: Int,
    bit: (Int) -> Boolean,
    toggle: (Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = bit(mask), onCheckedChange = { toggle(mask) })
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun OwnerDialog(
    owner: String,
    group: String,
    uid: Int,
    gid: Int,
    users: List<SystemAccount>,
    groups: List<SystemAccount>,
    showApplyScope: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int, String, String, Boolean, Boolean) -> Unit,
) {
    var pickedOwner by remember { mutableStateOf(owner) }
    var pickedGroup by remember { mutableStateOf(group) }
    var pickedUid by remember { mutableIntStateOf(uid) }
    var pickedGid by remember { mutableIntStateOf(gid) }
    var showUsers by remember { mutableStateOf(false) }
    var showGroups by remember { mutableStateOf(false) }
    var applyFiles by remember { mutableStateOf(false) }
    var applyDirs by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.properties_owner_group)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PropertyRow(
                    stringResource(R.string.properties_owner),
                    pickedOwner.ifBlank { "-" },
                    onClick = { showUsers = true },
                )
                PropertyRow(
                    stringResource(R.string.properties_group),
                    pickedGroup.ifBlank { "-" },
                    onClick = { showGroups = true },
                )
                if (showApplyScope) {
                    Spacer(Modifier.height(4.dp))
                    ApplyScopeRow(applyFiles, applyDirs, { applyFiles = it }, { applyDirs = it })
                }
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                TextButton(
                    onClick = {
                        onConfirm(
                            pickedUid,
                            pickedGid,
                            pickedOwner,
                            pickedGroup,
                            applyFiles,
                            applyDirs,
                        )
                    },
                ) { Text(stringResource(R.string.confirm)) }
            }
        },
    )

    if (showUsers) {
        AccountPickerDialog(
            title = stringResource(R.string.properties_owner),
            accounts = users,
            selectedId = pickedUid,
            defaultIcon = Icons.Default.Person,
            onPick = {
                pickedUid = it.id
                pickedOwner = it.name
            },
            onDismiss = { showUsers = false },
        )
    }
    if (showGroups) {
        AccountPickerDialog(
            title = stringResource(R.string.properties_group),
            accounts = groups,
            selectedId = pickedGid,
            defaultIcon = Icons.Default.Group,
            onPick = {
                pickedGid = it.id
                pickedGroup = it.name
            },
            onDismiss = { showGroups = false },
        )
    }
}

@Composable
private fun AccountPickerDialog(
    title: String,
    accounts: List<SystemAccount>,
    selectedId: Int,
    defaultIcon: ImageVector,
    onPick: (SystemAccount) -> Unit,
    onDismiss: () -> Unit,
) {
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableIntStateOf(selectedId) }
    val searchFocus = remember { FocusRequester() }
    val visible = if (query.isBlank()) {
        accounts
    } else {
        accounts.filter {
            it.name.contains(query.trim(), ignoreCase = true) ||
                it.description.contains(query.trim(), ignoreCase = true) ||
                it.id.toString() == query.trim()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (searching) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.titleMedium,
                        placeholder = { Text(stringResource(R.string.properties_search)) },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(searchFocus),
                    )
                    LaunchedEffect(Unit) { searchFocus.requestFocus() }
                } else {
                    Text(title, modifier = Modifier.weight(1f))
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
                            if (searching) R.string.cancel else R.string.properties_search,
                        ),
                    )
                }
            }
        },
        text = {
            if (visible.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(72.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.no_results))
                }
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(visible) { account ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selected = account.id
                                    onPick(account)
                                }
                                .padding(vertical = 6.dp),
                        ) {
                            RadioButton(
                                selected = selected == account.id,
                                onClick = {
                                    selected = account.id
                                    onPick(account)
                                },
                            )
                            Spacer(Modifier.width(8.dp))
                            AccountIcon(account, defaultIcon)
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    account.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (account.description.isNotBlank()) {
                                    Text(
                                        account.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(
                                account.id.toString(),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) }
        },
    )
}

@Composable
private fun AccountIcon(account: SystemAccount, defaultIcon: ImageVector) {
    val context = LocalContext.current
    val bitmap = remember(account.icon) {
        if (account.icon.isBlank()) {
            null
        } else {
            runCatching {
                context.packageManager.getApplicationIcon(account.icon).toBitmap(48, 48)
            }.getOrNull()
        }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.size(32.dp),
        )
    } else {
        Icon(
            defaultIcon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(28.dp),
        )
    }
}
