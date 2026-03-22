package com.fersaiyan.cyanbridge.ai.transcription

import kotlin.math.min
import kotlin.random.Random

data class RetryPolicy(
    val maxAttempts: Int = 3,
    val baseDelayMs: Long = 750,
    val maxDelayMs: Long = 8000,
) {
    fun computeDelayMs(attempt: Int): Long {
        // attempt is 1-based (after first failure)
        val exp = baseDelayMs * (1L shl min(6, attempt))
        val capped = min(maxDelayMs, exp)
        val jitter = (capped * 0.2).toLong()
        return capped + Random.nextLong(0, jitter.coerceAtLeast(1L))
    }
}
