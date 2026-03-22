package com.fersaiyan.cyanbridge.ai.transcription.backend

import com.fersaiyan.cyanbridge.ai.transcription.chunking.FileChunk
import java.io.IOException

/**
 * Fake backend for unit tests and offline/manual demos.
 */
class FakeTranscriptionBackend(
    private val fixedText: String? = null,
    private val failTimes: Int = 0,
) : TranscriptionBackend {

    override val name: String = "fake"

    @Volatile private var failuresSoFar: Int = 0

    override suspend fun transcribeChunk(chunk: FileChunk, languageHint: String?): String {
        if (failuresSoFar < failTimes) {
            failuresSoFar++
            throw IOException("fake transient failure #$failuresSoFar")
        }

        return fixedText ?: "[fake] chunk=${chunk.index + 1}/${expectedChunkCount(chunk)} bytes=${chunk.lengthBytes}"
    }

    private fun expectedChunkCount(chunk: FileChunk): Int {
        // Best-effort: derive chunk count from total/len only when perfectly even.
        return ((chunk.totalBytes + chunk.lengthBytes - 1) / chunk.lengthBytes).toInt().coerceAtLeast(1)
    }
}
