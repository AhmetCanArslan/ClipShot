package com.arslan.clipshot

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

/**
 * The two screenshot actions shared by the notification buttons
 * ([NotificationActionReceiver]) and the floating overlay button
 * ([OverlayService]): copy the image to the clipboard, and delete it.
 */
object ScreenshotActions {

    /**
     * Copies [file] to the clipboard as an image. The bytes are first copied to
     * the app cache (and shared through the FileProvider) so the clip survives
     * after the original is deleted.
     */
    fun copyToClipboard(context: Context, file: File): Boolean {
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
            val clip = ClipData.newUri(context.contentResolver, "Screenshot", uri).apply {
                // Keep the system "Copied" chip from rendering a thumbnail of the
                // screenshot (the chip itself can't be suppressed by a normal app).
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    description.extras = PersistableBundle().apply {
                        putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                    }
                }
            }
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(clip)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** Deletes the raw file and removes any matching MediaStore entry. */
    fun delete(context: Context, file: File) {
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
}
