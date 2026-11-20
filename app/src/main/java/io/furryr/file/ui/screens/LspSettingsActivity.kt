package io.furryr.file.ui.screens

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.furryr.file.AppLocale
import io.furryr.file.R
import io.furryr.file.lsp.LspConfigStore
import io.furryr.file.lsp.LspLanguageRule
import io.furryr.file.lsp.LspOverrideRule
import io.furryr.file.lsp.LspRuntimeFacade
import io.furryr.file.ui.theme.FileManagerTheme
import kotlinx.coroutines.launch
import org.json.JSONObject

class LspSettingsActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LspConfigStore.migrateLegacy(applicationContext)
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        setContent {
            FileManagerTheme(darkTheme = prefs.getBoolean("dark_theme", true)) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    LspConfigPage(onBack = { finish() })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LspConfigPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf(LspConfigStore.load(context)) }
    var languageOpen by remember { mutableStateOf(false) }
    var languageEditing by remember { mutableStateOf<LspLanguageRule?>(null) }
    var overrideOpen by remember { mutableStateOf(false) }
    var overrideEditing by remember { mutableStateOf<LspOverrideRule?>(null) }

    fun store(next: io.furryr.file.lsp.LspUserConfig) {
        config = next
        LspConfigStore.save(context, next)
        scope.launch { LspRuntimeFacade.refreshAll() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.editor_lsp_settings)) },
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = onBack) {
                        androidx.compose.material3.Icon(
                            io.furryr.file.ui.icons.Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                        )
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
            SectionHeader(
                title = stringResource(R.string.lsp_languages),
                add = stringResource(R.string.lsp_add_language),
                onAdd = {
                    languageEditing = null
                    languageOpen = true
                },
            )
            if (config.languages.isEmpty()) {
                Text(
                    stringResource(R.string.lsp_empty_languages),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            for (language in config.languages) {
                RuleRow(
                    title = language.label.ifBlank { language.id },
                    subtitle = listOfNotNull(
                        language.id.takeIf { language.label.isNotBlank() },
                        language.command.joinToString(" ").takeIf { it.isNotBlank() },
                        language.markers.joinToString(", ").takeIf { it.isNotBlank() },
                    ).joinToString(" · "),
                    onEdit = {
                        languageEditing = language
                        languageOpen = true
                    },
                    onDelete = {
                        store(config.copy(languages = config.languages.filterNot { it.id == language.id }))
                    },
                )
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SectionHeader(
                title = stringResource(R.string.lsp_overrides),
                add = stringResource(R.string.lsp_add_override),
                onAdd = {
                    overrideEditing = null
                    overrideOpen = true
                },
            )
            if (config.overrides.isEmpty()) {
                Text(
                    stringResource(R.string.lsp_empty_overrides),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            for (override in config.overrides) {
                RuleRow(
                    title = override.prefix,
                    subtitle = listOfNotNull(
                        override.language.takeIf { it.isNotBlank() },
                        override.command.joinToString(" ").takeIf { it.isNotBlank() },
                    ).joinToString(" · "),
                    onEdit = {
                        overrideEditing = override
                        overrideOpen = true
                    },
                    onDelete = {
                        store(config.copy(overrides = config.overrides.filterNot { it.prefix == override.prefix }))
                    },
                )
            }
        }
    }

    if (languageOpen) {
        LanguageDialog(
            initial = languageEditing,
            onDismiss = { languageOpen = false },
            onSave = { rule ->
                val oldId = languageEditing?.id
                val next = config.languages
                    .filterNot { it.id == oldId || it.id == rule.id } + rule
                store(config.copy(languages = next))
                languageOpen = false
            },
        )
    }
    if (overrideOpen) {
        OverrideDialog(
            initial = overrideEditing,
            onDismiss = { overrideOpen = false },
            onSave = { rule ->
                val oldPrefix = overrideEditing?.prefix
                val next = config.overrides
                    .filterNot { it.prefix == oldPrefix || it.prefix == rule.prefix } + rule
                store(config.copy(overrides = next))
                overrideOpen = false
            },
        )
    }
}

@Composable
private fun SectionHeader(title: String, add: String, onAdd: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        TextButton(onClick = onAdd) { Text(add) }
    }
}

@Composable
private fun RuleRow(
    title: String,
    subtitle: String,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        TextButton(onClick = onEdit) { Text(stringResource(R.string.agent_provider_edit)) }
        TextButton(onClick = onDelete) { Text(stringResource(R.string.delete)) }
    }
}

@Composable
private fun LanguageDialog(
    initial: LspLanguageRule?,
    onDismiss: () -> Unit,
    onSave: (LspLanguageRule) -> Unit,
) {
    var id by remember { mutableStateOf(initial?.id.orEmpty()) }
    var label by remember { mutableStateOf(initial?.label.orEmpty()) }
    var extensions by remember { mutableStateOf(initial?.extensions.orEmpty().joinToString(", ")) }
    var exactNames by remember { mutableStateOf(initial?.exactNames.orEmpty().joinToString(", ")) }
    var markers by remember { mutableStateOf(initial?.markers.orEmpty().joinToString(", ")) }
    var command by remember { mutableStateOf(initial?.command.orEmpty().joinToString(" ")) }
    var args by remember { mutableStateOf(initial?.args.orEmpty().joinToString("\n")) }
    var languageIds by remember {
        mutableStateOf(initial?.lspLanguageIds.orEmpty().entries.joinToString("\n") { "${it.key}=${it.value}" })
    }
    var env by remember {
        mutableStateOf(initial?.env.orEmpty().entries.joinToString("\n") { "${it.key}=${it.value}" })
    }
    var initializationOptions by remember { mutableStateOf(initial?.initializationOptions?.toString(2).orEmpty()) }
    var settings by remember { mutableStateOf(initial?.settings?.toString(2).orEmpty()) }
    var workspace by remember { mutableStateOf(initial?.workspace ?: "exec") }
    var rootMode by remember { mutableStateOf(initial?.rootMode ?: "nearest") }
    var enabled by remember { mutableStateOf(initial?.enabled ?: true) }
    var error by remember { mutableStateOf<String?>(null) }
    val invalidJson = stringResource(R.string.lsp_invalid_json)
    val valid = id.isNotBlank() && command.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (initial == null) R.string.lsp_add_language else R.string.agent_provider_edit))
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Field(stringResource(R.string.lsp_language_id), id, { id = it })
                Field(stringResource(R.string.lsp_label), label, { label = it })
                Field(stringResource(R.string.lsp_extensions), extensions, { extensions = it })
                Field(stringResource(R.string.lsp_exact_names), exactNames, { exactNames = it })
                Field(stringResource(R.string.lsp_markers), markers, { markers = it })
                Field(stringResource(R.string.lsp_command), command, { command = it })
                Field(stringResource(R.string.lsp_args), args, { args = it }, singleLine = false)
                Field(stringResource(R.string.lsp_language_ids), languageIds, { languageIds = it }, singleLine = false)
                Field(stringResource(R.string.lsp_env), env, { env = it }, singleLine = false)
                Field(
                    stringResource(R.string.lsp_initialization_options),
                    initializationOptions,
                    { initializationOptions = it },
                    singleLine = false,
                )
                Field(stringResource(R.string.lsp_settings), settings, { settings = it }, singleLine = false)
                Selector(
                    label = stringResource(R.string.lsp_workspace),
                    value = workspace,
                    options = listOf("exec", "mirror", "virtual"),
                ) { workspace = it }
                Selector(
                    label = stringResource(R.string.lsp_root_mode),
                    value = rootMode,
                    options = listOf("nearest", "outermost"),
                ) { rootMode = it }
                EnabledRow(enabled) { enabled = it }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    val init = runCatching { parseObject(initializationOptions) }
                    val settingsValue = runCatching { parseObject(settings) }
                    if (init.isFailure || settingsValue.isFailure) {
                        error = init.exceptionOrNull()?.message
                            ?: settingsValue.exceptionOrNull()?.message
                            ?: invalidJson
                    } else onSave(
                        LspLanguageRule(
                            id = id.trim(),
                            label = label.trim(),
                            extensions = commaSet(extensions),
                            exactNames = commaSet(exactNames),
                            markers = commaSet(markers),
                            command = commandTokens(command),
                            args = lines(args),
                            workspace = workspace,
                            rootMode = rootMode,
                            enabled = enabled,
                            lspLanguageIds = assignments(languageIds),
                            env = assignments(env),
                            initializationOptions = init.getOrNull(),
                            settings = settingsValue.getOrNull(),
                        ),
                    )
                },
            ) { Text(stringResource(R.string.editor_save)) }
        },
    )
}

