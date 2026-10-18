package io.furryr.file

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import io.furryr.file.agent.SessionManager

/**
 * Foreground service shown while at least one terminal session is active.
 *
 * Keeps the process alive when the app is backgrounded and holds a
 * [PowerManager.WakeLock] for its whole lifetime so long-running commands keep
 * running with the screen off. The notification exposes two actions:
 *
 * - toggle the wake lock ("Hold WakeLock" / "Release WakeLock"; held by default),
 * - exit, which closes every terminal session and stops the service.
 *
 * Lifetime is owned by the service itself: [SessionManager] starts it when the
 * first session is created, and the service listens for session changes to
 * refresh the notification count and stop once the last session is gone.
 */
class TerminalForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "terminal_session"
        private const val NOTIFICATION_ID = 1002
        private const val WAKE_LOCK_TAG = "io.furryr.file:terminal"

        const val ACTION_START = "io.furryr.file.action.TERMINAL_START"
        const val ACTION_EXIT = "io.furryr.file.action.TERMINAL_EXIT"
        const val ACTION_TOGGLE_WAKE_LOCK = "io.furryr.file.action.TERMINAL_TOGGLE_WAKE_LOCK"

        /** Show (or refresh) the terminal foreground notification. */
        fun start(context: Context) {
            val intent = Intent(context, TerminalForegroundService::class.java)
                .setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Hide the notification and stop the service. */
        fun stop(context: Context) {
            context.stopService(Intent(context, TerminalForegroundService::class.java))
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeLockHeld = false
    private var foregroundStarted = false

    /**
     * Keeps the notification's terminal count in sync with [SessionManager] and
     * tears the service down once the last session is gone. The service -- not
     * the Compose UI -- owns session lifetime, so terminals survive the UI being
     * destroyed while the process stays alive.
     */
    private val sessionChangeListener: () -> Unit = { refreshNotification() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        SessionManager.addSessionChangeListener(sessionChangeListener)
        // Hold the partial wake lock for the whole lifetime of the service so
        // long-running terminal commands keep executing with the screen off.
        // This is the actual "keep alive" guarantee; the foreground
        // notification alone only prevents the process from being reclaimed.
        setWakeLockHeld(true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_EXIT -> {
                SessionManager.destroyAll()
                stopSelf()
            }
            ACTION_TOGGLE_WAKE_LOCK -> {
                if (SessionManager.getAllSessions().isEmpty()) {
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
                if (SessionManager.getAllSessions().isEmpty()) stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        SessionManager.removeSessionChangeListener(sessionChangeListener)
        setWakeLockHeld(false)
        stopForegroundCompat()
        super.onDestroy()
    }

    /** Republish the notification (or stop the service) after any session change. */
    private fun refreshNotification() {
        val count = SessionManager.getAllSessions().size
        if (count == 0) {
            // The system requires startForeground() shortly after the service is
            // started, so satisfy it before tearing ourselves down.
            if (!foregroundStarted) startForegroundCompat()
            stopSelf()
            return
        }
        if (foregroundStarted) {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(count))
        } else {
            startForegroundCompat()
        }
    }

    private fun startForegroundCompat() {
        foregroundStarted = true
        startForeground(NOTIFICATION_ID, buildNotification(SessionManager.getAllSessions().size))
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

    internal fun buildNotification(sessionCount: Int): Notification {
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
            Intent(this, TerminalForegroundService::class.java).setAction(ACTION_TOGGLE_WAKE_LOCK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val exitIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, TerminalForegroundService::class.java).setAction(ACTION_EXIT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val wakeLockLabel = getString(
            if (wakeLockHeld) R.string.terminal_wakelock_release
            else R.string.terminal_wakelock_hold
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.terminal_notification_title))
            .setContentText(getString(R.string.terminal_notification_text, sessionCount))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, wakeLockLabel, toggleIntent)
            .addAction(0, getString(R.string.terminal_notification_exit), exitIntent)
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
                getString(R.string.terminal_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }
}
