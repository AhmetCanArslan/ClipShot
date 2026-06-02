package com.arslan.clipshot

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

/**
 * Detects the system screenshot preview (SystemUI's `ScreenshotAnimation`
 * window) and ties the floating copy-and-delete button to its lifecycle:
 * the button appears when the preview appears and is removed the moment the
 * preview is dismissed — whether it times out or the user swipes it away.
 *
 * Only acts while [Prefs.overlayEnabled] is set (Overlay mode).
 */
class ScreenshotAccessibilityService : AccessibilityService() {

    private lateinit var prefs: Prefs
    private lateinit var overlay: ScreenshotOverlay
    private val main = Handler(Looper.getMainLooper())
    private var shown = false

    /** Once shown, poll the preview's presence so we hide as soon as it goes. */
    private val poll = object : Runnable {
        override fun run() {
            if (!shown) return
            if (!prefs.overlayEnabled || !isPreviewPresent()) {
                hide()
            } else {
                main.postDelayed(this, POLL_MS)
            }
        }
    }

    override fun onServiceConnected() {
        prefs = Prefs(this)
        overlay = ScreenshotOverlay(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!::prefs.isInitialized) return
        if (!prefs.overlayEnabled) {
            if (shown) hide()
            return
        }
        // The appear trigger; dismissal is handled by [poll].
        if (!shown && isPreviewPresent()) show()
    }

    override fun onInterrupt() {}

    private fun isPreviewPresent(): Boolean {
        val ws = windows ?: return false
        for (w in ws) {
            val root = w.root ?: continue
            if (root.packageName?.toString() != SYSTEMUI) continue
            val nodes = root.findAccessibilityNodeInfosByViewId(PREVIEW_ID)
            if (!nodes.isNullOrEmpty()) return true
        }
        return false
    }

    private fun show() {
        shown = true
        main.post { overlay.show(prefs.overlayX, prefs.overlayY) { onTap() } }
        main.postDelayed(poll, POLL_MS)
    }

    private fun hide() {
        shown = false
        main.removeCallbacks(poll)
        main.post { overlay.hide() }
    }

    private fun onTap() {
        hide()
        val app = applicationContext
        Thread {
            val file = ScreenshotActions.latestScreenshot(app)
            val ok = file != null && ScreenshotActions.copyToClipboard(app, file)
            if (file != null) ScreenshotActions.delete(app, file)
            main.post {
                Toast.makeText(
                    app,
                    if (ok) R.string.toast_copied_deleted else R.string.toast_deleted,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }.start()
    }

    companion object {
        private const val SYSTEMUI = "com.android.systemui"
        private const val PREVIEW_ID = "com.android.systemui:id/screenshot_static"
        private const val POLL_MS = 250L
    }
}
