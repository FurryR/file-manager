package io.furryr.file.ui.screens

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.furryr.file.ui.theme.FileManagerTheme
import io.furryr.file.R
import io.furryr.file.wayland.WaylandManager
import io.furryr.file.wayland.WaylandView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/**
 * Full-screen Wayland viewer. The embedded wldroid/wlroots compositor runs
 * in a resident service; this activity only attaches its surface and input.
 * Containers connect to its
 * `wayland-0` socket through the bind that [WaylandManager] adds to every
 * launch. Kotlin does no window management — the desktop environment inside a
 * container is an ordinary Wayland client of the nested compositor.
 *
 * Back follows the app-wide predictive-back setup (enabled on the activity in
 * the manifest): the default handler finishes the activity without closing
 * the Wayland socket or disconnecting container applications.
 */
class WaylandActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Immersive, edge-to-edge desktop: keep the system bars out of the way
        // while still allowing swipe reveal.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
        )
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        setContent {
            val darkTheme = remember { prefs.getBoolean("dark_theme", true) }
            FileManagerTheme(darkTheme = darkTheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ViewerContent()
                }
            }
        }
    }

    @Composable
    private fun ViewerContent() {
        var prepared by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var view by remember { mutableStateOf<WaylandView?>(null) }
        LaunchedEffect(Unit) {
            runCatching {
                withContext(Dispatchers.IO) { WaylandManager.ensureHost(this@WaylandActivity) }
            }.onSuccess { prepared = true }
                .onFailure {
                    if (it is CancellationException) throw it
                    error = it.message ?: it.toString()
                }
        }
        if (error != null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(error!!)
            }
            return
        }
        if (!prepared) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return
        }
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = { context -> WaylandView(context).also { view = it } },
                onRelease = { view = null },
                modifier = Modifier.fillMaxSize(),
            )
            FloatingActionButton(
                onClick = { view?.toggleKeyboard() },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            ) {
                Icon(Icons.Default.Keyboard, contentDescription = stringResource(R.string.wayland_toggle_keyboard))
            }
        }
    }
}
