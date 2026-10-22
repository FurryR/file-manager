package io.furryr.file

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import io.furryr.file.agent.SessionManager
import io.furryr.file.core.DaemonManager

/**
 * Foreground service shown while any long-running runtime exists: terminal
 * sessions or live container daemons.
 *
 * Keeps the process in the foreground while the app is backgrounded. Users can
 * opt into a [PowerManager.WakeLock] so commands keep running with the screen
 * off. The notification exposes two actions:
 *
 * - toggle the wake lock ("Hold WakeLock" / "Release WakeLock"; released by default),
 * - exit, which closes every terminal session and container daemon and stops
 *   the service.
 *
 * Lifetime is owned by the service itself: [SessionManager] starts it when the
 * first session is created, a live container daemon starts it via the named
 * daemon change listener, and the service listens for both kinds of changes to
 * refresh the notification counts and stop once nothing is left running.
 */
class RuntimeForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "runtime"
        private const val NOTIFICATION_ID = 1002
        private const val WAKE_LOCK_TAG = "io.furryr.file:runtime"

        const val ACTION_START = "io.furryr.file.action.RUNTIME_START"
        const val ACTION_EXIT = "io.furryr.file.action.RUNTIME_EXIT"
        const val ACTION_TOGGLE_WAKE_LOCK = "io.furryr.file.action.RUNTIME_TOGGLE_WAKE_LOCK"

        /** Show (or refresh) the runtime foreground notification. */
        fun start(context: Context) {
            val intent = Intent(context, RuntimeForegroundService::class.java)
                .setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Hide the notification and stop the service. */
        fun stop(context: Context) {
            context.stopService(Intent(context, RuntimeForegroundService::class.java))
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeLockHeld = false
    private var foregroundStarted = false

    /**
     * Keeps the notification's counts in sync with [SessionManager] and the
     * live named daemons, and tears the service down once nothing is running.
     * The service -- not the Compose UI -- owns session lifetime, so terminals
     * survive the UI being destroyed while the process stays alive.
     */
    private val runtimeChangeListener: () -> Unit = { requestRefresh() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        SessionManager.addSessionChangeListener(runtimeChangeListener)
        DaemonManager.addNamedChangeListener(runtimeChangeListener)
        // Each new service starts without a wake lock. Notification refreshes
        // and additional sessions preserve the user's choice for this lifetime.
        setWakeLockHeld(false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_EXIT -> {
                SessionManager.destroyAll()
                DaemonManager.closeNamedConnections()
                stopSelf()
            }
            ACTION_TOGGLE_WAKE_LOCK -> {
                if (!hasRunningWork()) {
                    stopSelf()
                } else {
                    setWakeLockHeld(!wakeLockHeld)
                    startForegroundCompat()
                }
            }
            else -> {
                // Always enter the foreground state first: the system requires
                // startForeground() within a few seconds of the service being
                // started, even if the last session has already disappeared.
                startForegroundCompat()
                if (!hasRunningWork()) stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        SessionManager.removeSessionChangeListener(runtimeChangeListener)
        DaemonManager.removeNamedChangeListener(runtimeChangeListener)
        setWakeLockHeld(false)
        stopForegroundCompat()
        super.onDestroy()
    }

    private fun hasRunningWork(): Boolean =
        SessionManager.getAllSessions().isNotEmpty() || DaemonManager.connectedNamedCount() > 0

    /** Republish the notification (or stop the service) after any runtime change. */
    private fun requestRefresh() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            refreshNotification()
        } else {
            mainHandler.post { refreshNotification() }
        }
    }

    private fun refreshNotification() {
        val containerCount = DaemonManager.connectedNamedCount()
        val terminalCount = SessionManager.getAllSessions().size
        if (containerCount == 0 && terminalCount == 0) {
            // The system requires startForeground() shortly after the service is
            // started, so satisfy it before tearing ourselves down.
            if (!foregroundStarted) startForegroundCompat()
            stopSelf()
            return
        }
        if (foregroundStarted) {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(containerCount, terminalCount))
        } else {
            startForegroundCompat()
        }
    }

    private fun startForegroundCompat() {
        foregroundStarted = true
        startForeground(
            NOTIFICATION_ID,
            buildNotification(
                DaemonManager.connectedNamedCount(),
                SessionManager.getAllSessions().size,
            ),
        )
    }

    private fun stopForegroundCompat() {
        foregroundStarted = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    internal fun buildNotification(containerCount: Int, terminalCount: Int): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            1002,
            Intent(this, MainActivity::class.java).apply {
                action = MainActivity.ACTION_OPEN_TERMINAL
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val toggleIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, RuntimeForegroundService::class.java).setAction(ACTION_TOGGLE_WAKE_LOCK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val exitIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, RuntimeForegroundService::class.java).setAction(ACTION_EXIT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val wakeLockLabel = getString(
            if (wakeLockHeld) R.string.runtime_wakelock_release
            else R.string.runtime_wakelock_hold
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.runtime_notification_title))
            .setContentText(
                getString(R.string.runtime_notification_text, containerCount, terminalCount)
            )
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, wakeLockLabel, toggleIntent)
            .addAction(0, getString(R.string.runtime_notification_exit), exitIntent)
            .build()
    }

    private fun setWakeLockHeld(held: Boolean) {
        if (held == wakeLockHeld) return
        wakeLockHeld = held
        if (held) acquireWakeLock() else releaseWakeLock()
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wakeLockHeld = false
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.runtime_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }
}
