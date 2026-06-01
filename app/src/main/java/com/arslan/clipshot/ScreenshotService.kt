package com.arslan.clipshot

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.FileObserver
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Foreground service that watches the configured folder with a [FileObserver]
 * and posts a preview notification (after the user-set delay) for each new
 * screenshot.
 */
class ScreenshotService : Service() {

    private lateinit var prefs: Prefs
    private lateinit var workerThread: HandlerThread
    private lateinit var handler: Handler

    private var observer: FileObserver? = null

    /** Debounce: filename -> last handled time (elapsedRealtime ms). */
    private val recent = ConcurrentHashMap<String, Long>()

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        ScreenshotNotifier.ensureChannels(this)
        workerThread = HandlerThread("clipshot-observer").apply { start() }
        handler = Handler(workerThread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        restartObserver()
        return START_STICKY
    }

    private fun startInForeground() {
        val notification = ScreenshotNotifier.buildWatcherNotification(this)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            ScreenshotNotifier.WATCHER_NOTIFICATION_ID,
            notification,
            type
        )
    }

    private fun restartObserver() {
        observer?.stopWatching()
        val dir = File(prefs.watchedPath)
        if (!dir.exists()) dir.mkdirs()
        observer = createObserver(dir).also { it.startWatching() }
    }

    private fun createObserver(dir: File): FileObserver {
        val mask = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or FileObserver.CREATE
        val onEvent: (Int, String?) -> Unit = handler@{ _, name ->
            if (name == null) return@handler
            if (!isImage(name)) return@handler
            val now = SystemClock.elapsedRealtime()
            val last = recent[name]
            if (last != null && now - last < DEBOUNCE_MS) return@handler
            recent[name] = now
            scheduleNotification(File(dir, name))
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            object : FileObserver(dir, mask) {
                override fun onEvent(event: Int, path: String?) = onEvent(event, path)
            }
        } else {
            @Suppress("DEPRECATION")
            object : FileObserver(dir.absolutePath, mask) {
                override fun onEvent(event: Int, path: String?) = onEvent(event, path)
            }
        }
    }

    private fun scheduleNotification(file: File) {
        val delayMs = prefs.notificationDelaySeconds * 1000L
        handler.postDelayed({
            if (file.exists() && file.length() > 0) {
                ScreenshotNotifier.notifyScreenshot(applicationContext, file)
            }
        }, delayMs)
    }

    override fun onDestroy() {
        observer?.stopWatching()
        observer = null
        workerThread.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val DEBOUNCE_MS = 3000L
        private val IMAGE_EXTS = listOf(".png", ".jpg", ".jpeg", ".webp")

        private fun isImage(name: String): Boolean {
            val lower = name.lowercase()
            // Ignore in-progress files (e.g. ".pending-123-...").
            if (lower.startsWith(".") || lower.contains(".pending")) return false
            return IMAGE_EXTS.any { lower.endsWith(it) }
        }

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ScreenshotService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ScreenshotService::class.java))
        }
    }
}
