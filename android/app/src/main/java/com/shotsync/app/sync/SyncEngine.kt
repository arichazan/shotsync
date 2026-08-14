package com.shotsync.app.sync

import android.content.Context
import android.net.Uri
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.shotsync.app.data.AppSettings
import com.shotsync.app.data.Prefs
import com.shotsync.app.data.db.QueueDao
import com.shotsync.app.data.db.QueueItem
import com.shotsync.app.data.db.QueueStatus
import com.shotsync.app.media.MediaScanner
import com.shotsync.app.media.Reconciliation
import com.shotsync.app.media.ScannedMedia
import com.shotsync.app.util.AppLogger
import com.shotsync.app.util.SafeFilename
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class EngineState(
    val monitoringEnabled: Boolean = false,
    val paired: Boolean = false,
    val macName: String? = null,
    val connection: ConnectionMethod? = null,
    val online: Boolean = false,
    val lastSuccessAt: Long? = null,
    val queuedCount: Int = 0,
    val uploading: Boolean = false,
    val lastError: String? = null,
    val recentTransfers: List<QueueItem> = emptyList(),
)

/**
 * Orchestrates detection -> queue -> upload -> acknowledgement, with the
 * endpoint selection order: local (mDNS) -> Tailscale -> offline queue.
 */
class SyncEngine(
    private val context: Context,
    private val prefs: Prefs,
    private val dao: QueueDao,
    private val scanner: MediaScanner,
    private val discovery: Discovery,
    private val scope: CoroutineScope,
) {

    private val uploadMutex = Mutex()

    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()

    @Volatile
    private var settingsSnapshot: AppSettings = AppSettings()

    @Volatile
    var networkOnline: Boolean = false
        private set

    private val workManager = WorkManager.getInstance(context)

    init {
        scope.launch {
            prefs.settings.collect { s ->
                settingsSnapshot = s
                updateState()
            }
        }
        scope.launch {
            dao.recent(50).collect { recent ->
                _state.value = _state.value.copy(recentTransfers = recent)
            }
        }
    }

    private suspend fun updateState() {
        val s = settingsSnapshot
        val queued = dao.countByStatus(
            listOf(QueueStatus.DISCOVERED, QueueStatus.READY, QueueStatus.UPLOADING, QueueStatus.RETRY_WAIT)
        )
        _state.value = _state.value.copy(
            monitoringEnabled = s.monitoringEnabled,
            paired = s.paired,
            macName = s.macDeviceName.ifBlank { null },
            lastSuccessAt = s.lastSuccessfulTransferAt.takeIf { it > 0 },
            queuedCount = queued,
        )
    }

    fun setOnline(online: Boolean) {
        networkOnline = online
        updateConnectionState()
        if (online) {
            scope.launch { triggerSync("network-available") }
        }
    }

    private fun updateConnectionState() {
        _state.value = _state.value.copy(online = networkOnline)
    }

    // --- endpoint resolution ---

    /**
     * Returns the best endpoint per the PRD order: discovered local Mac,
     * configured Tailscale, otherwise null (offline queue).
     */
    private fun resolveEndpoint(): ResolvedEndpoint? {
        val s = settingsSnapshot
        if (!s.paired) return null
        val discovered = discovery.macs.value
            .filter { it.deviceId == s.macDeviceId || s.macDeviceId.isBlank() }
            .firstOrNull()
        if (s.preferLocal && discovered != null) {
            return ResolvedEndpoint(
                Endpoint(discovered.host, discovered.port, ConnectionMethod.LOCAL),
                ConnectionMethod.LOCAL,
            )
        }
        if (s.tailscaleHost.isNotBlank()) {
            return ResolvedEndpoint(
                Endpoint(s.tailscaleHost, s.port, ConnectionMethod.TAILSCALE),
                ConnectionMethod.TAILSCALE,
            )
        }
        if (discovered != null) {
            return ResolvedEndpoint(
                Endpoint(discovered.host, discovered.port, ConnectionMethod.LOCAL),
                ConnectionMethod.LOCAL,
            )
        }
        return null
    }

    /** Probes health and updates the displayed connection method. */
    suspend fun probeConnection(): ConnectionMethod? {
        val resolved = resolveEndpoint()
        val method = if (resolved == null) {
            null
        } else {
            val client = apiFor(resolved.endpoint)
            if (client.isReachable(resolved.endpoint)) resolved.via else null
        }
        _state.value = _state.value.copy(connection = method, online = networkOnline)
        return method
    }

    private fun apiFor(endpoint: Endpoint): ApiClient =
        ApiClient(settingsSnapshot, token = null)

    private fun apiWithToken(endpoint: Endpoint, token: String): ApiClient =
        ApiClient(settingsSnapshot, token)

    // --- detection / enqueue ---

    /**
     * Runs scans (images and/or videos, per settings) since the stored
     * watermarks and enqueues new media. Called by the ContentObservers, app
     * start, monitor start and the reconciliation worker. Duplicate
     * notifications are harmless: the unique mediaStoreId index rejects
     * re-insertion.
     */
    suspend fun scanAndEnqueue(reason: String): Int {
        settingsSnapshot = prefs.current() // freshest watermarks/toggles
        val s = settingsSnapshot
        var added = 0
        if (s.syncScreenshots) {
            val scanned = scanner.scanImagesSince(s.watermarkSec, s.extraFolders)
            if (scanned.isNotEmpty()) {
                val known = dao.allMediaStoreIds().toHashSet()
                val fresh = Reconciliation.diff(scanned, known)
                for (image in fresh) {
                    if (enqueue(image, hashNow = true) != null) added++
                }
                val max = scanned.maxOf { it.dateAddedSec }
                if (max > s.watermarkSec) prefs.setWatermark(max)
                AppLogger.i("Sync", "scan($reason): ${scanned.size} screenshots scanned, ${fresh.size} new")
            }
        }
        if (s.syncRecordings) {
            val scanned = scanner.scanVideosSince(s.watermarkVideoSec, s.recordingFolders)
            if (scanned.isNotEmpty()) {
                val known = dao.allMediaStoreIds().toHashSet()
                val fresh = Reconciliation.diff(scanned, known)
                for (video in fresh) {
                    if (enqueue(video, hashNow = true) != null) added++
                }
                val max = scanned.maxOf { it.dateAddedSec }
                if (max > s.watermarkVideoSec) prefs.setVideoWatermark(max)
                AppLogger.i("Sync", "scan($reason): ${scanned.size} recordings scanned, ${fresh.size} new")
            }
        }
        if (added > 0) scope.launch { triggerSync("new-media") }
        return added
    }

    /**
     * Inserts one scanned media item into the queue as DISCOVERED, verifies
     * completion and computes its hash, then moves it to READY.
     */
    private suspend fun enqueue(media: ScannedMedia, hashNow: Boolean): Long? {
        if (!scanner.waitForCompletion(media.mediaStoreId)) {
            // Still being written; the next scan pass will pick it up.
            return null
        }
        val item = QueueItem(
            fileId = UUID.randomUUID().toString(),
            mediaStoreId = media.mediaStoreId,
            contentUri = media.contentUri,
            displayName = media.displayName,
            relativePath = media.relativePath,
            mimeType = media.mimeType,
            size = media.size,
            dateAddedSec = media.dateAddedSec,
            sha256 = null,
            status = QueueStatus.DISCOVERED,
            attemptCount = 0,
            lastAttemptAt = null,
            nextAttemptAt = null,
            createdAt = System.currentTimeMillis(),
            capturedAt = null,
            lastError = null,
            detectedAt = System.currentTimeMillis(),
            uploadStartedAt = null,
            completedAt = null,
        )
        val id = dao.insertIgnore(item)
        if (id <= 0) return null // duplicate MediaStore notification

        if (hashNow) {
            val uri = Uri.parse(media.contentUri)
            val hash = scanner.computeSha256(uri)
            if (hash == null) {
                dao.bumpAttempt(id, QueueStatus.RETRY_WAIT, System.currentTimeMillis())
                markRetryDelay(id, 1)
                AppLogger.w("Sync", "hash failed for ${media.displayName}")
                return id
            }
            val stored = dao.findById(id) ?: return id
            dao.update(
                stored.copy(
                    sha256 = hash,
                    status = QueueStatus.READY,
                    capturedAt = scanner.formatIso8601(media.dateTakenMs),
                )
            )
        }
        val kind = if (media.isVideo) "recording" else "screenshot"
        AppLogger.i(
            "Sync",
            "detected $kind ${media.displayName} (${media.size} bytes) at ${System.currentTimeMillis()}"
        )
        return id
    }

    // --- upload loop ---

    /** Single-flight queue processing: oldest items first. */
    suspend fun triggerSync(reason: String): Int = uploadMutex.withLock {
        val s = settingsSnapshot
        if (!s.paired) return@withLock 0
        if (s.wifiOnly && !isWifiConnected()) {
            _state.value = _state.value.copy(lastError = "waiting for Wi-Fi")
            return@withLock 0
        }
        val resolved = resolveEndpoint()
        if (resolved == null) {
            _state.value = _state.value.copy(connection = null, lastError = "receiver offline — queued")
            return@withLock 0
        }
        _state.value = _state.value.copy(connection = resolved.via)
        AppLogger.i("Sync", "sync started: $reason via ${resolved.via} (${resolved.endpoint.baseUrl})")

        val token = prefs.token() ?: run {
            _state.value = _state.value.copy(lastError = "no access token")
            return@withLock 0
        }
        val api = apiWithToken(resolved.endpoint, token)

        var uploaded = 0
        val now = System.currentTimeMillis()
        while (true) {
            val due = dao.nextUploads(
                statuses = listOf(QueueStatus.READY, QueueStatus.RETRY_WAIT),
                dueOnly = true,
                now = now,
                limit = 1,
            )
            val next = due.firstOrNull() ?: break
            if (next.status == QueueStatus.RETRY_WAIT && next.nextAttemptAt != null && next.nextAttemptAt > now) {
                break // wait for backoff
            }
            val result = uploadOne(api, resolved.endpoint, next)
            when (result) {
                is UploadOutcome.Success -> uploaded++
                is UploadOutcome.Deferred -> break
                is UploadOutcome.Permanent -> {
                    _state.value = _state.value.copy(lastError = result.message)
                    break
                }
                is UploadOutcome.NoNetwork -> break
            }
        }
        // Wake exactly one retry worker at the earliest due backoff time.
        val pending = dao.nextUploads(listOf(QueueStatus.RETRY_WAIT), false, now, 500)
        val nextDue = pending.minOfOrNull { it.nextAttemptAt ?: now }
        if (nextDue != null && nextDue > now) {
            scheduleRetryWorker(nextDue - now)
        }
        updateState()
        AppLogger.i("Sync", "sync complete: $uploaded uploaded")
        uploaded
    }

    private suspend fun isWifiConnected(): Boolean =
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return@runCatching false
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
        }.getOrDefault(false)

    sealed class UploadOutcome {
        data class Success(val filename: String) : UploadOutcome()
        data class Deferred(val retryInMs: Long) : UploadOutcome()
        data class Permanent(val message: String) : UploadOutcome()
        data object NoNetwork : UploadOutcome()
    }

    private suspend fun uploadOne(api: ApiClient, endpoint: Endpoint, item: QueueItem): UploadOutcome {
        _state.value = _state.value.copy(uploading = true)
        val startedAt = System.currentTimeMillis()
        dao.update(QueueTransitions.markUploading(item, startedAt))

        // A hash may be missing when the process died between discovery and
        // hashing. Recompute it now; only fail if the content is unreadable.
        val sha256 = item.sha256 ?: scanner.computeSha256(Uri.parse(item.contentUri))
        if (sha256 == null) {
            return failUpload(item, "content unavailable (deleted or unreadable)", permanent = true)
        }

        val descriptor = UploadDescriptor(
            fileId = item.fileId,
            mimeType = item.mimeType.ifBlank { "image/png" },
            size = item.size,
            sha256 = sha256,
            filename = SafeFilename.forHeader(item.displayName),
            capturedAtIso = item.capturedAt,
        )

        val result = api.upload(endpoint, descriptor) {
            context.contentResolver.openInputStream(Uri.parse(item.contentUri))
                ?: throw java.io.IOException("cannot open ${item.contentUri}")
        }
        val now = System.currentTimeMillis()
        return when (result) {
            is UploadResult.Stored -> {
                dao.update(QueueTransitions.markCompleted(item, now))
                prefs.setLastSuccessfulTransfer(now)
                logLatency(item, startedAt, now)
                AppLogger.i("Sync", "completed ${item.displayName} -> ${result.filename} in ${now - startedAt}ms")
                _state.value = _state.value.copy(uploading = false, lastError = null)
                UploadOutcome.Success(result.filename)
            }
            is UploadResult.AlreadyExists -> {
                dao.update(QueueTransitions.markCompleted(item, now))
                prefs.setLastSuccessfulTransfer(now)
                AppLogger.i("Sync", "deduplicated ${item.displayName} (already on Mac)")
                _state.value = _state.value.copy(uploading = false, lastError = null)
                UploadOutcome.Success(result.filename)
            }
            is UploadResult.Rejected -> {
                val permanent = RetryPolicy.isPermanent(result.httpStatus) ||
                    result.errorCode == "hash_mismatch" ||
                    result.errorCode == "unsupported_media"
                if (permanent) {
                    dao.update(
                        QueueTransitions.markFailed(
                            item, now, "${result.errorCode ?: "HTTP ${result.httpStatus}"}: ${result.message}"
                        )
                    )
                    _state.value = _state.value.copy(uploading = false, lastError = result.message)
                    UploadOutcome.Permanent(result.message ?: "rejected")
                } else {
                    scheduleRetry(item, now)
                    _state.value = _state.value.copy(uploading = false, lastError = result.message)
                    UploadOutcome.Deferred(retryDelayFor(item.attemptCount + 1))
                }
            }
            is UploadResult.NetworkError -> {
                scheduleRetry(item, now)
                _state.value = _state.value.copy(uploading = false, lastError = result.message)
                UploadOutcome.Deferred(retryDelayFor(item.attemptCount + 1))
            }
        }
    }

    private suspend fun failUpload(item: QueueItem, message: String, permanent: Boolean): UploadOutcome {
        dao.update(
            QueueTransitions.markFailed(item, System.currentTimeMillis(), message)
        )
        AppLogger.w("Sync", "upload failed for ${item.displayName}: $message")
        _state.value = _state.value.copy(uploading = false, lastError = message)
        return if (permanent) UploadOutcome.Permanent(message) else UploadOutcome.Deferred(0)
    }

    private fun retryDelayFor(attempts: Int): Long = RetryPolicy.delayForAttempt(attempts)

    private suspend fun scheduleRetry(item: QueueItem, now: Long) {
        val delay = RetryPolicy.delayForAttempt(item.attemptCount + 1)
        dao.update(QueueTransitions.markRetryWait(item, now, delay))
        AppLogger.i("Sync", "retry ${item.displayName} in ${delay / 1000}s (attempt ${item.attemptCount + 1})")
        // The worker is scheduled once per sync pass by triggerSync at the
        // earliest due time; do not enqueue here to avoid worker churn.
    }

    private suspend fun markRetryDelay(id: Long, attempts: Int) {
        val delay = RetryPolicy.delayForAttempt(attempts)
        dao.findById(id)?.let {
            dao.update(it.copy(nextAttemptAt = System.currentTimeMillis() + delay))
        }
    }

    private fun logLatency(item: QueueItem, startedAt: Long, now: Long) {
        val detected = item.detectedAt
        if (detected != null) {
            AppLogger.i(
                "Sync",
                "latency: detected=${detected} uploadStarted=$startedAt completed=$now total=${now - detected}ms"
            )
        }
    }

    // --- recovery / workers ---

    fun schedulePeriodicReconciliation() {
        val request = PeriodicWorkRequestBuilder<ReconcileWorker>(15, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniquePeriodicWork(
            "shotsync-reconcile",
            androidx.work.ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    private fun scheduleRetryWorker(delayMs: Long) {
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .build()
        // APPEND (not REPLACE): replacing would cancel a currently running
        // worker with this name, which causes a cancel/retry storm.
        workManager.enqueueUniqueWork("shotsync-retry", ExistingWorkPolicy.APPEND, request)
    }

    /** Full reconciliation: wide window scan + queue processing. */
    suspend fun reconciliationScan(): Int {
        settingsSnapshot = prefs.current()
        val s = settingsSnapshot
        val known = dao.allMediaStoreIds().toHashSet()
        val windowStart = (System.currentTimeMillis() / 1000) - s.scanWindowSec
        var added = 0
        if (s.syncScreenshots) {
            val since = minOf(s.watermarkSec.takeIf { it > 0 } ?: windowStart, windowStart)
            val scanned = scanner.scanImagesSince(since, s.extraFolders, maxResults = 2000)
            for (media in Reconciliation.diff(scanned, known)) {
                if (enqueue(media, hashNow = true) != null) added++
            }
        }
        if (s.syncRecordings) {
            val since = minOf(s.watermarkVideoSec.takeIf { it > 0 } ?: windowStart, windowStart)
            val scanned = scanner.scanVideosSince(since, s.recordingFolders, maxResults = 500)
            for (media in Reconciliation.diff(scanned, known)) {
                if (enqueue(media, hashNow = true) != null) added++
            }
        }
        if (added > 0) {
            AppLogger.i("Sync", "reconciliation: $added missed items recovered")
            scope.launch { triggerSync("reconciliation") }
        }
        return added
    }

    /** Re-processes incomplete items left behind by process death. */
    suspend fun recoverIncomplete() {
        val incomplete = dao.incomplete()
        for (item in incomplete) {
            when (item.status) {
                QueueStatus.UPLOADING, QueueStatus.DISCOVERED -> {
                    // Process was killed mid-upload or mid-discovery.
                    dao.update(QueueTransitions.recoverFromCrash(item))
                }
                QueueStatus.RETRY_WAIT -> {
                    // Make due immediately on app start when backoff expired.
                    if (item.nextAttemptAt == null || item.nextAttemptAt <= System.currentTimeMillis()) {
                        dao.update(item.copy(status = QueueStatus.READY, nextAttemptAt = null))
                    }
                }
                else -> Unit
            }
        }
    }

    /**
     * Clears the retry worker chain. Called once per process start (never
     * from inside a worker): a chain head sitting in WorkManager backoff
     * would otherwise delay every appended retry behind it. The startup
     * sync re-schedules a fresh chain immediately.
     */
    fun resetRetryChain() {
        workManager.cancelUniqueWork("shotsync-retry")
    }

    /** Retries a FAILED item manually. */
    suspend fun retryFailedItem(id: Long) {
        val item = dao.findById(id) ?: return
        dao.update(QueueTransitions.resetForRetry(item))
        triggerSync("manual-retry")
    }

    suspend fun retryAllFailed() {
        for (item in dao.failed()) {
            dao.update(QueueTransitions.resetForRetry(item))
        }
        triggerSync("manual-retry-all")
    }

    suspend fun deleteItem(id: Long) {
        dao.deleteById(id)
        updateState()
    }

    /** Retention cleanup per settings (default 30 days). */
    suspend fun cleanupHistory() {
        val days = settingsSnapshot.retentionDays
        val cutoff = System.currentTimeMillis() - days * 24L * 3600L * 1000L
        dao.deleteCompletedOlderThan(cutoff)
    }

    // --- pairing / test ---

    suspend fun pairWithQr(
        secret: String,
        macDeviceId: String,
        macName: String,
        fingerprint: String,
        localHost: String,
        port: Int,
        deviceDisplayName: String,
    ): Result<Unit> {
        val deviceId = prefs.ensureDeviceId()
        val endpoint = Endpoint(localHost, port, ConnectionMethod.LOCAL)
        val client = ApiClient(
            settingsSnapshot.copy(certificateFingerprint = fingerprint, deviceId = deviceId),
            token = null,
        )
        val result = client.pair(endpoint, secret, deviceId, deviceDisplayName.trim().ifBlank { "Android device" })
        return when (result) {
            is PairResult.Success -> {
                prefs.savePairing(macDeviceId, macName, fingerprint, localHost, port, result.token)
                AppLogger.i("Sync", "paired with $macName ($macDeviceId)")
                Result.success(Unit)
            }
            is PairResult.Failure -> Result.failure(RuntimeException(result.message))
        }
    }

    suspend fun unpair() {
        runCatching {
            val resolved = resolveEndpoint()
            if (resolved != null) {
                prefs.token()?.let { token ->
                    apiWithToken(resolved.endpoint, token).revokeSelf(resolved.endpoint)
                }
            }
        }
        prefs.unpair()
        updateState()
    }

    /** Sends a synthetic test image straight to the receiver (bypasses MediaStore). */
    suspend fun sendTestImage(): Result<String> {
        val resolved = resolveEndpoint()
            ?: return Result.failure(RuntimeException("receiver offline — queued"))
        val token = prefs.token() ?: return Result.failure(RuntimeException("no access token"))
        val file = TestImage.create(context)
        val descriptor = UploadDescriptor(
            fileId = UUID.randomUUID().toString(),
            mimeType = "image/png",
            size = file.length(),
            sha256 = TestImage.sha256Of(file),
            filename = "shotsync-test-${System.currentTimeMillis()}.png",
            capturedAtIso = null,
        )
        val api = apiWithToken(resolved.endpoint, token)
        return when (val result = api.upload(resolved.endpoint, descriptor) { file.inputStream() }) {
            is UploadResult.Stored -> Result.success("stored as ${result.filename}")
            is UploadResult.AlreadyExists -> Result.success("already on Mac")
            is UploadResult.Rejected -> Result.failure(RuntimeException(result.message ?: "HTTP ${result.httpStatus}"))
            is UploadResult.NetworkError -> Result.failure(RuntimeException(result.message))
        }
    }

    /** Pings health on the current endpoint for the UI. */
    suspend fun refreshConnectionStatus() {
        val resolved = resolveEndpoint()
        val method = when {
            resolved == null -> null
            apiFor(resolved.endpoint).isReachable(resolved.endpoint) -> resolved.via
            else -> null
        }
        _state.value = _state.value.copy(connection = method, online = networkOnline)
    }
}
