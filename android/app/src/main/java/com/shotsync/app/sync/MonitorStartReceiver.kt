package com.shotsync.app.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.shotsync.app.ShotSyncApp
import com.shotsync.app.util.AppLogger
import kotlinx.coroutines.launch

/**
 * Restarts the monitoring service after events that kill the process:
 * device reboot (BOOT_COMPLETED) and app update (MY_PACKAGE_REPLACED).
 * Both broadcasts temporarily allowlist the app for foreground-service
 * start, so this must run within that short window — see
 * docs/android-platform-notes.md.
 */
class MonitorStartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as ShotSyncApp
        val pending = goAsync()
        app.appScope.launch {
            try {
                if (app.prefs.current().monitoringEnabled) {
                    MonitorService.start(app)
                    AppLogger.i("Monitor", "restarted after ${intent.action}")
                }
            } catch (e: Exception) {
                AppLogger.w("Monitor", "restart after ${intent.action} failed: ${e.message}")
            } finally {
                pending.finish()
            }
        }
    }
}
