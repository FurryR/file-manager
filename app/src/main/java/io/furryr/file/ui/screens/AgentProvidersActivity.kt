package io.furryr.file.ui.screens

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.furryr.file.AppLocale
import io.furryr.file.R
import io.furryr.file.agent.AGENT_PROVIDER_COMPATIBLE
import io.furryr.file.agent.AGENT_PROVIDER_RESPONSES
import io.furryr.file.agent.AgentAi
import io.furryr.file.agent.AgentModelConfig
import io.furryr.file.agent.AgentSettings
import io.furryr.file.ui.theme.FileManagerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

private data class ProviderPreset(
    val id: String,
    @StringRes val label: Int,
    @StringRes val hint: Int,
    val baseUrl: String,
    val kind: String,
    val fixedUrl: Boolean,
    val generic: Boolean = false,
    val legacyBaseUrls: List<String> = emptyList(),
)

private val PRESETS = listOf(
    ProviderPreset(
        id = "opencode_go",
        label = R.string.agent_provider_type_opencode_go,
        hint = R.string.agent_provider_hint_opencode_go,
        baseUrl = "https://opencode.ai/inference/go/openai/v1",
        kind = AGENT_PROVIDER_COMPATIBLE,
        fixedUrl = true,
    ),
    ProviderPreset(
        id = "opencode_zen",
        label = R.string.agent_provider_type_opencode_zen,
        hint = R.string.agent_provider_hint_opencode_zen,
        baseUrl = "https://opencode.ai/inference/openai/v1",
        kind = AGENT_PROVIDER_COMPATIBLE,
        fixedUrl = true,
        legacyBaseUrls = listOf("https://opencode.ai/zen/v1"),
    ),
    ProviderPreset(
        id = "new_api",
        label = R.string.agent_provider_type_new_api,
        hint = R.string.agent_provider_hint_new_api,
        baseUrl = "",
        kind = AGENT_PROVIDER_COMPATIBLE,
        fixedUrl = false,
    ),
    ProviderPreset(
        id = "openrouter",
        label = R.string.agent_provider_type_openrouter,
        hint = R.string.agent_provider_hint_openrouter,
        baseUrl = "https://openrouter.ai/api/v1",
        kind = AGENT_PROVIDER_COMPATIBLE,
        fixedUrl = true,
    ),
    ProviderPreset(
        id = "glm",
        label = R.string.agent_provider_type_glm,
        hint = R.string.agent_provider_hint_glm,
        baseUrl = "https://open.bigmodel.cn/api/paas/v4",
        kind = AGENT_PROVIDER_COMPATIBLE,
        fixedUrl = true,
    ),
    ProviderPreset(
        id = "deepseek",
        label = R.string.agent_provider_type_deepseek,
        hint = R.string.agent_provider_hint_deepseek,
        baseUrl = "https://api.deepseek.com/v1",
        kind = AGENT_PROVIDER_COMPATIBLE,
        fixedUrl = true,
    ),
    ProviderPreset(
        id = "compatible",
        label = R.string.agent_provider_type_compatible,
        hint = R.string.agent_provider_hint_compatible,
        baseUrl = "https://api.openai.com/v1",
        kind = AGENT_PROVIDER_COMPATIBLE,
        fixedUrl = false,
        generic = true,
    ),
    ProviderPreset(
        id = "responses",
        label = R.string.agent_provider_type_responses,
        hint = R.string.agent_provider_hint_responses,
        baseUrl = "https://api.openai.com/v1",
        kind = AGENT_PROVIDER_RESPONSES,
        fixedUrl = false,
        generic = true,
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProvidersPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var providers by remember { mutableStateOf(AgentSettings.providers(context)) }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<AgentSettings.Provider?>(null) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var configuring by remember { mutableStateOf<Triple<String, AgentModelConfig, Boolean>?>(null) }
    var titleModel by remember { mutableStateOf(AgentSettings.titleModel(context)) }
    var titleModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var titleInfo by remember { mutableStateOf<Map<String, JSONObject>>(emptyMap()) }
    var titleLoading by remember { mutableStateOf(false) }
    var titleError by remember { mutableStateOf<String?>(null) }
    var choosingTitleModel by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf<String?>(null) }
    var refreshError by remember { mutableStateOf<String?>(null) }

    fun reload() {
        providers = AgentSettings.providers(context)
    }

    fun refreshModels(provider: AgentSettings.Provider) {
        if (refreshing != null) return
        refreshing = provider.id
        refreshError = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val root = JSONObject(AgentAi.catalogJson())
                    val models = root.optJSONArray("models") ?: org.json.JSONArray()
                    val info = root.optJSONObject("info") ?: JSONObject()
                    val prefix = "${provider.name}/"
                    buildList {
                        for (index in 0 until models.length()) {
                            val qualified = models.optString(index)
                            if (!qualified.startsWith(prefix)) continue
                            add(qualified.removePrefix(prefix) to info.optJSONObject(qualified))
                        }
                    }
                }
            }
            result
                .onSuccess { found ->
                    val existing = AgentSettings.providers(context)
                        .firstOrNull { it.id == provider.id } ?: return@onSuccess
                    val byId = existing.models.associateBy { it.id }
                    val merged = found.map { (id, info) ->
                        val old = byId[id]
                        val config = old ?: AgentModelConfig(id = id)
                        config.copy(
                            contextWindow = config.contextWindow.takeIf { it > 0 }
                                ?: info?.optLong("context_window", 0L) ?: 0L,
                            maxOutput = config.maxOutput.takeIf { it > 0 }
                                ?: info?.optLong("max_output", 0L) ?: 0L,
                            inputPerMillion = config.inputPerMillion.takeIf { it > 0.0 }
                                ?: info?.optDouble("input_per_million", 0.0) ?: 0.0,
                            outputPerMillion = config.outputPerMillion.takeIf { it > 0.0 }
                                ?: info?.optDouble("output_per_million", 0.0) ?: 0.0,
                            cacheReadPerMillion = config.cacheReadPerMillion.takeIf { it > 0.0 }
                                ?: info?.optDouble("cache_read_per_million", 0.0) ?: 0.0,
                            cacheWritePerMillion = config.cacheWritePerMillion.takeIf { it > 0.0 }
                                ?: info?.optDouble("cache_write_per_million", 0.0) ?: 0.0,
                            thinking = info?.optBoolean("thinking", config.thinking)
                                ?: config.thinking,
                            toolCall = info?.optBoolean("tool_call", config.toolCall)
                                ?: config.toolCall,
                            vision = info?.optBoolean("vision", config.vision)
                                ?: config.vision,
                            efforts = config.efforts.ifEmpty {
                                info?.optJSONArray("efforts")?.let { values ->
                                    (0 until values.length()).mapNotNull {
                                        values.optString(it).takeIf(String::isNotBlank)
                                    }
                                }.orEmpty()
                            },
                        )
                    }
                    AgentSettings.saveProvider(context, existing.copy(models = merged))
                    reload()
                }
                .onFailure { refreshError = it.message ?: it.javaClass.simpleName }
            refreshing = null
        }
    }

    LaunchedEffect(Unit) {
        titleLoading = true
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val root = JSONObject(AgentAi.catalogJson())
                val models = root.optJSONArray("models") ?: org.json.JSONArray()
                val info = root.optJSONObject("info") ?: JSONObject()
                val list = (0 until models.length())
                    .mapNotNull { models.optString(it).takeIf(String::isNotBlank) }
                list to (0 until models.length()).mapNotNull { index ->
                    val qualified = models.optString(index)
                    val detail = info.optJSONObject(qualified) ?: return@mapNotNull null
                    qualified to detail
                }.toMap()
            }
        }
        result
            .onSuccess {
                titleModels = it.first
                titleInfo = it.second
            }
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
            SectionLabel(stringResource(R.string.agent_title_model))
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
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SectionLabel(stringResource(R.string.agent_providers))
            if (providers.isEmpty()) {
                Text(
                    stringResource(R.string.agent_provider_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            for (provider in providers) {
                val open = expanded == provider.id
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            expanded = if (open) null else provider.id
                            if (!open && provider.models.isEmpty()) refreshModels(provider)
                        }
                        .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                ) {
                    Icon(
                        if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(provider.name, style = MaterialTheme.typography.bodyLarge)
                        if (!providerUsesFixedUrl(provider)) {
                            Text(
                                provider.baseUrl,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            stringResource(presetFor(provider).hint),
                            style = MaterialTheme.typography.labelSmall,
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
                if (open) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 4.dp),
                    ) {
                        Text(
                            if (refreshing == provider.id) {
                                stringResource(R.string.agent_title_model_loading)
                            } else {
                                pluralStringResource(
                                    R.plurals.agent_provider_models_count,
                                    provider.models.size,
                                    provider.models.size,
                                )
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = {
                                configuring = Triple(provider.id, AgentModelConfig(id = ""), true)
                            },
                        ) { Text(stringResource(R.string.agent_model_add)) }
                        TextButton(
                            enabled = refreshing == null,
                            onClick = { refreshModels(provider) },
                        ) { Text(stringResource(R.string.refresh)) }
                    }
                    refreshError?.let {
                        if (refreshing == null) {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 20.dp),
                            )
                        }
                    }
                    for (model in provider.models.sortedBy { it.id }) {
                        val summary = listOfNotNull(
                            if (model.contextWindow > 0) {
                                stringResource(R.string.agent_model_summary_context, model.contextWindow)
                            } else null,
                            if (model.maxOutput > 0) {
                                stringResource(R.string.agent_model_summary_output, model.maxOutput)
                            } else null,
                            if (model.inputPerMillion > 0.0 || model.outputPerMillion > 0.0) {
                                stringResource(
                                    R.string.agent_model_summary_price,
                                    formatPrice(model.inputPerMillion),
                                    formatPrice(model.outputPerMillion),
                                )
                            } else null,
                            if (model.thinking) stringResource(R.string.agent_model_thinking) else null,
                            if (model.vision) stringResource(R.string.agent_model_vision) else null,
                        ).joinToString(" · ")
                        val notConfigured = stringResource(R.string.agent_model_not_configured)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    configuring = Triple(provider.id, model, false)
                                }
                                .padding(start = 28.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(model.id, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    summary.ifBlank { notConfigured },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(
                                onClick = { configuring = Triple(provider.id, model, false) },
                            ) { Text(stringResource(R.string.agent_provider_edit)) }
                        }
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
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
    configuring?.let { (providerId, model, isNew) ->
        val provider = providers.firstOrNull { it.id == providerId } ?: return@let
        ModelConfigDialog(
            model = model,
            isNew = isNew,
            onDismiss = { configuring = null },
            onSave = { updated ->
                val models = provider.models.toMutableList()
                val index = models.indexOfFirst { it.id == updated.id }
                if (index >= 0) models[index] = updated else models.add(updated)
                AgentSettings.saveProvider(context, provider.copy(models = models))
                configuring = null
                reload()
            },
        )
    }
    if (choosingTitleModel) {
        TitleModelDialog(
            models = titleModels,
            info = titleInfo,
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
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
    )
}

/** Compact dollars-per-million text: 0.15, 3, 1.2345. */
private fun formatPrice(value: Double): String {
    if (value <= 0.0) return "0"
    val scaled = kotlin.math.round(value * 10000.0).toLong()
    val fraction = (scaled % 10000).toString().padStart(4, '0').trimEnd('0')
    return if (fraction.isEmpty()) "${scaled / 10000}" else "${scaled / 10000}.$fraction"
}

private fun presetFor(provider: AgentSettings.Provider): ProviderPreset =
    PRESETS.firstOrNull { provider.type.isNotBlank() && it.id == provider.type }
        ?: PRESETS.firstOrNull {
            it.kind == provider.kind &&
                (it.baseUrl == provider.baseUrl || provider.baseUrl in it.legacyBaseUrls)
        }
        ?: PRESETS.firstOrNull { it.kind == provider.kind && it.generic }
        ?: PRESETS.firstOrNull { it.kind == provider.kind }
        ?: PRESETS.first()

/** Fixed-endpoint providers never expose their URL: it is not editable. */
internal fun providerUsesFixedUrl(provider: AgentSettings.Provider): Boolean =
    presetFor(provider).fixedUrl

@Composable
private fun TitleModelDialog(
    models: List<String>,
    info: Map<String, JSONObject>,
    loading: Boolean,
    error: String?,
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = models.filter {
        query.isBlank() || it.contains(query, ignoreCase = true) ||
            (info[it]?.optString("provider") ?: "").contains(query, ignoreCase = true)
    }
    val grouped = filtered.groupBy { info[it]?.optString("provider").orEmpty() }
        .toSortedMap()
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
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.agent_title_model_search)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect("") }
                        .padding(top = 4.dp),
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
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    filtered.isEmpty() -> Text(
                        if (query.isBlank()) stringResource(R.string.agent_title_model_empty)
                        else stringResource(R.string.agent_title_model_no_match),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    else -> LazyColumn(
                        modifier = Modifier.heightIn(max = 320.dp).padding(top = 4.dp),
                    ) {
                        for ((provider, entries) in grouped) {
                            if (provider.isNotBlank()) {
                                item { Text(
                                    provider,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                                ) }
                            }
                            items(entries) { model ->
                                val label = provider.takeIf { it.isNotBlank() }
                                    ?.let { model.removePrefix("$it/") } ?: model
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onSelect(model) }
                                        .padding(vertical = 2.dp),
                                ) {
                                    RadioButton(
                                        selected = selected == model,
                                        onClick = { onSelect(model) },
                                    )
                                    Text(label, modifier = Modifier.padding(start = 4.dp))
                                }
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
private fun ModelConfigDialog(
    model: AgentModelConfig,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (AgentModelConfig) -> Unit,
) {
    var id by remember { mutableStateOf(model.id) }
    var context by remember { mutableStateOf(model.contextWindow.takeIf { it > 0 }?.toString().orEmpty()) }
    var output by remember { mutableStateOf(model.maxOutput.takeIf { it > 0 }?.toString().orEmpty()) }
    var input by remember { mutableStateOf(model.inputPerMillion.takeIf { it > 0.0 }?.toString().orEmpty()) }
    var outputPrice by remember { mutableStateOf(model.outputPerMillion.takeIf { it > 0.0 }?.toString().orEmpty()) }
    var cacheRead by remember { mutableStateOf(model.cacheReadPerMillion.takeIf { it > 0.0 }?.toString().orEmpty()) }
    var cacheWrite by remember { mutableStateOf(model.cacheWritePerMillion.takeIf { it > 0.0 }?.toString().orEmpty()) }
    var thinking by remember { mutableStateOf(model.thinking) }
    var vision by remember { mutableStateOf(model.vision) }
    var toolCall by remember { mutableStateOf(model.toolCall) }
    var efforts by remember { mutableStateOf(model.efforts.joinToString(", ")) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (isNew) stringResource(R.string.agent_model_add)
                else model.id,
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.agent_model_config_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (isNew) {
                    OutlinedTextField(
                        value = id,
                        onValueChange = { id = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.agent_model_id)) },
                        placeholder = { Text(stringResource(R.string.agent_model_id_hint)) },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
                NumberField(
                    context,
                    { context = it },
                    stringResource(R.string.agent_model_context_window),
                    Modifier.fillMaxWidth(),
                )
                NumberField(
                    output,
                    { output = it },
                    stringResource(R.string.agent_model_max_output),
                    Modifier.fillMaxWidth(),
                )
                NumberField(
                    input,
                    { input = it },
                    stringResource(R.string.agent_model_input_price),
                    Modifier.fillMaxWidth(),
                )
                NumberField(
                    outputPrice,
                    { outputPrice = it },
                    stringResource(R.string.agent_model_output_price),
                    Modifier.fillMaxWidth(),
                )
                NumberField(
                    cacheRead,
                    { cacheRead = it },
                    stringResource(R.string.agent_model_cache_read_price),
                    Modifier.fillMaxWidth(),
                )
                NumberField(
                    cacheWrite,
                    { cacheWrite = it },
                    stringResource(R.string.agent_model_cache_write_price),
                    Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = efforts,
                    onValueChange = { efforts = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.agent_model_efforts)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                CapabilityRow(
                    stringResource(R.string.agent_model_thinking),
                    thinking,
                ) { thinking = it }
                CapabilityRow(
                    stringResource(R.string.agent_model_tool_call),
                    toolCall,
                ) { toolCall = it }
                CapabilityRow(
                    stringResource(R.string.agent_model_vision),
                    vision,
                ) { vision = it }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
        confirmButton = {
            TextButton(
                enabled = !isNew || id.isNotBlank(),
                onClick = {
                    onSave(
                        model.copy(
                            id = if (isNew) id.trim() else model.id,
                            contextWindow = context.toLongOrNull()?.coerceAtLeast(0) ?: 0,
                            maxOutput = output.toLongOrNull()?.coerceAtLeast(0) ?: 0,
                            inputPerMillion = input.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0,
                            outputPerMillion = outputPrice.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0,
                            cacheReadPerMillion = cacheRead.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0,
                            cacheWritePerMillion = cacheWrite.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0,
                            thinking = thinking,
                            toolCall = toolCall,
                            vision = vision,
                            efforts = efforts.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                        ),
                    )
                },
            ) { Text(stringResource(R.string.confirm)) }
        },
    )
}

@Composable
private fun NumberField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { text -> onChange(text.filter { it.isDigit() || it == '.' }) },
        singleLine = true,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
private fun CapabilityRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderDialog(
    initial: AgentSettings.Provider?,
    onDismiss: () -> Unit,
    onSave: (AgentSettings.Provider) -> Unit,
) {
    val context = LocalContext.current
    val initialPreset = remember(initial) { initial?.let { presetFor(it) } }
    var preset by remember { mutableStateOf(initialPreset) }
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var baseUrl by remember {
        mutableStateOf(
            initialPreset?.takeIf { it.fixedUrl }?.baseUrl
                ?: initial?.baseUrl.orEmpty(),
        )
    }
    var apiKey by remember { mutableStateOf(initial?.apiKey.orEmpty()) }
    var typeExpanded by remember { mutableStateOf(false) }
    val valid = preset != null && name.isNotBlank() &&
        (preset?.fixedUrl == true || AgentSettings.validEndpoint(baseUrl))
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
                ExposedDropdownMenuBox(
                    expanded = typeExpanded,
                    onExpandedChange = { typeExpanded = it },
                ) {
                    OutlinedTextField(
                        value = preset?.let { context.getString(it.label) }.orEmpty(),
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        label = { Text(stringResource(R.string.agent_provider_type)) },
                        placeholder = { Text(stringResource(R.string.agent_provider_type_select)) },
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
                        for (item in PRESETS) {
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(
                                            stringResource(item.label),
                                            style = MaterialTheme.typography.bodyLarge,
                                        )
                                        Text(
                                            stringResource(item.hint),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                onClick = {
                                    if (preset != item) {
                                        preset = item
                                        name = context.getString(item.label)
                                        baseUrl = item.baseUrl
                                    }
                                    typeExpanded = false
                                },
                            )
                        }
                    }
                }
                val selected = preset
                if (selected == null) {
                    Text(
                        stringResource(R.string.agent_provider_type_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                } else {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.agent_provider_name)) },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    if (!selected.fixedUrl) {
                        OutlinedTextField(
                            value = baseUrl,
                            onValueChange = { baseUrl = it },
                            singleLine = true,
                            label = { Text(stringResource(R.string.agent_base_url)) },
                            placeholder = { Text(stringResource(R.string.agent_base_url_hint)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                    }
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.agent_api_key)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
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
                            baseUrl = (preset?.takeIf { it.fixedUrl }?.baseUrl ?: baseUrl).trim(),
                            apiKey = apiKey.trim(),
                            kind = preset?.kind ?: initial?.kind ?: AGENT_PROVIDER_COMPATIBLE,
                            models = initial?.models.orEmpty(),
                            type = preset?.id.orEmpty(),
                        ),
                    )
                },
            ) { Text(stringResource(R.string.confirm)) }
        },
    )
}
