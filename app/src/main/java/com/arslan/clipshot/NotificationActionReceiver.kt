package com.arslan.clipshot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import java.io.File

/**
 * Handles the notification action buttons:
 *  - [ScreenshotNotifier.ACTION_DELETE]: delete the screenshot.
 *  - [ScreenshotNotifier.ACTION_COPY_DELETE]: copy the image to the clipboard
 *    then delete the original.
 *
 * The actual copy/delete work lives in [ScreenshotActions], shared with the
 * overlay button.
 */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val path = intent.getStringExtra(ScreenshotNotifier.EXTRA_PATH) ?: return
        val notifId = intent.getIntExtra(ScreenshotNotifier.EXTRA_NOTIF_ID, -1)

        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                when (action) {
                    ScreenshotNotifier.ACTION_DELETE -> {
                        ScreenshotActions.delete(app, File(path))
                        toast(app, R.string.toast_deleted)
                    }
                    ScreenshotNotifier.ACTION_COPY_DELETE -> {
                        val file = File(path)
                        val ok = ScreenshotActions.copyToClipboard(app, file)
                        ScreenshotActions.delete(app, file)
                        toast(app, if (ok) R.string.toast_copied_deleted else R.string.toast_deleted)
                    }
                }
                if (notifId != -1) {
                    NotificationManagerCompat.from(app).cancel(notifId)
                }
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun toast(context: Context, resId: Int) {
        android.os.Handler(context.mainLooper).post {
            Toast.makeText(context, resId, Toast.LENGTH_SHORT).show()
        }
    }
}
