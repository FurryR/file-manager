package io.furryr.file.agent

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.furryr.file.R
import io.furryr.file.ui.theme.FileManagerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ContainerWizardActivity : ComponentActivity() {
    companion object {
        const val EXTRA_CONTAINER_NAME = "container_name"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FileManagerTheme(darkTheme = true) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ContainerWizardScreen(onBack = { finish() })
                }
            }
        }
    }
}

private sealed class WizardState {
    data object Input : WizardState()
    data class Creating(val status: String, val progress: Float = 0f) : WizardState()
    data class Error(val message: String) : WizardState()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContainerWizardScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var imageRef by rememberSaveable { mutableStateOf("library/alpine:latest") }
    var containerName by rememberSaveable { mutableStateOf("") }
    var state by remember { mutableStateOf<WizardState>(WizardState.Input) }
    var downloadedBytes by remember { mutableLongStateOf(0L) }
    var totalBytes by remember { mutableLongStateOf(0L) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.wizard_create)) },
                navigationIcon = {
                    if (state !is WizardState.Creating) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.wizard_back))
                        }
                    }
                }
            )
        }
    ) { padding ->
        // Disable back gesture during download
        BackHandler(enabled = state is WizardState.Creating) { /* no-op */ }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (val s = state) {
                is WizardState.Input -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp)
                    ) {
                        Text(
                            stringResource(R.string.wizard_pull_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.wizard_pull_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        Spacer(Modifier.height(24.dp))

                        OutlinedTextField(
                            value = imageRef,
                            onValueChange = { imageRef = it },
                            label = { Text(stringResource(R.string.wizard_image_label)) },
                            placeholder = { Text(stringResource(R.string.wizard_image_placeholder)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = state is WizardState.Input,
                        )

                        Spacer(Modifier.height(12.dp))

                        OutlinedTextField(
                            value = containerName,
                            onValueChange = { containerName = it },
                            label = { Text(stringResource(R.string.wizard_name_label)) },
                            placeholder = { Text(stringResource(R.string.wizard_name_placeholder)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = state is WizardState.Input,
                        )

                        Spacer(Modifier.height(24.dp))

                        Button(
                            onClick = {
                                val name = containerName.trim()
                                if (name.isEmpty()) return@Button
                                val img = imageRef.trim()
                                if (img.isEmpty()) return@Button

                                state = WizardState.Creating("Connecting to Docker Hub...")
                                scope.launch {
                                    try {
                                        val activity = context as ContainerWizardActivity
                                        state = WizardState.Creating("Downloading image layers...")
                                        val result = withContext(Dispatchers.IO) {
                                            ContainerManager.createContainer(
                                                activity, name, img
                                            )
                                        }
                                        val container = result.getOrThrow()
                                        val intent = Intent().apply {
                                            putExtra(ContainerWizardActivity.EXTRA_CONTAINER_NAME, container.name)
                                        }
                                        activity.setResult(Activity.RESULT_OK, intent)
                                        activity.finish()
                                    } catch (e: Exception) {
                                        state = WizardState.Error(e.message ?: "Failed to create container")
                                    }
                                }
                            },
                            enabled = containerName.isNotBlank() && imageRef.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.wizard_create_button))
                        }
                    }
                }

                is WizardState.Creating -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(24.dp))
                        Text(
                            stringResource(R.string.wizard_creating),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            s.status,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                is WizardState.Error -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                    ) {
                        Text(
                            stringResource(R.string.wizard_failed),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            s.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(24.dp))
                        Button(onClick = {
                            state = WizardState.Input
                        }) {
                            Text(stringResource(R.string.wizard_back))
                        }
                    }
                }
            }
        }
    }
}
