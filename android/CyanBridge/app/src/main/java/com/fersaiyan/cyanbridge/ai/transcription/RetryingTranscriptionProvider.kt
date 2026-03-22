package com.fersaiyan.cyanbridge.ai.transcription

import kotlinx.coroutines.delay
import java.io.File
import java.io.IOException

class RetryingTranscriptionProvider(
    private val delegate: TranscriptionProvider,
    private val policy: RetryPolicy = RetryPolicy(),
) : TranscriptionProvider {

    override val name: String = delegate.name

    override suspend fun transcribe(audioFile: File, mimeType: String, language: String?): String {
        val max = policy.maxAttempts.coerceAtLeast(1)
        var lastErr: Throwable? = null

        for (attempt in 1..max) {
            try {
                return delegate.transcribe(audioFile, mimeType, language)
            } catch (e: Throwable) {
                lastErr = e
                val retryable = isRetryable(e)
                val isLast = attempt == max
                if (!retryable || isLast) throw e

                val delayMs = policy.computeDelayMs(attempt)
                delay(delayMs)
            }
        }

        throw lastErr ?: IllegalStateException("Unknown transcription failure")
    }

    private fun isRetryable(t: Throwable): Boolean {
        return when (t) {
            is IOException -> true
            is TranscriptionHttpException -> {
                t.code == 408 || t.code == 429 || (t.code in 500..599)
            }
            else -> false
        }
    }
}
