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
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.furryr.file.AppLocale
import io.furryr.file.R
import io.furryr.file.ui.icons.Icons
import io.furryr.file.ui.theme.FileManagerTheme
import io.furryr.file.ui.util.SearchEngine
import io.furryr.file.ui.util.SearchEngines

/**
 * Browse → Search engine page. Lists the built-in engines and the user's own
 * entries; a tap selects immediately, and only custom entries can be edited or
 * deleted. The store persists one JSON object like the LSP settings page.
 */
class SearchEngineSettingsActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        setContent {
            FileManagerTheme(darkTheme = prefs.getBoolean("dark_theme", true)) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SearchEnginePage(onBack = { finish() })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchEnginePage(onBack: () -> Unit) {
    val context = LocalContext.current
    var custom by remember { mutableStateOf(SearchEngines.custom(context)) }
    var selectedId by remember { mutableStateOf(SearchEngines.current(context).id) }
    var editorOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<SearchEngine?>(null) }

    fun refresh() {
        custom = SearchEngines.custom(context)
        selectedId = SearchEngines.current(context).id
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.search_engines_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp),
            ) {
                Text(
                    stringResource(R.string.search_engines_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    editing = null
                    editorOpen = true
                }) { Text(stringResource(R.string.search_engine_add)) }
            }
            for (engine in SearchEngines.builtIns + custom) {
                EngineRow(
                    engine = engine,
                    selected = engine.id == selectedId,
                    editable = !SearchEngines.isBuiltIn(engine.id),
                    onSelect = {
                        SearchEngines.select(context, engine)
                        selectedId = engine.id
                    },
                    onEdit = {
                        editing = engine
                        editorOpen = true
                    },
                    onDelete = {
                        SearchEngines.delete(context, engine.id)
                        refresh()
                    },
                )
            }
        }
    }

    if (editorOpen) {
        EngineDialog(
            initial = editing,
            onDismiss = { editorOpen = false },
            onSave = { engine ->
                SearchEngines.save(context, engine)
                refresh()
                editorOpen = false
            },
        )
    }
}

@Composable
private fun EngineRow(
    engine: SearchEngine,
    selected: Boolean,
    editable: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(start = 8.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(modifier = Modifier.weight(1f)) {
            Text(engine.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                engine.template,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (editable) {
            TextButton(onClick = onEdit) { Text(stringResource(R.string.agent_provider_edit)) }
            TextButton(onClick = onDelete) { Text(stringResource(R.string.delete)) }
        }
    }
}

@Composable
private fun EngineDialog(
    initial: SearchEngine?,
    onDismiss: () -> Unit,
    onSave: (SearchEngine) -> Unit,
) {
    var label by remember { mutableStateOf(initial?.label.orEmpty()) }
    var template by remember { mutableStateOf(initial?.template.orEmpty()) }
    var suggestion by remember { mutableStateOf(initial?.suggestion.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    val nameRequired = stringResource(R.string.search_engine_name_required)
    val urlInvalid = stringResource(R.string.search_engine_url_invalid)
    val suggestionInvalid = stringResource(R.string.search_engine_suggestion_invalid)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (initial == null) R.string.search_engine_add else R.string.search_engine_edit,
                ),
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(R.string.search_engine_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = template,
                    onValueChange = { template = it },
                    label = { Text(stringResource(R.string.search_engine_url)) },
                    placeholder = { Text(stringResource(R.string.search_engine_url_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = suggestion,
                    onValueChange = { suggestion = it },
                    label = { Text(stringResource(R.string.search_engine_suggestion)) },
                    placeholder = { Text(stringResource(R.string.search_engine_suggestion_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
        confirmButton = {
            TextButton(onClick = {
                val name = label.trim()
                val url = template.trim()
                val suggestUrl = suggestion.trim()
                error = when {
                    name.isEmpty() -> nameRequired
                    !SearchEngines.isValidTemplate(url) -> urlInvalid
                    suggestUrl.isNotEmpty() && !SearchEngines.isValidTemplate(suggestUrl) -> suggestionInvalid
                    else -> null
                }
                if (error == null) {
                    onSave(
                        SearchEngine(
                            id = initial?.id ?: SearchEngines.newId(),
                            label = name,
                            template = url,
                            suggestion = suggestUrl.takeIf { it.isNotEmpty() },
                        ),
                    )
                }
            }) { Text(stringResource(R.string.editor_save)) }
        },
    )
}
