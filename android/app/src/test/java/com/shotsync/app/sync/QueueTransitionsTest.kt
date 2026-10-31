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

    @Test
    fun `uploading keeps attempt count and clears previous error`() {
        val retry = QueueTransitions.markRetryWait(base.copy(lastError = "timeout"), 1000, 15_000)
        val up = QueueTransitions.markUploading(retry, 20_000)
        assertEquals(1, up.attemptCount)
        assertNull(up.lastError)
        assertEquals(retry.nextAttemptAt, up.nextAttemptAt)
    }

    @Test
    fun `each failed attempt increments the attempt count once`() {
        var item = base
        for (i in 1..5) {
            item = QueueTransitions.markRetryWait(QueueTransitions.markUploading(item, i * 1000L), i * 1000L, 10)
            assertEquals(i, item.attemptCount)
        }
        val failed = QueueTransitions.markFailed(item, 9000, "unauthorized")
        assertEquals(6, failed.attemptCount)
        assertEquals(9000L, failed.lastAttemptAt)
    }

    @Test
    fun `completion clears pending retry state and error`() {
        val retry = QueueTransitions.markRetryWait(base.copy(lastError = "timeout"), 1000, 15_000)
        val done = QueueTransitions.markCompleted(QueueTransitions.markUploading(retry, 16_000), 17_000)
        assertEquals(QueueStatus.COMPLETED, done.status)
        assertNull(done.nextAttemptAt)
        assertNull(done.lastError)
        assertEquals(17_000L, done.lastAttemptAt)
        assertEquals(1, done.attemptCount)
        assertFalse(QueueTransitions.isUploadable(done, Long.MAX_VALUE))
    }

    @Test
    fun `full lifecycle with retry policy delays`() {
        val zeroJitter = object : kotlin.random.Random() {
            override fun nextBits(bitCount: Int): Int = 0
            override fun nextLong(until: Long): Long = 0
        }
        var now = 0L
        var item = QueueTransitions.markUploading(base, now)
        val delay = RetryPolicy.delayForAttempt(item.attemptCount + 1, zeroJitter)
        item = QueueTransitions.markRetryWait(item, now, delay)
        assertFalse(QueueTransitions.isUploadable(item, now + delay - 1))
        now += delay
        assertTrue(QueueTransitions.isUploadable(item, now))
        item = QueueTransitions.markCompleted(QueueTransitions.markUploading(item, now), now + 10)
        assertEquals(QueueStatus.COMPLETED, item.status)
        assertEquals(base.fileId, item.fileId)
        assertEquals(base.sha256, item.sha256)
    }

    @Test
    fun `crash recovery re-queues discovered items and leaves settled ones alone`() {
        val discovered = base.copy(status = QueueStatus.DISCOVERED)
        assertEquals(QueueStatus.READY, QueueTransitions.recoverFromCrash(discovered).status)
        for (status in listOf(QueueStatus.READY, QueueStatus.COMPLETED, QueueStatus.RETRY_WAIT, QueueStatus.FAILED)) {
            val item = base.copy(status = status, nextAttemptAt = 5, lastError = "x")
            assertEquals(item, QueueTransitions.recoverFromCrash(item))
        }
    }

    @Test
    fun `only ready and due retry items are uploadable`() {
        val now = 10_000L
        val expected = mapOf(
            QueueStatus.DISCOVERED to false,
            QueueStatus.READY to true,
            QueueStatus.UPLOADING to false,
            QueueStatus.COMPLETED to false,
            QueueStatus.FAILED to false,
        )
        for ((status, want) in expected) {
            assertEquals(status.name, want, QueueTransitions.isUploadable(base.copy(status = status), now))
        }
        val retry = base.copy(status = QueueStatus.RETRY_WAIT)
        assertTrue(QueueTransitions.isUploadable(retry.copy(nextAttemptAt = null), now))
        assertTrue(QueueTransitions.isUploadable(retry.copy(nextAttemptAt = now), now))
        assertFalse(QueueTransitions.isUploadable(retry.copy(nextAttemptAt = now + 1), now))
    }

    @Test
    fun `manual retry of failed item makes it uploadable immediately`() {
        val failed = QueueTransitions.markFailed(base, 2000, "too_large")
        assertFalse(QueueTransitions.isUploadable(failed, 3000))
        assertTrue(QueueTransitions.isUploadable(QueueTransitions.resetForRetry(failed), 3000))
    }
}
