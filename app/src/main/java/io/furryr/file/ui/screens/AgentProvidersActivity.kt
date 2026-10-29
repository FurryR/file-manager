package io.furryr.file.ui.screens

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.furryr.file.AppLocale
import io.furryr.file.R
import io.furryr.file.agent.AgentSettings
import io.furryr.file.agent.AgentAi
import io.furryr.file.ui.theme.FileManagerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class AgentProvidersActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        setContent {
            FileManagerTheme(darkTheme = prefs.getBoolean("dark_theme", true)) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ProvidersPage(onBack = { finish() })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProvidersPage(onBack: () -> Unit) {
    val context = LocalContext.current
    var providers by remember { mutableStateOf(AgentSettings.providers(context)) }
    var active by remember { mutableStateOf(AgentSettings.activeProvider(context)?.id) }
    var editing by remember { mutableStateOf<AgentSettings.Provider?>(null) }
    var adding by remember { mutableStateOf(false) }
    var titleModel by remember { mutableStateOf(AgentSettings.titleModel(context)) }
    var titleModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var titleLoading by remember { mutableStateOf(false) }
    var titleError by remember { mutableStateOf<String?>(null) }
    var choosingTitleModel by remember { mutableStateOf(false) }

    fun reload() {
        providers = AgentSettings.providers(context)
        active = AgentSettings.activeProvider(context)?.id
    }

    LaunchedEffect(active, providers) {
        if (active == null) {
            titleModels = emptyList()
            titleError = null
            titleLoading = false
            return@LaunchedEffect
        }
        titleLoading = true
        titleError = null
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val array = JSONObject(AgentAi.catalogJson()).optJSONArray("models") ?: JSONArray()
                (0 until array.length())
                    .mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
            }
        }
        result
            .onSuccess { titleModels = it }
            .onFailure { titleError = it.message ?: it.javaClass.simpleName }
        titleLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.agent_providers)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = { adding = true }) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.agent_provider_add))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            if (providers.isEmpty()) {
                Text(
                    stringResource(R.string.agent_provider_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            for (provider in providers) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            AgentSettings.setActiveProvider(context, provider.id)
                            reload()
                        }
                        .padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                ) {
                    RadioButton(
                        selected = provider.id == active,
                        onClick = {
                            AgentSettings.setActiveProvider(context, provider.id)
                            reload()
                        },
                    )
                    Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                        Text(provider.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            provider.baseUrl,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { editing = provider }) {
                        Text(stringResource(R.string.agent_provider_edit))
                    }
                    TextButton(
                        onClick = {
                            AgentSettings.deleteProvider(context, provider.id)
                            reload()
                        },
                    ) {
                        Text(stringResource(R.string.agent_provider_delete))
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { choosingTitleModel = true }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.agent_title_model),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        if (titleModel.isBlank()) {
                            stringResource(R.string.agent_title_model_follow)
                        } else {
                            titleModel
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    val initial = if (adding) null else editing
    if (adding || editing != null) {
        ProviderDialog(
            initial = initial,
            onDismiss = {
                adding = false
                editing = null
            },
            onSave = { provider ->
                AgentSettings.saveProvider(context, provider)
                adding = false
                editing = null
                reload()
            },
        )
    }
    if (choosingTitleModel) {
        TitleModelDialog(
            models = titleModels,
            loading = titleLoading,
            error = titleError,
            selected = titleModel,
            onSelect = { model ->
                AgentSettings.setTitleModel(context, model)
                titleModel = model
                choosingTitleModel = false
            },
            onDismiss = { choosingTitleModel = false },
        )
    }
}

@Composable
private fun TitleModelDialog(
    models: List<String>,
    loading: Boolean,
    error: String?,
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.agent_title_model)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.agent_title_model_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect("") }
                        .padding(top = 8.dp),
                ) {
                    RadioButton(selected = selected.isBlank(), onClick = { onSelect("") })
                    Text(
                        stringResource(R.string.agent_title_model_follow),
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                when {
                    loading -> Text(
                        stringResource(R.string.agent_title_model_loading),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    error != null -> Text(
                        stringResource(R.string.agent_title_model_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    models.isEmpty() -> Text(
                        stringResource(R.string.agent_title_model_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    else -> LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                        items(models) { model ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(model) },
                            ) {
                                RadioButton(
                                    selected = selected == model,
                                    onClick = { onSelect(model) },
                                )
                                Text(
                                    model,
                                    modifier = Modifier.padding(start = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun ProviderDialog(
    initial: AgentSettings.Provider?,
    onDismiss: () -> Unit,
    onSave: (AgentSettings.Provider) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var baseUrl by remember { mutableStateOf(initial?.baseUrl.orEmpty()) }
    var apiKey by remember { mutableStateOf(initial?.apiKey.orEmpty()) }
    val valid = name.isNotBlank() && AgentSettings.validEndpoint(baseUrl)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (initial == null) R.string.agent_provider_add else R.string.agent_provider_edit,
                ),
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.agent_endpoint_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.agent_provider_name)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.agent_base_url)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.agent_api_key)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onSave(
                        AgentSettings.Provider(
                            id = initial?.id.orEmpty(),
                            name = name.trim(),
                            baseUrl = baseUrl.trim(),
                            apiKey = apiKey.trim(),
                        ),
                    )
                },
            ) { Text(stringResource(R.string.confirm)) }
        },
    )
}
