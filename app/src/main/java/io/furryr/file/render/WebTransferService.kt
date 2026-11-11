package io.furryr.file.render

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import io.furryr.file.MainActivity
import io.furryr.file.R
import io.furryr.file.provider.web.WebTransfers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Foreground anchor for remote HTTP(S) block reads.
 *
 * A forwarded `content://` URI may be read long after the activity is gone, by
 * a viewer or downloader in another app. Without a foreground service that
 * process can be reclaimed mid-read, so every file with bytes on the wire gets
 * its own ongoing notification: "downloading" for a stream handle (the whole
 * body is transferred) and "downloading on demand" for a range handle (only the
 * requested blocks are fetched). Notifications disappear shortly after the last
 * block lands, so browsing a remote archive does not leave a permanent entry.
 */
class WebTransferService : Service() {

    companion object {
        private const val CHANNEL_ID = "web_transfer"
        private const val NOTIFICATION_BASE = 3000
        private const val IDLE_STOP_MS = 900L
        private const val NOTIFY_INTERVAL_MS = 500L

        @Volatile
        private var running = false

        /** Start the service if it is not already keeping the process alive. */
        fun start(context: Context) {
            if (running) return
            val intent = Intent(context, WebTransferService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val shown = mutableSetOf<Int>()
    private val lastNotifyAt = mutableMapOf<Int, Long>()
    private var foreground = false
    private var primaryId = 0
    private var idleJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        createChannel()
        // A startForegroundService() caller requires startForeground() within a
        // few seconds, even when the read finished before this instance was
        // created. Post the current handle, or a placeholder that the idle
        // timer retires immediately.
        if (!foreground) {
            val fallback = WebTransfers.progress.value.firstOrNull()
            if (fallback != null) {
                render(listOf(fallback))
            } else {
                primaryId = NOTIFICATION_BASE
                startForeground(NOTIFICATION_BASE, buildPlaceholderNotification())
                shown += NOTIFICATION_BASE
                foreground = true
            }
        }
        scope.launch {
            WebTransfers.progress.collect { transfers ->
                val active = transfers.filter { it.reading > 0 }
                if (active.isEmpty()) scheduleIdleStop() else render(active)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        for (id in shown) {
            getSystemService(NotificationManager::class.java).cancel(id)
        }
        shown.clear()
        lastNotifyAt.clear()
        if (foreground) stopForegroundCompat()
        foreground = false
        primaryId = 0
        running = false
        super.onDestroy()
    }

    private fun scheduleIdleStop() {
        if (idleJob != null) return
        idleJob = scope.launch {
            delay(IDLE_STOP_MS)
            // A new block may have arrived while the timer was pending.
            if (WebTransfers.progress.value.none { it.reading > 0 }) stopSelf()
        }
    }

    private fun render(transfers: List<WebTransfers.Progress>) {
        if (transfers.isEmpty()) return
        idleJob?.cancel()
        idleJob = null

        val manager = getSystemService(NotificationManager::class.java)
        val current = LinkedHashMap<Int, WebTransfers.Progress>()
        for (transfer in transfers) current[idFor(transfer.id)] = transfer

        val primary = current.entries.first()
        val primaryChanged = !foreground || primaryId != primary.key
        if (primaryChanged) {
            primaryId = primary.key
            startForeground(primary.key, buildNotification(primary.value))
            foreground = true
        }
        // Post after startForeground: switching the foreground ID can drop the
        // previous notification, and that file may still be reading. Progress
        // is sampled, not posted per network block.
        val now = SystemClock.elapsedRealtime()
        for ((id, transfer) in current) {
            val last = lastNotifyAt[id]
            if (primaryChanged || id !in shown || last == null ||
                now - last >= NOTIFY_INTERVAL_MS
            ) {
                manager.notify(id, buildNotification(transfer))
                shown += id
                lastNotifyAt[id] = now
            }
        }
        val stale = shown.filter { it !in current }
        for (id in stale) {
            manager.cancel(id)
            shown.remove(id)
            lastNotifyAt.remove(id)
        }
    }

    private fun idFor(transferId: Long): Int =
        NOTIFICATION_BASE + 1 + (transferId % 100_000L).toInt()

    private fun buildPlaceholderNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.web_download_title))
            .setContentText(getString(R.string.app_name))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(contentIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    private fun buildNotification(transfer: WebTransfers.Progress): Notification {
        val title = getString(
            if (transfer.onDemand) R.string.web_download_on_demand_title
            else R.string.web_download_title
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(transfer.name)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(contentIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (!transfer.onDemand && transfer.totalBytes > 0L) {
            val progress = (transfer.fetchedBytes * 100 / transfer.totalBytes)
                .toInt()
                .coerceIn(0, 100)
            builder.setProgress(100, progress, false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.web_transfer_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }
}
