package com.fersaiyan.cyanbridge.ai.transcription

import com.fersaiyan.cyanbridge.data.local.entity.CaptureSession
import kotlinx.coroutines.flow.Flow

/**
 * Pluggable transcription interface (Chapters 6 and 9).
 *
 * The project currently keeps two integration styles so older and newer features can evolve
 * independently while sharing backend implementations:
 * - callback style for capture-session driven workflows
 * - Flow style for streaming/progressive UI
 */
interface TranscriptionService {

    /** Chapter 9 style: suspend function with progress callbacks. */
    data class Options(
        val language: String? = null,
        val chunkDurationSec: Long = 60,
        val mimeType: String = "audio/mp4",
    )

    suspend fun transcribe(
        session: CaptureSession,
        options: Options = Options(),
        onProgress: (TranscriptionProgress) -> Unit = {},
    ): TranscriptionResult

    /** Chapter 6 style: Flow-based transcription. */
    fun transcribe(request: TranscriptionRequest): Flow<TranscriptionEvent>
}
