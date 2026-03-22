package com.fersaiyan.cyanbridge.ai.transcription.storage

/**
 * Transcript persistence abstraction.
 *
 * Implementations must respect privacy defaults (transcript storage OFF by default).
 */
interface TranscriptStore {
    suspend fun maybePersist(
        captureSessionId: Long?,
        provider: String,
        language: String?,
        transcript: String,
    ): Boolean
}
