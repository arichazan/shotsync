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
}
