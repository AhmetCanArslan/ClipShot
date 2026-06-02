package com.arslan.clipshot

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Foreground service for "Overlay" mode. Watches MediaStore for new screenshots
 * via a [ContentObserver] and shows a floating copy-and-delete button next to
 * the system screenshot preview. Tapping it copies the image to the clipboard
 * and deletes the original (shared logic in [ScreenshotActions]).
 */
class OverlayService : Service() {

    private lateinit var prefs: Prefs
    private lateinit var overlay: ScreenshotOverlay
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var workerThread: HandlerThread
    private lateinit var workerHandler: Handler

    private var observer: ContentObserver? = null

    private var showRunnable: Runnable? = null
    private var autoHideRunnable: Runnable? = null

    /** Debounce: last handled screenshot path + time (elapsedRealtime ms). */
    private var lastPath: String? = null
    private var lastHandled = 0L

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        overlay = ScreenshotOverlay(this)
        ScreenshotNotifier.ensureChannels(this)
        workerThread = HandlerThread("clipshot-overlay").apply { start() }
        workerHandler = Handler(workerThread.looper)
        registerObserver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else 0
        ServiceCompat.startForeground(
            this,
            ScreenshotNotifier.OVERLAY_NOTIFICATION_ID,
            ScreenshotNotifier.buildOverlayNotification(this),
            type
        )
        return START_STICKY
    }

    private fun registerObserver() {
        val obs = object : ContentObserver(workerHandler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) = handleChange()
        }
        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            obs
        )
        observer = obs
    }

    /** Runs on the worker thread (the observer's handler). */
    private fun handleChange() {
        val info = queryLatestScreenshot() ?: return
        val (path, dateAddedSec) = info

        val nowSec = System.currentTimeMillis() / 1000
        if (nowSec - dateAddedSec > FRESH_WINDOW_SEC) return // stale/edited row

        val now = SystemClock.elapsedRealtime()
        if (path == lastPath && now - lastHandled < DEBOUNCE_MS) return
        lastPath = path
        lastHandled = now

        scheduleOverlay(File(path))
    }

    private fun queryLatestScreenshot(): Pair<String, Long>? {
        val projection = arrayOf(
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.DATE_ADDED
        )
        val selection = "${MediaStore.Images.Media.DATA} LIKE ?"
        val args = arrayOf("%Screenshot%")
        val sort = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        return runCatching {
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection, selection, args, sort
            )?.use { c ->
                if (c.moveToFirst()) {
                    val data = c.getString(0)
                    val date = c.getLong(1)
                    if (!data.isNullOrEmpty()) return@use data to date
                }
                null
            }
        }.getOrNull()
    }

    private fun scheduleOverlay(file: File) {
        mainHandler.post {
            cancelPending()
            overlay.hide()
            val show = Runnable {
                overlay.show(prefs.overlayX, prefs.overlayY) { onTap(file) }
                val hide = Runnable { overlay.hide() }
                autoHideRunnable = hide
                mainHandler.postDelayed(hide, prefs.overlayDurationMs.toLong())
            }
            showRunnable = show
            mainHandler.postDelayed(show, prefs.overlayDelayMs.toLong())
        }
    }

    private fun onTap(file: File) {
        autoHideRunnable?.let { mainHandler.removeCallbacks(it) }
        overlay.hide()
        workerHandler.post {
            val ok = ScreenshotActions.copyToClipboard(applicationContext, file)
            ScreenshotActions.delete(applicationContext, file)
            toast(if (ok) R.string.toast_copied_deleted else R.string.toast_deleted)
        }
    }

    private fun cancelPending() {
        showRunnable?.let { mainHandler.removeCallbacks(it) }
        autoHideRunnable?.let { mainHandler.removeCallbacks(it) }
        showRunnable = null
        autoHideRunnable = null
    }

    private fun toast(resId: Int) {
        mainHandler.post { Toast.makeText(applicationContext, resId, Toast.LENGTH_SHORT).show() }
    }

    override fun onDestroy() {
        observer?.let { contentResolver.unregisterContentObserver(it) }
        observer = null
        cancelPending()
        overlay.hide()
        workerThread.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val DEBOUNCE_MS = 4000L
        private const val FRESH_WINDOW_SEC = 10L

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, OverlayService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }
}
