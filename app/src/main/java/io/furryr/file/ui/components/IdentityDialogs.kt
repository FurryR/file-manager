package io.furryr.file.ui.components

import android.content.Context
import android.content.Intent
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.furryr.file.R
import io.furryr.file.picker.FilePickerActivity
import io.furryr.file.store.IdentityRepository
import io.furryr.file.store.LocationStores
import io.furryr.file.store.RemoteLocationStore
import io.furryr.file.ui.icons.Icons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The one identity picker for every remote connection.
 *
 * FTP/SFTP/AList only accept password identities; SSH additionally lists
 * private-key identities. The picker can create a new identity inline, so a
 * connection form never needs an inline credential field.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun IdentitySelector(
    selectedId: String,
    allowKey: Boolean,
    modifier: Modifier = Modifier,
    onSelected: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var identities by remember { mutableStateOf(emptyList<RemoteLocationStore.Identity>()) }
    var expanded by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<RemoteLocationStore.Identity?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun load(): List<RemoteLocationStore.Identity> = withContext(Dispatchers.IO) {
        LocationStores.init(context)
        IdentityRepository.identities().filter { allowKey || !it.usesPrivateKey }
    }

    LaunchedEffect(allowKey) {
        try {
            identities = load()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = context.getString(R.string.identity_store_failed)
        }
    }

    fun save(identity: RemoteLocationStore.Identity) {
        editing = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    LocationStores.init(context)
                    IdentityRepository.save(identity)
                }
                identities = load()
                onSelected(identity.id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = context.getString(
                    if (failure is IllegalArgumentException) R.string.identity_save_failed
                    else R.string.identity_store_failed,
                )
            }
        }
    }

    Column(modifier) {
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = identities.firstOrNull { it.id == selectedId }?.name
                    ?: stringResource(
                        if (selectedId.isBlank()) R.string.ssh_key_none
                        else R.string.ssh_key_missing,
                    ),
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                label = { Text(stringResource(R.string.remote_identity)) },
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.ssh_key_none)) },
                    leadingIcon = if (selectedId.isBlank()) {
                        { Icon(Icons.Default.Check, contentDescription = null) }
                    } else {
                        null
                    },
                    onClick = {
                        onSelected("")
                        expanded = false
                    },
                )
                identities.forEach { identity ->
                    DropdownMenuItem(
                        text = { Text(identity.name) },
                        leadingIcon = if (identity.id == selectedId) {
                            { Icon(Icons.Default.Check, contentDescription = null) }
                        } else {
                            null
                        },
                        onClick = {
                            onSelected(identity.id)
                            expanded = false
                        },
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.identity_add)) },
                    leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                    onClick = {
                        expanded = false
                        editing = RemoteLocationStore.Identity(name = "", password = "")
                    },
                )
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }

    editing?.let { initial ->
        IdentityEditorDialog(
            initial = initial,
            allowKey = allowKey,
            onDismiss = { editing = null },
            onSave = ::save,
        )
    }
}

/**
 * Add/edit one identity. [allowKey] is false where the protocol only supports
 * password authentication; an existing key identity is then still editable as
 * a key but cannot be selected by that connection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun IdentityEditorDialog(
    initial: RemoteLocationStore.Identity,
    allowKey: Boolean = true,
    onDismiss: () -> Unit,
    onSave: (RemoteLocationStore.Identity) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember(initial.id) { mutableStateOf(initial.name) }
    var useKey by remember(initial.id) {
        mutableStateOf(allowKey && initial.usesPrivateKey)
    }
    var password by remember(initial.id) { mutableStateOf(initial.password) }
    var body by remember(initial.id) { mutableStateOf(initial.privateKey) }
    var passphrase by remember(initial.id) { mutableStateOf(initial.passphrase) }
    var error by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data?.takeIf { result.resultCode == android.app.Activity.RESULT_OK }
            ?: return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) { readKey(context, uri) }
                if (name.isBlank()) name = loaded.first.orEmpty()
                body = loaded.second
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = if (failure is IllegalArgumentException) {
                    failure.message ?: context.getString(R.string.identity_key_unreadable)
                } else {
                    context.getString(R.string.identity_key_unreadable)
                }
            } finally {
                importing = false
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.remote_identity)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    name,
                    { name = it },
                    label = { Text(stringResource(R.string.identity_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (allowKey) {
                    var typeExpanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = typeExpanded,
                        onExpandedChange = { typeExpanded = it },
                    ) {
                        OutlinedTextField(
                            value = stringResource(
                                if (useKey) R.string.identity_private_key
                                else R.string.identity_password,
                            ),
                            onValueChange = {},
                            readOnly = true,
                            singleLine = true,
                            label = { Text(stringResource(R.string.identity_type)) },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeExpanded)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                        )
                        ExposedDropdownMenu(
                            expanded = typeExpanded,
                            onDismissRequest = { typeExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.identity_password)) },
                                leadingIcon = if (!useKey) {
                                    { Icon(Icons.Default.Check, contentDescription = null) }
                                } else {
                                    null
                                },
                                onClick = {
                                    useKey = false
                                    typeExpanded = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.identity_private_key)) },
                                leadingIcon = if (useKey) {
                                    { Icon(Icons.Default.Check, contentDescription = null) }
                                } else {
                                    null
                                },
                                onClick = {
                                    useKey = true
                                    typeExpanded = false
                                },
                            )
                        }
                    }
                }
                if (useKey) {
                    OutlinedTextField(
                        body,
                        { body = it },
                        label = { Text(stringResource(R.string.identity_private_key)) },
                        minLines = 3,
                        maxLines = 6,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(enabled = !importing, onClick = {
                        picker.launch(Intent(context, FilePickerActivity::class.java).apply {
                            action = Intent.ACTION_OPEN_DOCUMENT
                            type = "*/*"
                            addCategory(Intent.CATEGORY_OPENABLE)
                        })
                    }) { Text(stringResource(R.string.remote_import_key)) }
                    OutlinedTextField(
                        passphrase,
                        { passphrase = it },
                        label = { Text(stringResource(R.string.remote_passphrase)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    OutlinedTextField(
                        password,
                        { password = it },
                        label = { Text(stringResource(R.string.remote_password)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() &&
                    if (useKey) body.isNotBlank() else password.isNotBlank(),
                onClick = {
                    val candidate = initial.copy(
                        name = name.trim(),
                        password = if (useKey) "" else password,
                        privateKey = if (useKey) body else "",
                        passphrase = if (useKey) passphrase else "",
                    )
                    runCatching { RemoteLocationStore.verifyIdentity(candidate) }.onFailure {
                        error = context.getString(R.string.identity_save_failed)
                    }.onSuccess {
                        error = null
                        onSave(candidate)
                    }
                },
            ) { Text(stringResource(R.string.remote_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

private fun readKey(context: Context, uri: android.net.Uri): Pair<String?, String> {
    val displayName = context.contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { if (it.moveToFirst()) it.getString(0) else null }
    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= 512 * 1024) {
                context.getString(R.string.identity_key_too_large)
            }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    } ?: error(context.getString(R.string.identity_key_unreadable))
    require(bytes.size <= 512 * 1024) { context.getString(R.string.identity_key_too_large) }
    return displayName to bytes.toString(Charsets.UTF_8)
}
