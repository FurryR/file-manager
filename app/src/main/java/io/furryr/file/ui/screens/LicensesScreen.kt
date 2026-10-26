package io.furryr.file.ui.screens
import io.furryr.file.ui.theme.*
import io.furryr.file.ui.screens.*
import io.furryr.file.ui.components.*
import io.furryr.file.ui.util.*
import io.furryr.file.model.*
import io.furryr.file.R

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
fun LicensesScreen(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = stringResource(R.string.licenses_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            LicenseEntry(
                name = "File Manager",
                url = "https://www.gnu.org/licenses/gpl-3.0.html",
                license = "GNU GPLv3"
            )
            LicenseEntry(
                name = "AndroidX Activity",
                url = "https://developer.android.com/jetpack/androidx/releases/activity",
                license = "Apache License 2.0"
            )
            LicenseEntry(
                name = "AndroidX Compose",
                url = "https://developer.android.com/jetpack/compose",
                license = "Apache License 2.0"
            )
            LicenseEntry(
                name = "AndroidX Lifecycle",
                url = "https://developer.android.com/jetpack/androidx/releases/lifecycle",
                license = "Apache License 2.0"
            )
            LicenseEntry(
                name = "Kotlin",
                url = "https://kotlinlang.org",
                license = "Apache License 2.0"
            )
            LicenseEntry(
                name = "Material 3",
                url = "https://m3.material.io",
                license = "Apache License 2.0"
            )
            LicenseEntry(
                name = "Protocol Buffers",
                url = "https://protobuf.dev",
                license = "BSD 3-Clause License"
            )
            LicenseEntry(
                name = "Shizuku",
                url = "https://github.com/RikkaApps/Shizuku",
                license = "Apache License 2.0"
            )
            LicenseEntry(
                name = "HiddenApiBypass",
                url = "https://github.com/LSPosed/AndroidHiddenApiBypass",
                license = "Apache License 2.0"
            )
            LicenseEntry(
                name = "Termux Terminal View",
                url = "https://github.com/termux/termux-app",
                license = "GNU GPLv3"
            )
            LicenseEntry(
                name = "AndroidX DocumentFile",
                url = "https://developer.android.com/reference/androidx/documentfile",
                license = "Apache License 2.0"
            )
            LicenseEntry(
                name = "Tokio",
                url = "https://tokio.rs",
                license = "MIT License"
            )
            LicenseEntry(
                name = "Kotlin Coroutines",
                url = "https://github.com/Kotlin/kotlinx.coroutines",
                license = "Apache License 2.0"
            )
            LicenseEntry(
                name = "Prost",
                url = "https://github.com/tokio-rs/prost",
                license = "Apache License 2.0"
            )
            LicenseEntry(
                name = "Rust",
                url = "https://www.rust-lang.org",
                license = "Apache License 2.0 / MIT"
            )
            LicenseEntry(
                name = "PRoot",
                url = "https://github.com/termux/proot",
                license = "GNU GPLv2"
            )
            LicenseEntry(
                name = "PulseAudio",
                url = "https://www.freedesktop.org/wiki/Software/PulseAudio/",
                license = "GNU LGPLv2.1 or later (see native-licenses/LICENSE-PulseAudio)"
            )
            LicenseEntry(
                name = "libsndfile",
                url = "https://libsndfile.github.io/libsndfile/",
                license = "GNU LGPLv2.1 or later"
            )
            LicenseEntry(
                name = "libltdl",
                url = "https://www.gnu.org/software/libtool/",
                license = "GNU LGPLv2.1 or later"
            )
            LicenseEntry(
                name = "WLDroid",
                url = "https://github.com/Shelnutt2/wldroid",
                license = "MIT License"
            )
            LicenseEntry(
                name = "wlroots (wldroid fork)",
                url = "https://github.com/Shelnutt2/wlroots",
                license = "MIT License"
            )
            LicenseEntry(
                name = "virglrenderer (wldroid fork)",
                url = "https://github.com/Shelnutt2/virglrenderer",
                license = "MIT License"
            )
            LicenseEntry(
                name = "libepoxy",
                url = "https://github.com/anholt/libepoxy",
                license = "MIT License"
            )
            LicenseEntry(
                name = "Apache Commons Net",
                url = "https://commons.apache.org/proper/commons-net/",
                license = "Apache License 2.0"
            )
            LicenseEntry(
                name = "JSch",
                url = "https://github.com/mwiede/jsch",
                license = "BSD 3-Clause License"
            )
            LicenseEntry(
                name = "EdDSA-Java",
                url = "https://github.com/str4d/ed25519-java",
                license = "CC0 1.0 / Public Domain"
            )
        }
    }
}

@Composable
private fun LicenseEntry(name: String, url: String, license: String) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(
            text = name,
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = url,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = license,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
    }
}
