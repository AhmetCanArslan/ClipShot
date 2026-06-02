package com.arslan.clipshot

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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

    private var previewVisible = false
    /** Block re-showing until this time, to swallow the exit-animation flicker. */
    private var suppressShowUntil = 0L
    /** Block re-showing the current preview after the user acted on it (tap). */
    private var suppressUntilAbsent = false
    private val pollRunnable = Runnable { evaluate() }

    override fun onServiceConnected() {
        prefs = Prefs(this)
        overlay = ScreenshotOverlay(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!::prefs.isInitialized) return
        evaluate()
    }

    override fun onInterrupt() {}

    /**
     * Re-evaluates whether the preview is on screen. Hides immediately when the
     * preview goes (no debounce lag); the flash that a naive immediate-hide
     * would cause — the node query flickers back to "present" during the
     * preview's own exit animation and re-adds the button — is prevented by a
     * short [SUPPRESS_MS] window during which re-showing is blocked.
     */
    private fun evaluate() {
        main.removeCallbacks(pollRunnable)
        if (!prefs.overlayEnabled) {
            setVisible(false)
            suppressUntilAbsent = false
            return
        }
        if (isPreviewPresent()) {
            val blocked = suppressUntilAbsent || SystemClock.uptimeMillis() < suppressShowUntil
            if (!blocked) setVisible(true)
            // Keep watching while any preview is up, so we catch its disappearance.
            main.postDelayed(pollRunnable, POLL_MS)
        } else {
            suppressUntilAbsent = false
            if (previewVisible) {
                setVisible(false) // immediate
                suppressShowUntil = SystemClock.uptimeMillis() + SUPPRESS_MS
            }
        }
    }

    private fun setVisible(visible: Boolean) {
        if (visible == previewVisible) return
        previewVisible = visible
        if (visible) {
            overlay.show(prefs.overlayX, prefs.overlayY) { onTap() }
        } else {
            overlay.hide()
        }
    }

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

    private fun onTap() {
        // Hide now and don't let this still-present preview re-show the button.
        suppressUntilAbsent = true
        setVisible(false)
        main.removeCallbacks(pollRunnable)
        main.postDelayed(pollRunnable, POLL_MS) // keep watching to clear the block when it goes
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
        private const val POLL_MS = 60L
        private const val SUPPRESS_MS = 500L
    }
}
