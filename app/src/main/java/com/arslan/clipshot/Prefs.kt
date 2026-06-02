package com.arslan.clipshot

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * Thin wrapper over SharedPreferences holding the user's watcher settings.
 */
class Prefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var watchedPath: String
        get() = sp.getString(KEY_PATH, defaultScreenshotsPath()) ?: defaultScreenshotsPath()
        set(value) = sp.edit().putString(KEY_PATH, value).apply()

    /** Allowed values: 0, 1, 2, 3, 5, 10 (seconds). */
    var notificationDelaySeconds: Int
        get() = sp.getInt(KEY_DELAY, DEFAULT_DELAY)
        set(value) = sp.edit().putInt(KEY_DELAY, value).apply()

    var watcherEnabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, false)
        set(value) = sp.edit().putBoolean(KEY_ENABLED, value).apply()

    // ---- Overlay mode ----

    var overlayEnabled: Boolean
        get() = sp.getBoolean(KEY_OVERLAY_ENABLED, false)
        set(value) = sp.edit().putBoolean(KEY_OVERLAY_ENABLED, value).apply()

    /** Button left-edge offset from the left screen edge, in dp. */
    var overlayX: Int
        get() = sp.getInt(KEY_OVERLAY_X, DEFAULT_OVERLAY_X)
        set(value) = sp.edit().putInt(KEY_OVERLAY_X, value).apply()

    /** Button bottom-edge offset from the bottom screen edge, in dp. */
    var overlayY: Int
        get() = sp.getInt(KEY_OVERLAY_Y, DEFAULT_OVERLAY_Y)
        set(value) = sp.edit().putInt(KEY_OVERLAY_Y, value).apply()

    companion object {
        private const val NAME = "clipshot_prefs"
        private const val KEY_PATH = "watched_path"
        private const val KEY_DELAY = "notification_delay_seconds"
        private const val KEY_ENABLED = "watcher_enabled"
        private const val KEY_OVERLAY_ENABLED = "overlay_enabled"
        private const val KEY_OVERLAY_X = "overlay_x"
        private const val KEY_OVERLAY_Y = "overlay_y"

        const val DEFAULT_DELAY = 2
        val DELAY_OPTIONS = listOf(0, 1, 2, 3, 5, 10)

        // Defaults tuned for a Nothing Phone 2 in portrait: the system preview
        // pill's right edge sits at ~177dp, so clear it.
        const val DEFAULT_OVERLAY_X = 190
        const val DEFAULT_OVERLAY_Y = 54
        const val OVERLAY_POSITION_MAX = 400

        fun defaultScreenshotsPath(): String {
            val pictures = Environment
                .getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            return File(pictures, "Screenshots").absolutePath
        }
    }
}
