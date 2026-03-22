package com.fersaiyan.cyanbridge.ai.transcription

import java.io.File

interface AudioChunker {
    /**
     * Returns a list of chunk files (in order). If chunking fails, implementors may return
     * a single chunk (the original file) or throw.
     */
    suspend fun chunk(
        inputAudio: File,
        sessionId: Long,
        chunkDurationSec: Long,
    ): List<File>
}

class NoOpAudioChunker : AudioChunker {
    override suspend fun chunk(inputAudio: File, sessionId: Long, chunkDurationSec: Long): List<File> {
        return listOf(inputAudio)
    }
}
