package com.shotsync.app.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.shotsync.app.ShotSyncApp
import com.shotsync.app.util.AppLogger
import kotlinx.coroutines.CancellationException

/** Retries queued uploads from the background (WorkManager recovery). */
class UploadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val engine = ShotSyncApp.from(applicationContext).engine
        return try {
            engine.recoverIncomplete()
            engine.triggerSync("worker")
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w("UploadWorker", "failed: ${e.message}")
            Result.retry()
        }
    }
}

/** Periodic reconciliation: finds screenshots the live observer missed. */
class ReconcileWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val engine = ShotSyncApp.from(applicationContext).engine
        return try {
            engine.reconciliationScan()
            engine.cleanupHistory()
            engine.triggerSync("reconcile")
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w("ReconcileWorker", "failed: ${e.message}")
            Result.retry()
        }
    }
}
