package io.furryr.file

import android.content.Context
import android.os.Bundle
import android.util.Log
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
        OperationNotification.createChannel(this)
        ContainerNotification.createChannel(this)
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("FileManager", "Uncaught exception in thread ${thread.name}", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
        if (intent?.getBooleanExtra("show_operation", false) == true) {
            pendingReopenOperation = true
        }
        containerErrorRequest = intent?.getStringExtra("container_error")
        if (intent?.action == ACTION_OPEN_TERMINAL) terminalOpenRequest++

        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        installFileProviders(this)

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
            runCatching { Hub.start(applicationContext) }
                .onFailure { Log.w("FileManager", "hub start failed", it) }
        }

        setContent {
            var darkTheme by remember { mutableStateOf(prefs.getBoolean("dark_theme", true)) }

            FileManagerTheme(darkTheme = darkTheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    FileManagerApp(
                        darkTheme = darkTheme,
                        terminalOpenRequest = terminalOpenRequest,
                        containerErrorRequest = containerErrorRequest,
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
        if (intent.action == ACTION_OPEN_TERMINAL) terminalOpenRequest++
        if (intent.getBooleanExtra("show_operation", false)) {
            pendingReopenOperation = true
        }
        containerErrorRequest = intent.getStringExtra("container_error")
    }
}
