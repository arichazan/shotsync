package com.shotsync.app.sync

import com.shotsync.app.data.db.QueueItem
import com.shotsync.app.data.db.QueueStatus

/**
 * Pure queue state transitions (unit-tested). Invariants:
 * - entries are never deleted on transient failure;
 * - COMPLETED only after the receiver acknowledges the content hash;
 * - attempt count increments once per failed attempt.
 */
object QueueTransitions {

    fun markUploading(item: QueueItem, now: Long): QueueItem =
        item.copy(
            status = QueueStatus.UPLOADING,
            uploadStartedAt = now,
            lastError = null,
        )

    fun markCompleted(item: QueueItem, now: Long): QueueItem =
        item.copy(
            status = QueueStatus.COMPLETED,
            completedAt = now,
            lastAttemptAt = now,
            nextAttemptAt = null,
            lastError = null,
        )

    /** Transient failure: keep the item, schedule a backoff retry. */
    fun markRetryWait(item: QueueItem, now: Long, delayMs: Long): QueueItem =
        item.copy(
            status = QueueStatus.RETRY_WAIT,
            attemptCount = item.attemptCount + 1,
            lastAttemptAt = now,
            nextAttemptAt = now + delayMs,
        )

    /** Permanent failure (auth revoked, hash mismatch, unsupported type…). */
    fun markFailed(item: QueueItem, now: Long, error: String): QueueItem =
        item.copy(
            status = QueueStatus.FAILED,
            attemptCount = item.attemptCount + 1,
            lastAttemptAt = now,
            nextAttemptAt = null,
            lastError = error,
        )

    /** Manual retry of a FAILED item. */
    fun resetForRetry(item: QueueItem): QueueItem =
        item.copy(
            status = QueueStatus.READY,
            lastError = null,
            nextAttemptAt = null,
            attemptCount = 0,
        )

    /** Recovery after process death mid-upload. */
    fun recoverFromCrash(item: QueueItem): QueueItem = when (item.status) {
        QueueStatus.UPLOADING, QueueStatus.DISCOVERED ->
            item.copy(
                status = QueueStatus.READY,
                nextAttemptAt = null,
                lastError = "recovered after process termination",
            )
        else -> item
    }

    /** Returns true when the item may be uploaded now. */
    fun isUploadable(item: QueueItem, now: Long): Boolean =
        item.status == QueueStatus.READY ||
            (item.status == QueueStatus.RETRY_WAIT &&
                (item.nextAttemptAt == null || item.nextAttemptAt <= now))
}
