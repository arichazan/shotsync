package com.shotsync.app.sync

import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * Backoff for failed uploads (unit-testable).
 * attempts² × 15s (15s, 60s, 135s, 240s, …), capped at 10 minutes, plus up
 * to 5s of jitter (never past the cap).
 */
object RetryPolicy {

    const val BASE_DELAY_MS = 15_000L
    const val MAX_DELAY_MS = 10 * 60_000L
    const val JITTER_MS = 5_000L

    /** Beyond this many attempts the delay is always the cap. */
    private const val MAX_EFFECTIVE_ATTEMPTS = 64

    /** Backoff delay before retrying an item with [attemptCount] prior attempts. */
    fun delayForAttempt(attemptCount: Int, random: Random = Random.Default): Long {
        // Clamp first: attempts² × base would overflow Long for huge counts.
        val n = attemptCount.coerceIn(0, MAX_EFFECTIVE_ATTEMPTS)
        val exp = min(n.toDouble().pow(2.0).toLong() * BASE_DELAY_MS, MAX_DELAY_MS)
        val base = if (n <= 1) BASE_DELAY_MS else exp
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
