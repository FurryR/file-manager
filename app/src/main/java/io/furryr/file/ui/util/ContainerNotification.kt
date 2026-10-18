package io.furryr.file.ui.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import io.furryr.file.MainActivity
import io.furryr.file.R

object ContainerNotification {
    private const val CHANNEL_ID = "container_creation"
    private const val NOTIFICATION_ID = 1002

    fun createChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Container creation", NotificationManager.IMPORTANCE_LOW)
        )
    }

    fun showProgress(context: Context, name: String, current: Long, total: Long) {
        val builder = builder(context, name)
            .setContentText(context.getString(R.string.container_download_rootfs))
            .setOngoing(true)
        if (total > 0) {
            builder.setProgress(100, ((current * 100) / total).toInt().coerceIn(0, 100), false)
        } else {
            builder.setProgress(0, 0, true)
        }
        notify(context, builder)
    }

    fun showFailure(context: Context, name: String, reason: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("container_error", "[$name]\n$reason")
        }
        val pending = PendingIntent.getActivity(
            context, NOTIFICATION_ID, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = builder(context, name)
            .setContentText(context.getString(R.string.container_create_failed))
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setOngoing(false)
        notify(context, builder)
    }

    fun cancel(context: Context) {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(NOTIFICATION_ID)
    }

    private fun builder(context: Context, name: String): NotificationCompat.Builder =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("[$name]")
            .setOnlyAlertOnce(true)

    private fun notify(context: Context, builder: NotificationCompat.Builder) {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, builder.build())
    }
}
