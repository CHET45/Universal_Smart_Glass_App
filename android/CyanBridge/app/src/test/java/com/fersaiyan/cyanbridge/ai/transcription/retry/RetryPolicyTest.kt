package com.fersaiyan.cyanbridge.ai.transcription.retry

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class RetryPolicyTest {

    @Test
    fun execute_retriesThenSucceeds() {
        runBlocking {
        val policy = RetryPolicy(maxAttempts = 3, initialBackoffMs = 0, maxBackoffMs = 0, jitterRatio = 0.0)

        val attempts = AtomicInteger(0)
        val result = policy.execute(
            isRetryable = { it is IOException },
            block = { _ ->
                val a = attempts.incrementAndGet()
                if (a < 3) throw IOException("boom")
                "ok"
            }
        )

        assertEquals("ok", result)
        assertEquals(3, attempts.get())
        }
    }

    @Test(expected = IOException::class)
    fun execute_stopsAfterMaxAttempts() {
        runBlocking {
        val policy = RetryPolicy(maxAttempts = 2, initialBackoffMs = 0, maxBackoffMs = 0, jitterRatio = 0.0)
        val attempts = AtomicInteger(0)

        policy.execute<String>(
            isRetryable = { true },
            block = { _ ->
                attempts.incrementAndGet()
                throw IOException("always")
            }
        )
        }
    }
}
