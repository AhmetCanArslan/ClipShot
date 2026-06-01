package com.arslan.clipshot

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import java.io.File

/**
 * Handles the notification action buttons:
 *  - [ScreenshotNotifier.ACTION_DELETE]: delete the screenshot.
 *  - [ScreenshotNotifier.ACTION_COPY_DELETE]: copy the image to the clipboard
 *    (via a cached copy so the clip survives), then delete the original.
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
                        delete(app, File(path))
                        toast(app, R.string.toast_deleted)
                    }
                    ScreenshotNotifier.ACTION_COPY_DELETE -> {
                        val file = File(path)
                        val ok = copyToClipboard(app, file)
                        delete(app, file)
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

    private fun copyToClipboard(context: Context, file: File): Boolean {
        if (!file.exists()) return false
        return try {
            val dir = File(context.cacheDir, "clipboard").apply { mkdirs() }
            val copy = File(dir, file.name)
            file.copyTo(copy, overwrite = true)

            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                copy
            )
            val clip = ClipData.newUri(context.contentResolver, "Screenshot", uri)
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(clip)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** Deletes the raw file and removes any matching MediaStore entry. */
    private fun delete(context: Context, file: File) {
        val path = file.absolutePath
        runCatching { if (file.exists()) file.delete() }
        runCatching {
            context.contentResolver.delete(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                "${MediaStore.Images.Media.DATA}=?",
                arrayOf(path)
            )
        }
    }

    private fun toast(context: Context, resId: Int) {
        android.os.Handler(context.mainLooper).post {
            Toast.makeText(context, resId, Toast.LENGTH_SHORT).show()
        }
    }
}
