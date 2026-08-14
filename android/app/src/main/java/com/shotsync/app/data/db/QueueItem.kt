package com.shotsync.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Upload queue states, per protocol doc section 4.5. */
enum class QueueStatus {
    DISCOVERED, // found by observer/scan, not yet verified
    READY,      // hash computed, eligible for upload
    UPLOADING,  // upload in flight
    COMPLETED,  // acknowledged by receiver
    RETRY_WAIT, // failed transiently, retry scheduled
    FAILED      // permanently rejected (or manual retry needed)
}

/**
 * One screenshot queued for transfer. Keeps every field required by the
 * protocol doc section 4.2 plus latency diagnostics timestamps.
 */
@Entity(
    tableName = "queue_items",
    indices = [
        Index(value = ["mediaStoreId"], unique = true),
        Index(value = ["status"]),
        Index(value = ["createdAt"]),
        Index(value = ["nextAttemptAt"]),
    ]
)
data class QueueItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fileId: String,
    val mediaStoreId: String,
    val contentUri: String,
    val displayName: String,
    val relativePath: String,
    val mimeType: String,
    val size: Long,
    val dateAddedSec: Long,
    val sha256: String?,
    val status: QueueStatus,
    val attemptCount: Int,
    val lastAttemptAt: Long?,
    val nextAttemptAt: Long?,
    val createdAt: Long,
    val capturedAt: String?,
    val lastError: String?,
    val detectedAt: Long?,
    val uploadStartedAt: Long?,
    val completedAt: Long?,
) {
    /** Latency from detection to receiver acknowledgement, in ms. */
    fun totalLatencyMs(): Long? {
        val start = detectedAt ?: return null
        val end = completedAt ?: return null
        return end - start
    }
}
