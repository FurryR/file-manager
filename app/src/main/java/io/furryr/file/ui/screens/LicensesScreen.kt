package io.furryr.file.ui.screens

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import io.furryr.file.R
import io.furryr.file.ui.util.openExternalBrowser
import org.json.JSONObject

private data class LicenseRecord(
    val group: String? = null,
    val name: String,
    val version: String? = null,
    val license: String,
    val url: String? = null,
)

/** The generated asset, or an empty list when this is a source checkout. */
private fun loadGeneratedLicenses(context: Context): List<LicenseRecord> = runCatching {
    val text = context.assets.open("licenses.json").bufferedReader().use { it.readText() }
    val rows = JSONObject(text).getJSONArray("components")
    buildList {
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            add(
                LicenseRecord(
                    group = row.optString("group").takeIf { it.isNotBlank() },
                    name = row.optString("name"),
                    version = row.optString("version").takeIf { it.isNotBlank() },
                    license = row.optString("license").ifBlank { "Unknown" },
                    url = row.optString("url").takeIf { it.isNotBlank() },
                ),
            )
        }
    }
}.getOrDefault(emptyList())

/** Shown only when the generated asset is absent (for example in a bare IDE checkout). */
private val FALLBACK_LICENSES = listOf(
    LicenseRecord(name = "File", license = "GNU GPLv3", url = "https://www.gnu.org/licenses/gpl-3.0.html"),
    LicenseRecord(name = "AndroidX Activity", license = "Apache License 2.0", url = "https://developer.android.com/jetpack/androidx/releases/activity"),
    LicenseRecord(name = "AndroidX Compose", license = "Apache License 2.0", url = "https://developer.android.com/jetpack/compose"),
    LicenseRecord(name = "AndroidX Lifecycle", license = "Apache License 2.0", url = "https://developer.android.com/jetpack/androidx/releases/lifecycle"),
    LicenseRecord(name = "Kotlin", license = "Apache License 2.0", url = "https://kotlinlang.org"),
    LicenseRecord(name = "Material 3", license = "Apache License 2.0", url = "https://m3.material.io"),
    LicenseRecord(name = "Protocol Buffers", license = "BSD 3-Clause License", url = "https://protobuf.dev"),
    LicenseRecord(name = "Shizuku", license = "Apache License 2.0", url = "https://github.com/RikkaApps/Shizuku"),
    LicenseRecord(name = "HiddenApiBypass", license = "Apache License 2.0", url = "https://github.com/LSPosed/AndroidHiddenApiBypass"),
    LicenseRecord(name = "Termux Terminal View", license = "GNU GPLv3", url = "https://github.com/termux/termux-app"),
    LicenseRecord(name = "AndroidX DocumentFile", license = "Apache License 2.0", url = "https://developer.android.com/reference/androidx/documentfile"),
    LicenseRecord(name = "Tokio", license = "MIT License", url = "https://tokio.rs"),
    LicenseRecord(name = "Kotlin Coroutines", license = "Apache License 2.0", url = "https://github.com/Kotlin/kotlinx.coroutines"),
    LicenseRecord(name = "Prost", license = "Apache License 2.0", url = "https://github.com/tokio-rs/prost"),
    LicenseRecord(name = "Rust", license = "Apache License 2.0 / MIT", url = "https://www.rust-lang.org"),
    LicenseRecord(name = "PRoot", license = "GNU GPLv2", url = "https://github.com/termux/proot"),
    LicenseRecord(name = "PulseAudio", license = "GNU LGPLv2.1 or later (see native-licenses/LICENSE-PulseAudio)", url = "https://www.freedesktop.org/wiki/Software/PulseAudio/"),
    LicenseRecord(name = "libsndfile", license = "GNU LGPLv2.1 or later", url = "https://libsndfile.github.io/libsndfile/"),
    LicenseRecord(name = "libltdl", license = "GNU LGPLv2.1 or later", url = "https://www.gnu.org/software/libtool/"),
    LicenseRecord(name = "Smithay", license = "MIT License", url = "https://github.com/Smithay/smithay"),
    LicenseRecord(name = "libxkbcommon", license = "MIT License", url = "https://github.com/xkbcommon/libxkbcommon"),
    LicenseRecord(name = "xkeyboard-config", license = "MIT License", url = "https://gitlab.freedesktop.org/xkeyboard-config/xkeyboard-config"),
    LicenseRecord(name = "libepoxy", license = "MIT License", url = "https://github.com/anholt/libepoxy"),
    LicenseRecord(name = "Apache Commons Net", license = "Apache License 2.0", url = "https://commons.apache.org/proper/commons-net/"),
    LicenseRecord(name = "JSch", license = "BSD 3-Clause License", url = "https://github.com/mwiede/jsch"),
    LicenseRecord(name = "Sora Editor / TextMate", license = "GNU LGPL 2.1 or later", url = "https://github.com/Rosemoe/sora-editor/tree/0.23.5"),
    LicenseRecord(name = "EdDSA-Java", license = "CC0 1.0 / Public Domain", url = "https://github.com/str4d/ed25519-java"),
)

@Composable
fun LicensesScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val generated = remember { loadGeneratedLicenses(context) }
    val rows = generated.ifEmpty { FALLBACK_LICENSES }
    val sections = remember(rows) {
        val order = listOf("Android / JVM", "Rust", "Native")
        rows.groupBy { it.group }
            .entries
            .sortedBy { (group, _) -> order.indexOf(group).let { if (it < 0) order.size else it } }
    }
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.licenses_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            }
            for ((group, entries) in sections) {
                if (!group.isNullOrBlank()) {
                    item(key = "group-$group") {
                        Text(
                            text = groupLabel(group),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                        )
                    }
                }
                items(
                    items = entries,
                    key = { "${it.group}|${it.name}|${it.version}|${it.license}" },
                ) { entry ->
                    LicenseEntry(entry)
                }
            }
        }
    }
}

@Composable
private fun groupLabel(group: String): String = when (group) {
    "Android / JVM" -> stringResource(R.string.licenses_group_android)
    "Rust" -> stringResource(R.string.licenses_group_rust)
    "Native" -> stringResource(R.string.licenses_group_native)
    else -> group
}

@Composable
private fun LicenseEntry(entry: LicenseRecord) {
    val context = LocalContext.current
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(
            text = if (entry.version.isNullOrBlank()) {
                entry.name
            } else {
                "${entry.name} · ${entry.version}"
            },
            style = MaterialTheme.typography.titleMedium
        )
        entry.url?.let { url ->
            Text(
                text = url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable { openExternalBrowser(context, url) },
            )
        }
        Text(
            text = entry.license,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
    }
}
