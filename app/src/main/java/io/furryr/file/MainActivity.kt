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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.furryr.file.daemon.DaemonLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import io.furryr.file.provider.ContainerDaemonProvider
import io.furryr.file.provider.DaemonFileProvider
import io.furryr.file.provider.DocumentFileProvider
import io.furryr.file.provider.SAFProvider
import io.furryr.file.ui.theme.FileManagerTheme
import io.furryr.file.ui.util.OperationNotification

class MainActivity : ComponentActivity() {
    companion object {
        var pendingReopenOperation = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OperationNotification.createChannel(this)
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("FileManager", "Uncaught exception in thread ${thread.name}", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
        if (intent?.getBooleanExtra("show_operation", false) == true) {
            pendingReopenOperation = true
        }

        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        DaemonLauncher.init(this)
        DaemonLauncher.setRootEnabled(prefs.getBoolean("root_enabled", false))
        DaemonLauncher.setShellEnabled(prefs.getBoolean("shell_enabled", false))
        DaemonLauncher.setSuPath(prefs.getString("su_path", "") ?: "")

        FileRepository.setDefault(DaemonFileProvider())
        ContainerDaemonProvider.init(this)
        FileRepository.register("container", ContainerDaemonProvider)
        DocumentFileProvider.init(this)
        FileRepository.register("document", DocumentFileProvider)
        SAFProvider.init(this)
        FileRepository.register("saf", SAFProvider)

        // Pre-start the daemon off the main thread. Failure is logged and
        // observable via DaemonLauncher.daemonState; the lazy-init in
        // DaemonLauncher.send() will retry on the next API call.
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                DaemonLauncher.connect()
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
                        darkTheme = darkTheme,
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
        if (intent.getBooleanExtra("show_operation", false)) {
            pendingReopenOperation = true
        }
    }
}
