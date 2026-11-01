package com.shotsync.app.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RetryPolicyTest {

    @Test
    fun `backoff grows exponentially and is capped`() {
        val d1 = RetryPolicy.delayForAttempt(1, Random(42))
        val d2 = RetryPolicy.delayForAttempt(2, Random(42))
        val d3 = RetryPolicy.delayForAttempt(3, Random(42))
        val d4 = RetryPolicy.delayForAttempt(4, Random(42))
        assertTrue(d1 <= d2)
        assertTrue(d2 <= d3)
        assertTrue(d3 <= d4)
    }

    @Test
    fun `delay never exceeds cap`() {
        for (attempt in 1..30) {
            assertTrue(
                "attempt $attempt exceeds cap",
                RetryPolicy.delayForAttempt(attempt, Random(1)) <= RetryPolicy.MAX_DELAY_MS
            )
        }
    }

    @Test
    fun `permanent statuses are not retried`() {
        for (code in listOf(400, 401, 403, 413, 415)) {
            assertTrue("$code should be permanent", RetryPolicy.isPermanent(code))
        }
        for (code in listOf(408, 425, 429, 500, 502, 503, 504)) {
            assertFalse("$code should be retryable", RetryPolicy.isPermanent(code))
        }
    }

    /** Random whose nextLong(bound) always returns [value] (clamped to the bound). */
    private class FixedRandom(private val value: Long) : Random() {
        override fun nextBits(bitCount: Int): Int = 0
        override fun nextLong(until: Long): Long = value.coerceIn(0, until - 1)
    }

    @Test
    fun `schedule follows attempts squared times base without jitter`() {
        val zero = FixedRandom(0)
        val expected = mapOf(
            0 to 15_000L, 1 to 15_000L, 2 to 60_000L, 3 to 135_000L,
            4 to 240_000L, 5 to 375_000L, 6 to 540_000L, 7 to 600_000L, 50 to 600_000L,
        )
        for ((attempt, delay) in expected) {
            assertEquals("attempt $attempt", delay, RetryPolicy.delayForAttempt(attempt, zero))
        }
    }

    @Test
    fun `jitter adds less than JITTER_MS and never passes the cap`() {
        val max = FixedRandom(Long.MAX_VALUE)
        assertEquals(15_000L + RetryPolicy.JITTER_MS - 1, RetryPolicy.delayForAttempt(1, max))
        assertEquals(60_000L + RetryPolicy.JITTER_MS - 1, RetryPolicy.delayForAttempt(2, max))
        assertEquals(RetryPolicy.MAX_DELAY_MS, RetryPolicy.delayForAttempt(7, max))
        val rnd = Random(7)
        repeat(1000) {
            val d = RetryPolicy.delayForAttempt(1, rnd)
            assertTrue(d in RetryPolicy.BASE_DELAY_MS until RetryPolicy.BASE_DELAY_MS + RetryPolicy.JITTER_MS)
        }
    }

    @Test
    fun `extreme attempt counts never overflow or go below base`() {
        for (attempt in listOf(-5, -1, 10_000, 1_000_000, 50_000_000, Int.MAX_VALUE)) {
            val d = RetryPolicy.delayForAttempt(attempt, Random(3))
            assertTrue("attempt $attempt gave $d", d >= RetryPolicy.BASE_DELAY_MS)
            assertTrue("attempt $attempt gave $d", d <= RetryPolicy.MAX_DELAY_MS)
        }
    }

    @Test
    fun `success and not-found style codes are not permanent`() {
        for (code in listOf(200, 201, 404, 409)) {
            assertFalse("$code", RetryPolicy.isPermanent(code))
        }
    }
}
