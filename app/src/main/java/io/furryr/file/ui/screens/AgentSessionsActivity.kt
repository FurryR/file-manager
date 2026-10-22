package io.furryr.file.ui.screens

import android.content.Context
import android.os.Bundle
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import io.furryr.file.AppLocale
import io.furryr.file.R
import io.furryr.file.agent.AgentSettings
import io.furryr.file.ui.theme.FileManagerTheme

class AgentSessionsActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        setContent {
            FileManagerTheme(darkTheme = prefs.getBoolean("dark_theme", true)) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SessionsPage(onBack = { finish() })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    var sessions by remember { mutableStateOf(AgentSettings.sessions(context)) }
    var viewing by remember { mutableStateOf<AgentSettings.SessionInfo?>(null) }
    var renaming by remember { mutableStateOf<AgentSettings.SessionInfo?>(null) }

    fun reload() {
        sessions = AgentSettings.sessions(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.agent_sessions)) },
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
            if (sessions.isEmpty()) {
                Text(
                    stringResource(R.string.agent_sessions_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            for (session in sessions) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(session.title, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "${session.messageCount} ${stringResource(R.string.agent_session_messages)} · " +
                                DateUtils.getRelativeTimeSpanString(session.updatedAt),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { viewing = session }) {
                        Text(stringResource(R.string.agent_session_view))
                    }
                    TextButton(onClick = { renaming = session }) {
                        Text(stringResource(R.string.agent_session_rename))
                    }
                    TextButton(
                        onClick = {
                            AgentSettings.deleteSession(context, session.id)
                            reload()
                        },
                    ) {
                        Text(stringResource(R.string.agent_session_delete))
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
    }

    viewing?.let { session ->
        SessionViewDialog(session = session, onDismiss = { viewing = null })
    }
    renaming?.let { session ->
        RenameDialog(
            session = session,
            onDismiss = { renaming = null },
            onSave = { title ->
                AgentSettings.renameSession(context, session.id, title)
                renaming = null
                reload()
            },
        )
    }
}

@Composable
private fun SessionViewDialog(
    session: AgentSettings.SessionInfo,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val messages = remember(session.id) { AgentSettings.sessionMessages(context, session.id) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(session.title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (messages.isEmpty()) {
                    Text(stringResource(R.string.agent_sessions_empty))
                }
                for (message in messages) {
                    Text(
                        message.role.replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(message.content, style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.confirm)) }
        },
    )
}

@Composable
private fun RenameDialog(
    session: AgentSettings.SessionInfo,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var title by remember { mutableStateOf(session.title) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.agent_session_rename_title)) },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                singleLine = true,
                label = { Text(stringResource(R.string.agent_session_title)) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank(),
                onClick = { onSave(title) },
            ) { Text(stringResource(R.string.confirm)) }
        },
    )
}
