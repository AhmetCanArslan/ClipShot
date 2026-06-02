package com.arslan.clipshot

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager

/**
 * Thin wrapper around [WindowManager] that shows/removes the single floating
 * copy-and-delete button. All calls must happen on the main thread.
 */
class ScreenshotOverlay(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: View? = null

    fun isShowing(): Boolean = view != null

    /**
     * Shows the button with its left edge [xDp] from the left and its bottom
     * edge [yDp] from the bottom. [onTap] fires when the button is tapped.
     */
    fun show(xDp: Int, yDp: Int, onTap: () -> Unit) {
        if (view != null) return
        val v = LayoutInflater.from(context).inflate(R.layout.overlay_button, null)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.BOTTOM
            x = dpToPx(xDp)
            y = dpToPx(yDp)
        }

        v.setOnClickListener { onTap() }
        runCatching { wm.addView(v, params) }
            .onSuccess { view = v }
    }

    fun hide() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }

    private fun dpToPx(dp: Int): Int =
        (dp * context.resources.displayMetrics.density).toInt()
}
