package com.shotsync.app.sync

import com.shotsync.app.data.db.QueueItem
import com.shotsync.app.data.db.QueueStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueTransitionsTest {

    private val base = QueueItem(
        id = 1,
        fileId = "f-1",
        mediaStoreId = "42",
        contentUri = "content://media/42",
        displayName = "Screenshot_1.png",
        relativePath = "Pictures/Screenshots",
        mimeType = "image/png",
        size = 100,
        dateAddedSec = 1,
        sha256 = "abc",
        status = QueueStatus.READY,
        attemptCount = 0,
        lastAttemptAt = null,
        nextAttemptAt = null,
        createdAt = 0,
        capturedAt = null,
        lastError = null,
        detectedAt = 100,
        uploadStartedAt = null,
        completedAt = null,
    )

    @Test
    fun `ready to uploading`() {
        val up = QueueTransitions.markUploading(base, 1000)
        assertEquals(QueueStatus.UPLOADING, up.status)
        assertEquals(1000L, up.uploadStartedAt)
        assertNull(up.lastError)
    }

    @Test
    fun `completed only after acknowledgement`() {
        val completed = QueueTransitions.markCompleted(base, 2000)
        assertEquals(QueueStatus.COMPLETED, completed.status)
        assertEquals(2000L, completed.completedAt)
        assertNull(completed.nextAttemptAt)
    }

    @Test
    fun `transient failure keeps the entry and schedules retry`() {
        val retry = QueueTransitions.markRetryWait(base, 2000, 15_000)
        assertEquals(QueueStatus.RETRY_WAIT, retry.status)
        assertEquals(1, retry.attemptCount)
        assertEquals(2000L + 15_000L, retry.nextAttemptAt)
        assertEquals(base.id, retry.id) // entry is never removed
    }

    @Test
    fun `permanent failure marks failed with error`() {
        val failed = QueueTransitions.markFailed(base, 2000, "hash_mismatch")
        assertEquals(QueueStatus.FAILED, failed.status)
        assertEquals("hash_mismatch", failed.lastError)
        assertNull(failed.nextAttemptAt)
    }

    @Test
    fun `manual retry resets to ready`() {
        val failed = QueueTransitions.markFailed(base, 2000, "boom")
        val reset = QueueTransitions.resetForRetry(failed)
        assertEquals(QueueStatus.READY, reset.status)
        assertNull(reset.lastError)
        assertEquals(0, reset.attemptCount)
    }

    @Test
    fun `crash recovery re-queues interrupted uploads`() {
        val uploading = QueueTransitions.markUploading(base, 1000)
        val recovered = QueueTransitions.recoverFromCrash(uploading)
        assertEquals(QueueStatus.READY, recovered.status)
        assertNull(recovered.nextAttemptAt)
    }

    @Test
    fun `retry wait items become uploadable after backoff expires`() {
        val retry = QueueTransitions.markRetryWait(base, 0, 5000)
        assertFalse(QueueTransitions.isUploadable(retry, 1000))
        assertTrue(QueueTransitions.isUploadable(retry, 6000))
        assertTrue(QueueTransitions.isUploadable(base, 0))
    }
}
