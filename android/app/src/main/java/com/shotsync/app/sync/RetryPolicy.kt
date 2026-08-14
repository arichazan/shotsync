package com.shotsync.app.sync

import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * Exponential backoff for failed uploads (unit-testable).
 * Base 15s, doubling per attempt, capped at 10 minutes, with jitter.
 */
object RetryPolicy {

    const val BASE_DELAY_MS = 15_000L
    const val MAX_DELAY_MS = 10 * 60_000L
    const val JITTER_MS = 5_000L

    /** Backoff delay before retrying an item with [attemptCount] prior attempts. */
    fun delayForAttempt(attemptCount: Int, random: Random = Random.Default): Long {
        val exp = min(attemptCount.toDouble().pow(2.0).toLong() * BASE_DELAY_MS, MAX_DELAY_MS)
        val base = if (attemptCount <= 1) BASE_DELAY_MS else exp
        return (base + random.nextLong(JITTER_MS)).coerceAtMost(MAX_DELAY_MS)
    }

    /**
     * HTTP statuses that must not be retried automatically:
     * 401/403 revoked credentials, 400 bad request/hash mismatch,
     * 413 too large, 415 unsupported type.
     */
    fun isPermanent(statusCode: Int): Boolean =
        statusCode in setOf(400, 401, 403, 413, 415)
}
