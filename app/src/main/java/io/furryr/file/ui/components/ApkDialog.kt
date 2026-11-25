package io.furryr.file.ui.components

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import io.furryr.file.R
import io.furryr.file.ui.icons.Icons
import io.furryr.file.ui.util.formatModifiedTime
import io.furryr.file.ui.util.formatSize
import java.io.File

internal data class ApkDetails(
    val info: PackageInfo,
    val installed: PackageInfo?,
    val label: String,
    val icon: ImageBitmap?,
    val size: Long,
) {
    val versionCode: Long get() = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else {
        @Suppress("DEPRECATION")
        info.versionCode.toLong()
    }
}

/** Installed-app facts; file APKs are parsed by [io.furryr.file.render.ApkManifest]. */
internal fun loadApkDetails(context: Context, apk: File, installedPackage: PackageInfo): ApkDetails {
    val pm = context.packageManager
    val application = requireNotNull(installedPackage.applicationInfo)
    return ApkDetails(
        info = installedPackage,
        installed = installedPackage,
        label = application.loadLabel(pm).toString(),
        icon = application.loadIcon(pm).toBitmap(96, 96).asImageBitmap(),
        size = apk.length(),
    )
}

internal enum class ApkAction { OpenWith, Browse, Install, Launch, Details, Uninstall, Extract }

private fun sdkLabel(api: Int): String {
    val release = when (api) {
        1 -> "1.0"; 2 -> "1.1"; 3 -> "1.5"; 4 -> "1.6"
        5, 6 -> "2.0"; 7 -> "2.1"; 8 -> "2.2"; 9, 10 -> "2.3"
        11 -> "3.0"; 12 -> "3.1"; 13 -> "3.2"; 14, 15 -> "4.0"
        16 -> "4.1"; 17 -> "4.2"; 18 -> "4.3"; 19, 20 -> "4.4"
        21 -> "5.0"; 22 -> "5.1"; 23 -> "6.0"; 24 -> "7.0"; 25 -> "7.1"
        26 -> "8.0"; 27 -> "8.1"; 28 -> "9"; 29 -> "10"; 30 -> "11"
        31, 32 -> if (api == 32) "12L" else "12"
        33 -> "13"; 34 -> "14"; 35 -> "15"; 36 -> "16"; 37 -> "17"
        else -> null
    }
    return if (release == null) "Android (API $api)" else "Android $release (API $api)"
}

@Composable
private fun ApkProperty(label: String, value: String, onClick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, Modifier.width(84.dp), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1f).then(if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)),
            style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The same facts and layout serve a file opener and the installed-app browser. */
@Composable
internal fun ApkDialog(
    details: ApkDetails,
    installedMode: Boolean = false,
    onAction: (ApkAction) -> Unit,
    onNavigate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var operations by remember { mutableStateOf(false) }
    val info = details.info
    val application = requireNotNull(info.applicationInfo)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                val icon = details.icon
                if (icon != null) {
                    Image(icon, null, Modifier.size(48.dp))
                } else {
                    Icon(Icons.Default.Description, null, Modifier.size(48.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(details.label, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(info.versionName.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ApkProperty(stringResource(R.string.apk_package_name), info.packageName)
                ApkProperty(stringResource(R.string.apk_version_code), details.versionCode.toString())
                ApkProperty(stringResource(R.string.apk_size), formatSize(details.size))
                ApkProperty(stringResource(R.string.apk_target_sdk), sdkLabel(application.targetSdkVersion))
                ApkProperty(stringResource(R.string.apk_min_sdk), sdkLabel(application.minSdkVersion))
                details.installed?.let { installed ->
                    installed.applicationInfo?.let { app ->
                        ApkProperty(stringResource(R.string.apk_internal_dir), app.dataDir) { onNavigate(app.dataDir) }
                        val external = File(Environment.getExternalStorageDirectory(), "Android/data/${installed.packageName}").absolutePath
                        ApkProperty(stringResource(R.string.apk_external_dir), external) { onNavigate(external) }
                        ApkProperty(stringResource(R.string.apk_path), app.sourceDir) { onNavigate(File(app.sourceDir).parent ?: app.sourceDir) }
                        ApkProperty(stringResource(R.string.apk_first_install), formatModifiedTime(installed.firstInstallTime))
                        ApkProperty(stringResource(R.string.apk_last_update), formatModifiedTime(installed.lastUpdateTime))
                        ApkProperty("UID", app.uid.toString())
                    }
                }
            }
        },
        confirmButton = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (installedMode) {
                    Box {
                        TextButton(onClick = { operations = true }) { Text(stringResource(R.string.apk_actions)) }
                        DropdownMenu(expanded = operations, onDismissRequest = { operations = false }) {
                            listOf(
                                ApkAction.Launch to R.string.apk_launch,
                                ApkAction.Details to R.string.apk_details,
                                ApkAction.Uninstall to R.string.apk_uninstall,
                            ).forEach { (action, label) ->
                                DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = {
                                    operations = false
                                    onAction(action)
                                })
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { onAction(ApkAction.Extract) }) { Text(stringResource(R.string.apk_extract)) }
                } else {
                    TextButton(onClick = { onAction(ApkAction.OpenWith) }) { Text(stringResource(R.string.open_with_title)) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { onAction(ApkAction.Browse) }) { Text(stringResource(R.string.apk_browse)) }
                    TextButton(onClick = { onAction(ApkAction.Install) }) { Text(stringResource(R.string.apk_install)) }
                }
            }
        },
    )
}
