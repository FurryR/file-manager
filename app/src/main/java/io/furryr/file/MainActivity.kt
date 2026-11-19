package io.furryr.file

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import io.furryr.file.daemon.DaemonManager
import io.furryr.file.hub.Hub
import io.furryr.file.container.ContainerManager
import io.furryr.file.crash.CrashActivity
import io.furryr.file.crash.CrashReporter
import io.furryr.file.open.IncomingOpen
import io.furryr.file.open.OpenRequest
import io.furryr.file.pulse.PulseManager
import io.furryr.file.ui.theme.FileManagerTheme
import io.furryr.file.ui.util.OperationNotification
import io.furryr.file.ui.util.ContainerNotification

class MainActivity : ComponentActivity() {
    companion object {
        const val ACTION_OPEN_TERMINAL = "io.furryr.file.action.OPEN_TERMINAL"
        var pendingReopenOperation = false
    }

    internal var terminalOpenRequest by mutableIntStateOf(0)
        private set

    internal var containerErrorRequest by mutableStateOf<String?>(null)
        private set

    /** A file or link another app asked this one to show. */
    internal var openRequest by mutableStateOf<OpenRequest?>(null)
        private set

    private var appliedLanguage = AppLocale.SYSTEM

    override fun attachBaseContext(newBase: Context) {
        appliedLanguage = AppLocale.current(newBase)
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onResume() {
        super.onResume()
        runCatching { PulseManager.ensureResident(applicationContext) }
            .onFailure { Log.w("FileManager", "PulseAudio listener pre-start failed", it) }
        runCatching { ContainerManager.startResolverWatch(applicationContext) }
            .onFailure { Log.w("FileManager", "resolver watch registration failed", it) }
        if (AppLocale.current(this) != appliedLanguage) recreate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashReporter.pendingReport(this)?.let { report ->
            startActivity(CrashActivity.intent(this, report))
            finish()
            return
        }
        OperationNotification.createChannel(this)
        ContainerNotification.createChannel(this)
        handleIntent(intent)
        if (intent?.getBooleanExtra("show_operation", false) == true) {
            pendingReopenOperation = true
        }

        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        // chrome://inspect over adb; the in-app DevTools tab serves the
        // official frontend from a loopback bridge over the same socket.
        WebView.setWebContentsDebuggingEnabled(true)
        val world = AppRuntime.appWorld(this)

        // A terminal inherits HUB_SOCKET when its PTY is spawned, so the hub
        // must be listening before the shell can be opened.
        runCatching { Hub.start(applicationContext, world.files) }
            .onFailure { Log.w("FileManager", "hub start failed", it) }

        // Bind the resident PulseAudio listener as early as possible. It binds its
        // socket immediately, so a container launch only reuses an existing
        // socket; the audio core itself is started later, on demand.
        runCatching { PulseManager.ensureResident(applicationContext) }
            .onFailure { Log.w("FileManager", "PulseAudio listener pre-start failed", it) }

        // Pre-start the daemon off the main thread. Failure is logged and
        // observable via DaemonManager.status; the lazy-init in
        // DaemonManager will retry on the next API call.
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                DaemonManager.warmUp().getOrThrow()
                Log.i("FileManager", "Daemon pre-start complete")
            } catch (e: Exception) {
                Log.w("FileManager", "Daemon pre-start failed (will retry on first operation)", e)
            }
        }

        setContent {
            var darkTheme by remember { mutableStateOf(prefs.getBoolean("dark_theme", true)) }

            FileManagerTheme(darkTheme = darkTheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    FileManagerApp(
                        world = world,
                        darkTheme = darkTheme,
                        terminalOpenRequest = terminalOpenRequest,
                        containerErrorRequest = containerErrorRequest,
                        openRequest = openRequest,
                        onToggleTheme = {
                            darkTheme = !darkTheme
                            prefs.edit().putBoolean("dark_theme", darkTheme).apply()
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
        if (intent.getBooleanExtra("show_operation", false)) {
            pendingReopenOperation = true
        }
    }

    /**
     * Fold one delivered intent into the shell state.
     *
     * The open payload is cleared once read, so an activity recreate shows the
     * pages the shell already restored instead of opening the same file twice;
     * a redelivered intent (after process death) still opens it again.
     */
    private fun handleIntent(intent: android.content.Intent?) {
        if (intent == null) return
        containerErrorRequest = intent.getStringExtra("container_error")
        if (intent.action == ACTION_OPEN_TERMINAL) terminalOpenRequest++
        val addresses = IncomingOpen.addresses(intent)
        if (addresses.isEmpty()) return
        openRequest = OpenRequest(++openRequestId, addresses)
        intent.data = null
        intent.clipData = null
        intent.removeExtra(android.content.Intent.EXTRA_STREAM)
    }

    private var openRequestId = 0
}
