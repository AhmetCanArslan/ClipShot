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

    private var previewVisible = false
    private var absentStreak = 0
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
     * Re-evaluates whether the preview is on screen and shows/hides the button
     * on transitions only. Disappearance must hold across [ABSENT_CONFIRM]
     * consecutive checks: the accessibility node query flickers during the
     * preview's own exit animation, and reacting to a single flaky "absent"
     * would make the button blink (show/hide thrash) on the way out.
     */
    private fun evaluate() {
        main.removeCallbacks(pollRunnable)
        if (!prefs.overlayEnabled) {
            setVisible(false)
            return
        }
        if (isPreviewPresent()) {
            absentStreak = 0
            setVisible(true)
            main.postDelayed(pollRunnable, POLL_MS) // keep watching for disappearance
        } else if (previewVisible) {
            absentStreak++
            if (absentStreak >= ABSENT_CONFIRM) {
                setVisible(false)
            } else {
                main.postDelayed(pollRunnable, POLL_MS) // confirm it really went away
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
        main.removeCallbacks(pollRunnable)
        setVisible(false)
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
        private const val POLL_MS = 80L
        private const val ABSENT_CONFIRM = 2
    }
}
