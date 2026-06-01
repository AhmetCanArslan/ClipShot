package com.arslan.clipshot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.io.File

/**
 * Builds notification channels, the ongoing "watching" notification, and the
 * per-screenshot alert notification with Delete / Copy & Delete actions.
 */
object ScreenshotNotifier {

    const val CHANNEL_WATCHER = "clipshot_watcher"
    const val CHANNEL_ALERT = "clipshot_alert"

    const val WATCHER_NOTIFICATION_ID = 1

    const val ACTION_DELETE = "com.arslan.clipshot.action.DELETE"
    const val ACTION_COPY_DELETE = "com.arslan.clipshot.action.COPY_DELETE"
    const val EXTRA_PATH = "extra_path"
    const val EXTRA_NOTIF_ID = "extra_notif_id"

    /** Longest edge (px) of the preview bitmap shown in the notification. */
    private const val PREVIEW_MAX_EDGE = 1024

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)

        val watcher = NotificationChannel(
            CHANNEL_WATCHER,
            context.getString(R.string.channel_watcher_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = context.getString(R.string.channel_watcher_desc) }

        val alert = NotificationChannel(
            CHANNEL_ALERT,
            context.getString(R.string.channel_alert_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply { description = context.getString(R.string.channel_alert_desc) }

        nm.createNotificationChannel(watcher)
        nm.createNotificationChannel(alert)
    }

    fun buildWatcherNotification(context: Context): Notification =
        NotificationCompat.Builder(context, CHANNEL_WATCHER)
            .setSmallIcon(R.drawable.ic_stat_screenshot)
            .setContentTitle(context.getString(R.string.watcher_title))
            .setContentText(context.getString(R.string.watcher_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    /** Decodes a downsampled preview and posts the screenshot alert. */
    fun notifyScreenshot(context: Context, file: File) {
        if (!file.exists()) return
        val notifId = notificationIdFor(file)
        val preview = decodePreview(file)

        val deleteIntent = actionPendingIntent(context, ACTION_DELETE, file, notifId)
        val copyDeleteIntent = actionPendingIntent(context, ACTION_COPY_DELETE, file, notifId)

        val builder = NotificationCompat.Builder(context, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_stat_screenshot)
            .setContentTitle(context.getString(R.string.alert_title))
            .setContentText(file.name)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, context.getString(R.string.action_delete), deleteIntent)
            .addAction(0, context.getString(R.string.action_copy_delete), copyDeleteIntent)

        if (preview != null) {
            builder.setLargeIcon(preview)
            builder.setStyle(
                NotificationCompat.BigPictureStyle().bigPicture(preview)
            )
        }

        NotificationManagerCompat.from(context).notify(notifId, builder.build())
    }

    fun notificationIdFor(file: File): Int =
        // Offset so it never collides with the ongoing watcher notification.
        (file.absolutePath.hashCode() and 0x7FFFFFFF).coerceAtLeast(2)

    private fun actionPendingIntent(
        context: Context,
        action: String,
        file: File,
        notifId: Int
    ): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            this.action = action
            // Unique data so PendingIntents for different files don't collide.
            data = Uri.fromParts("clipshot", file.absolutePath, action)
            putExtra(EXTRA_PATH, file.absolutePath)
            putExtra(EXTRA_NOTIF_ID, notifId)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getBroadcast(context, action.hashCode(), intent, flags)
    }

    private fun decodePreview(file: File): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            var sample = 1
            while (longest / sample > PREVIEW_MAX_EDGE) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(file.absolutePath, opts)
        } catch (_: Throwable) {
            null
        }
    }
}
