package com.shotsync.app

import android.app.Application
import com.shotsync.app.data.Prefs
import com.shotsync.app.data.db.AppDatabase
import com.shotsync.app.media.MediaScanner
import com.shotsync.app.sync.Discovery
import com.shotsync.app.sync.SyncEngine
import com.shotsync.app.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Application entry point and tiny service locator. */
class ShotSyncApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var prefs: Prefs
        private set
    lateinit var engine: SyncEngine
        private set
    lateinit var discovery: Discovery
        private set

    override fun onCreate() {
        super.onCreate()
        AppLogger.init(this)
        AppLogger.i("App", "starting")

        prefs = Prefs(this)
        val dao = AppDatabase.get(this).queueDao()
        val scanner = MediaScanner(this)
        discovery = Discovery(this)
        engine = SyncEngine(this, prefs, dao, scanner, discovery, appScope)

        // Recovery: reconcile missed screenshots and reschedule retries after
        // any process start (includes device reboot via WorkManager, which
        // also re-enqueues the periodic work). Failures are logged, never
        // fatal: a MediaStore hiccup must not take the app down.
        appScope.launch {
            engine.resetRetryChain()
            runCatching { engine.recoverIncomplete() }
                .onFailure { AppLogger.w("App", "recoverIncomplete: ${it.message}") }
            runCatching { engine.scanAndEnqueue("app-start") }
                .onFailure { AppLogger.w("App", "scan at start: ${it.message}") }
            runCatching { engine.schedulePeriodicReconciliation() }
                .onFailure { AppLogger.w("App", "schedule reconcile: ${it.message}") }
            runCatching { engine.cleanupHistory() }
                .onFailure { AppLogger.w("App", "cleanup: ${it.message}") }
            runCatching { engine.triggerSync("app-start") }
                .onFailure { AppLogger.w("App", "sync at start: ${it.message}") }
        }

        // Start mDNS discovery when paired.
        appScope.launch {
            prefs.settings.collect { s ->
                if (s.paired) {
                    discovery.start(s.macDeviceId)
                } else {
                    discovery.stop()
                }
            }
        }
    }

    companion object {
        fun from(context: android.content.Context): ShotSyncApp =
            context.applicationContext as ShotSyncApp
    }
}
