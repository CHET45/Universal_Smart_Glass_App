package com.fersaiyan.cyanbridge.ai.transcription.backend

import com.fersaiyan.cyanbridge.ai.transcription.chunking.FileChunk

/**
 * Backend/provider that can transcribe one chunk.
 *
 * Higher-level orchestration (chunking, retries, concatenation) is handled by a TranscriptionService.
 */
interface TranscriptionBackend {
    val name: String

    suspend fun transcribeChunk(
        chunk: FileChunk,
        languageHint: String? = null,
    ): String
}
