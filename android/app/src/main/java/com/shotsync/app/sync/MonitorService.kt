package com.shotsync.app.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.shotsync.app.MainActivity
import com.shotsync.app.R
import com.shotsync.app.ShotSyncApp
import com.shotsync.app.media.ScreenshotObserver
import com.shotsync.app.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground monitoring service. Runs the live MediaStore observer and
 * network-change recovery while the process is alive. Uses the specialUse
 * foreground-service type: a continuously-running dataSync service would hit
 * cumulative time limits on recent Android versions. See
 * docs/android-platform-notes.md.
 */
class MonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var imageObserver: ScreenshotObserver? = null
    private var videoObserver: ScreenshotObserver? = null
    private var networkMonitor: NetworkMonitor? = null
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        createChannels()
        // Declare the type explicitly (required semantics on Android 14+).
        // If the system refuses the start (e.g. background-start
        // restrictions), stop cleanly instead of crashing the process;
        // the periodic reconciliation worker keeps syncing meanwhile.
        try {
            ServiceCompat.startForeground(
                this,
                NOTIF_ID_MONITORING,
                buildMonitoringNotification(),
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
            )
            foregroundStarted = true
        } catch (e: Exception) {
            AppLogger.w("Monitor", "startForeground refused: ${e.message}")
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundStarted) return START_NOT_STICKY
        val app = ShotSyncApp.from(this)
        val engine = app.engine

        if (imageObserver == null) {
            imageObserver = ScreenshotObserver(
                uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                resolver = contentResolver,
                scope = scope,
            ) {
                engine.scanAndEnqueue("observer-images")
            }.also { it.start() }
        }
        if (videoObserver == null) {
            videoObserver = ScreenshotObserver(
                uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                resolver = contentResolver,
                scope = scope,
                debounceMs = 3000, // recordings finalize slower; debounce longer
            ) {
                engine.scanAndEnqueue("observer-videos")
            }.also { it.start() }
        }

        if (networkMonitor == null) {
            networkMonitor = NetworkMonitor(this) {
                engine.setOnline(true)
            }.also { it.start() }
            engine.setOnline(networkMonitor?.isOnline ?: true)
        }

        // Initial reconciliation on service start. Each step is guarded so a
        // MediaStore failure can never crash the service.
        scope.launch {
            runCatching { engine.recoverIncomplete() }
                .onFailure { AppLogger.w("Monitor", "recover: ${it.message}") }
            runCatching { engine.scanAndEnqueue("monitor-start") }
                .onFailure { AppLogger.w("Monitor", "scan: ${it.message}") }
            runCatching { engine.triggerSync("monitor-start") }
                .onFailure { AppLogger.w("Monitor", "sync: ${it.message}") }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        imageObserver?.stop()
        videoObserver?.stop()
        networkMonitor?.stop()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannels() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MONITORING,
                getString(R.string.notif_channel_monitoring),
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = getString(R.string.notif_channel_monitoring_desc) }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_TRANSFERS,
                getString(R.string.notif_channel_transfers),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = getString(R.string.notif_channel_transfers_desc) }
        )
    }

    private fun buildMonitoringNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_MONITORING)
            .setSmallIcon(R.drawable.ic_stat_monitor)
            .setContentTitle(getString(R.string.notif_monitoring_title))
            .setContentText(getString(R.string.notif_monitoring_text))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val CHANNEL_MONITORING = "shotsync.monitoring"
        const val CHANNEL_TRANSFERS = "shotsync.transfers"
        const val NOTIF_ID_MONITORING = 1

        fun start(context: Context) {
            val intent = Intent(context, MonitorService::class.java)
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                // ForegroundServiceStartNotAllowedException (API 31+) when
                // called outside an allowed window. Monitoring resumes the
                // next time the app is opened.
                AppLogger.w("Monitor", "foreground start not allowed: ${e.message}")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MonitorService::class.java))
        }
    }
}
