package io.furryr.file.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.furryr.file.R

/**
 * A provider endpoint is reachable in principle but its backing app has not
 * started. The renderer supplies the endpoint; this component resolves the
 * platform package and offers the one action a user can take.
 *
 * [onRetry] runs once when the host resumes after a launch attempt, so the
 * list reloads without a manual refresh.
 */
@Composable
fun ServiceUnavailableState(
    endpoint: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // FIXME: authority→package is best-effort. System providers, work profiles
    // and disabled packages can fail resolution; the launch button then hides.
    val target = remember(endpoint) {
        context.packageManager.resolveContentProvider(endpoint, 0)?.packageName?.let { pkg ->
            val label = runCatching {
                context.packageManager.getApplicationLabel(
                    context.packageManager.getApplicationInfo(pkg, 0),
                ).toString()
            }.getOrDefault(pkg)
            pkg to label
        }
    }
    var launched by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        if (launched) {
            launched = false
            onRetry()
        }
        onPauseOrDispose { }
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_android_crying),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(72.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.service_not_started, target?.second ?: endpoint),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.service_not_started_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (target != null) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = {
                if (launchApplication(context, target.first)) {
                    launched = true
                } else {
                    Toast.makeText(
                        context,
                        R.string.service_launch_failed,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }) {
                Text(stringResource(R.string.launch_service))
            }
        }
    }
}

private fun launchApplication(context: Context, packageName: String): Boolean = runCatching {
    val launch = context.packageManager.getLaunchIntentForPackage(packageName)
        ?: Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        )
    context.startActivity(launch)
}.isSuccess
