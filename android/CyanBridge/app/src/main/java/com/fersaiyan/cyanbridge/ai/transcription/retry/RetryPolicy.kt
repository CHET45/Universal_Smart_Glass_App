package com.fersaiyan.cyanbridge.ai.transcription.retry

import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.random.Random

/** Lightweight retry policy for POC (Chapter 6). */
data class RetryPolicy(
    val maxAttempts: Int = 3,
    val initialBackoffMs: Long = 400,
    val maxBackoffMs: Long = 5_000,
    val jitterRatio: Double = 0.2,
) {
    init {
        require(maxAttempts >= 1)
        require(initialBackoffMs >= 0)
        require(maxBackoffMs >= initialBackoffMs)
        require(jitterRatio >= 0.0)
    }

    suspend fun <T> execute(
        isRetryable: (Throwable) -> Boolean,
        block: suspend (attempt: Int) -> T,
    ): T {
        var attempt = 1
        var backoff = initialBackoffMs
        var lastErr: Throwable? = null

        while (attempt <= maxAttempts) {
            try {
                return block(attempt)
            } catch (t: Throwable) {
                lastErr = t
                val retryable = isRetryable(t)
                val hasMore = attempt < maxAttempts
                if (!retryable || !hasMore) throw t

                val jitter = (backoff * jitterRatio).toLong().coerceAtLeast(0L)
                val sleepMs = (backoff + Random.nextLong(-jitter, jitter + 1)).coerceAtLeast(0L)
                delay(sleepMs)

                backoff = min(maxBackoffMs, (backoff * 2).coerceAtLeast(1L))
                attempt++
            }
        }

        throw lastErr ?: IllegalStateException("RetryPolicy exhausted without error")
    }
}