@Composable
private fun OverrideDialog(
    initial: LspOverrideRule?,
    onDismiss: () -> Unit,
    onSave: (LspOverrideRule) -> Unit,
) {
    var prefix by remember { mutableStateOf(initial?.prefix.orEmpty()) }
    var language by remember { mutableStateOf(initial?.language.orEmpty()) }
    var markers by remember { mutableStateOf(initial?.markers.orEmpty().joinToString(", ")) }
    var command by remember { mutableStateOf(initial?.command.orEmpty().joinToString(" ")) }
    var args by remember { mutableStateOf(initial?.args.orEmpty().joinToString("\n")) }
    var env by remember {
        mutableStateOf(initial?.env.orEmpty().entries.joinToString("\n") { "${it.key}=${it.value}" })
    }
    var initializationOptions by remember { mutableStateOf(initial?.initializationOptions?.toString(2).orEmpty()) }
    var settings by remember { mutableStateOf(initial?.settings?.toString(2).orEmpty()) }
    var workspace by remember { mutableStateOf(initial?.workspace.orEmpty()) }
    var rootMode by remember { mutableStateOf(initial?.rootMode.orEmpty()) }
    var enabled by remember { mutableStateOf(initial?.enabled ?: true) }
    var error by remember { mutableStateOf<String?>(null) }
    val inherit = stringResource(R.string.lsp_inherit)
    val invalidJson = stringResource(R.string.lsp_invalid_json)
    val valid = prefix.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (initial == null) R.string.lsp_add_override else R.string.agent_provider_edit))
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Field(stringResource(R.string.lsp_prefix), prefix, { prefix = it })
                Field(stringResource(R.string.lsp_language), language, { language = it })
                Field(stringResource(R.string.lsp_markers), markers, { markers = it })
                Field(stringResource(R.string.lsp_command), command, { command = it })
                Field(stringResource(R.string.lsp_args), args, { args = it }, singleLine = false)
                Field(stringResource(R.string.lsp_env), env, { env = it }, singleLine = false)
                Field(
                    stringResource(R.string.lsp_initialization_options),
                    initializationOptions,
                    { initializationOptions = it },
                    singleLine = false,
                )
                Field(stringResource(R.string.lsp_settings), settings, { settings = it }, singleLine = false)
                Selector(
                    label = stringResource(R.string.lsp_workspace),
                    value = workspace.ifBlank { inherit },
                    options = listOf(inherit, "exec", "mirror", "virtual"),
                ) { workspace = if (it == inherit) "" else it }
                Selector(
                    label = stringResource(R.string.lsp_root_mode),
                    value = rootMode.ifBlank { inherit },
                    options = listOf(inherit, "nearest", "outermost"),
                ) { rootMode = if (it == inherit) "" else it }
                EnabledRow(enabled) { enabled = it }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    val init = runCatching { parseObject(initializationOptions) }
                    val settingsValue = runCatching { parseObject(settings) }
                    if (init.isFailure || settingsValue.isFailure) {
                        error = init.exceptionOrNull()?.message
                            ?: settingsValue.exceptionOrNull()?.message
                            ?: invalidJson
                    } else onSave(
                        LspOverrideRule(
                            prefix = prefix.trim(),
                            language = language.trim(),
                            markers = commaSet(markers),
                            command = commandTokens(command),
                            args = lines(args),
                            workspace = workspace,
                            rootMode = rootMode,
                            enabled = enabled,
                            env = assignments(env),
                            initializationOptions = init.getOrNull(),
                            settings = settingsValue.getOrNull(),
                        ),
                    )
                },
            ) { Text(stringResource(R.string.editor_save)) }
        },
    )
}

@Composable
private fun Field(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    singleLine: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = singleLine,
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Selector(
    label: String,
    value: String,
    options: List<String>,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = open,
        onExpandedChange = { open = it },
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
        ) {
            for (option in options) {
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onSelect(option)
                        open = false
                    },
                )
            }
        }
    }
}

@Composable
private fun EnabledRow(enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { onChange(!enabled) },
    ) {
        Text(stringResource(R.string.lsp_enabled), modifier = Modifier.weight(1f))
        Switch(checked = enabled, onCheckedChange = onChange)
    }
}

private fun commandTokens(text: String): List<String> =
    text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

private fun lines(text: String): List<String> =
    text.lines().map { it.trim() }.filter { it.isNotEmpty() }

private fun commaSet(text: String): Set<String> =
    text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

private fun assignments(text: String): Map<String, String> =
    text.lines().mapNotNull { line ->
        val index = line.indexOf('=')
        if (index <= 0) return@mapNotNull null
        line.substring(0, index).trim() to line.substring(index + 1).trim()
    }.toMap()

private fun parseObject(text: String): JSONObject? =
    if (text.isBlank()) null else JSONObject(text)
