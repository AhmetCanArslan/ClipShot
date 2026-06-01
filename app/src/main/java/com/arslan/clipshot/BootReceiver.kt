package com.arslan.clipshot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts the watcher service after reboot if it was enabled. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (Prefs(context).watcherEnabled) {
            ScreenshotService.start(context.applicationContext)
        }
    }
}
