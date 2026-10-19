package io.furryr.file.agent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import io.furryr.file.R
import io.furryr.file.core.DaemonManager
import io.furryr.file.provider.installFileProviders
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Keeps container daemons alive independently of their UI and terminal sessions. */
class ContainerServiceForegroundService : Service() {
    companion object {
        private const val CHANNEL = "container_services"
        private const val NOTIFICATION = 1003
        private const val STOP = "io.furryr.file.CONTAINERS_STOP"
        private const val PREFS = "container_service_runtime"
        private const val KEY = "containers"
        private val runtimeMutex = Mutex()
        @Synchronized fun track(context: Context, name: String) {
            val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val names = preferences.getStringSet(KEY, emptySet()).orEmpty() + name
            preferences.edit().putStringSet(KEY, names).putLong("pending.$name", System.currentTimeMillis()).apply()
            val intent = Intent(context, ContainerServiceForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }
        @Synchronized fun forget(context: Context, name: String) {
            val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val names = preferences.getStringSet(KEY, emptySet()).orEmpty() - name
            preferences.edit().putStringSet(KEY, names).remove("pending.$name").apply()
            if (names.isEmpty()) context.stopService(Intent(context, ContainerServiceForegroundService::class.java))
        }
        suspend fun stopContainer(context: Context, name: String) = withContext(Dispatchers.IO) {
            runtimeMutex.withLock {
                SessionManager.destroyContainerSessions(name)
                DaemonManager.closeNamedConnection(name)
                forget(context, name)
            }
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitor: Job? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.container_services_title), NotificationManager.IMPORTANCE_LOW),
        )
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        publish()
        if (intent?.action == STOP) {
            scope.launch { names().forEach { stopContainer(this@ContainerServiceForegroundService, it) }; stopSelf() }
            return START_NOT_STICKY
        }
        if (monitor == null) monitor = scope.launch {
            installFileProviders(this@ContainerServiceForegroundService)
            for (name in names()) {
                runtimeMutex.withLock {
                    // Stop may have removed a name after this restore snapshot
                    // was taken. Never resurrect that explicitly stopped daemon.
                    if (name in names()) DaemonManager.warmNamed(name).onFailure {
                        Log.e("ContainerServices", "Cannot restore container $name", it)
                        forget(this@ContainerServiceForegroundService, name)
                    }
                }
            }
            while (isActive) {
                delay(5_000)
                for (name in names()) {
                    val pending = getSharedPreferences(PREFS, MODE_PRIVATE).getLong("pending.$name", 0L)
                    if (!DaemonManager.isNamedConnected(name) && System.currentTimeMillis() - pending > 15_000L) {
                        forget(this@ContainerServiceForegroundService, name)
                    }
                }
                if (names().isEmpty()) { stopSelf(); break }
                publish()
            }
        }
        return START_STICKY
    }
    private fun names(): Set<String> = getSharedPreferences(PREFS, MODE_PRIVATE).getStringSet(KEY, emptySet()).orEmpty().toSet()
    private fun publish() {
        val open = PendingIntent.getActivity(this, NOTIFICATION, Intent(this, ContainerServicesActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, NOTIFICATION, Intent(this, javaClass).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        startForeground(NOTIFICATION, NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_manage).setContentTitle(getString(R.string.container_services_title))
            .setContentText(names().joinToString(", ")).setContentIntent(open).setOngoing(true)
            .addAction(0, getString(R.string.container_services_stop_all), stop).build())
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
