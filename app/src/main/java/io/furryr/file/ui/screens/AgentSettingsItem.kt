package io.furryr.file.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.furryr.file.R
import io.furryr.file.agent.AgentSettings

@Composable
internal fun AgentSettingsItem() {
    val context = LocalContext.current
    var showDialog by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth().clickable { showDialog = true }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(stringResource(R.string.agent_settings), style = MaterialTheme.typography.bodyLarge)
        Text(
            stringResource(R.string.agent_settings_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (showDialog) {
        val loaded = remember { runCatching { AgentSettings.load(context) } }
        var baseUrl by remember { mutableStateOf(loaded.getOrNull()?.baseUrl.orEmpty()) }
        var model by remember { mutableStateOf(loaded.getOrNull()?.model.orEmpty()) }
        var apiKey by remember { mutableStateOf(loaded.getOrNull()?.apiKey.orEmpty()) }
        var error by remember { mutableStateOf(if (loaded.isFailure) R.string.agent_config_read_error else null) }
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(stringResource(R.string.agent_settings)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.agent_endpoint_hint), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = baseUrl, onValueChange = { baseUrl = it }, singleLine = true,
                        label = { Text(stringResource(R.string.agent_base_url)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = model, onValueChange = { model = it }, singleLine = true,
                        label = { Text(stringResource(R.string.agent_model)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = apiKey, onValueChange = { apiKey = it }, singleLine = true,
                        label = { Text(stringResource(R.string.agent_api_key)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text(stringResource(R.string.cancel)) }
            },
            confirmButton = {
                TextButton(
                    enabled = AgentSettings.validEndpoint(baseUrl) && model.isNotBlank(),
                    onClick = {
                        runCatching { AgentSettings.save(context, baseUrl, apiKey, model) }
                            .onSuccess { showDialog = false }
                            .onFailure { error = R.string.agent_config_save_error }
                    },
                ) { Text(stringResource(R.string.confirm)) }
            },
        )
    }
}
