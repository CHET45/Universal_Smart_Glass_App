package com.fersaiyan.cyanbridge.ai.transcription

import android.content.Context
import com.fersaiyan.cyanbridge.data.local.entity.CaptureSession
import com.fersaiyan.cyanbridge.data.local.entity.TranscriptionRecord
import com.fersaiyan.cyanbridge.data.repository.CyanBridgeRepository
import com.fersaiyan.cyanbridge.privacy.PrivacyPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Session-oriented transcription implementation used by meeting capture flows.
 *
 * Responsibilities:
 * - validate capture session/audio file
 * - chunk audio and call provider sequentially
 * - publish progress callbacks
 * - persist status/progress in Room
 * - respect transcript-storage privacy toggle on final persistence
 */
class DefaultTranscriptionService(
    private val context: Context,
    private val repository: CyanBridgeRepository,
    private val provider: TranscriptionProvider,
    private val chunker: AudioChunker,
) : TranscriptionService {

    override suspend fun transcribe(
        session: CaptureSession,
        options: TranscriptionService.Options,
        onProgress: (TranscriptionProgress) -> Unit,
    ): TranscriptionResult {
        val now = System.currentTimeMillis()
        val sessionId = session.id
        if (sessionId <= 0) {
            return TranscriptionResult.Failure(
                kind = TranscriptionResult.FailureKind.BAD_REQUEST,
                message = "Invalid capture session id",
                canRetry = false
            )
        }

        val audioFile = File(session.audioPath)
        if (!audioFile.exists()) {
            return TranscriptionResult.Failure(
                kind = TranscriptionResult.FailureKind.IO,
                message = "Audio file not found",
                canRetry = false
            )
        }

        val storeTranscript = PrivacyPrefs.isTranscriptStorageEnabled(context)

        suspend fun upsert(status: TranscriptionStatus, percent: Int, error: String? = null, text: String? = null) {
            val existing = repository.getTranscriptionByCaptureSessionId(sessionId)
            val createdAt = existing?.createdAt ?: now
            repository.upsertTranscription(
                TranscriptionRecord(
                    captureSessionId = sessionId,
                    status = status.name,
                    provider = provider.name,
                    language = options.language,
                    createdAt = createdAt,
                    updatedAt = System.currentTimeMillis(),
                    progressPercent = percent.coerceIn(0, 100),
                    error = error,
                    transcriptText = text,
                )
            )
        }

        return withContext(Dispatchers.IO) {
            try {
                onProgress(TranscriptionProgress(0, TranscriptionProgress.Stage.PREPARING, "Preparing"))
                upsert(TranscriptionStatus.IN_PROGRESS, 0)

                onProgress(TranscriptionProgress(5, TranscriptionProgress.Stage.CHUNKING, "Chunking audio"))
                val chunks = chunker.chunk(audioFile, sessionId = sessionId, chunkDurationSec = options.chunkDurationSec)
                    .ifEmpty { listOf(audioFile) }
                val originalPath = audioFile.absolutePath

                val sb = StringBuilder()

                for ((idx, chunk) in chunks.withIndex()) {
                    val detail = "Chunk ${idx + 1}/${chunks.size}"
                    val p = 5 + ((idx.toDouble() / chunks.size.toDouble()) * 85.0).toInt()
                    onProgress(TranscriptionProgress(p, TranscriptionProgress.Stage.TRANSCRIBING, detail))
                    upsert(TranscriptionStatus.IN_PROGRESS, p)

                    try {
                        val chunkText = provider.transcribe(chunk, options.mimeType, options.language)
                        if (chunkText.isNotBlank()) {
                            if (sb.isNotEmpty()) sb.append("\n")
                            sb.append(chunkText.trim())
                        }
                    } finally {
                        if (chunk.absolutePath != originalPath) {
                            runCatching { chunk.delete() }
                        }
                    }
                }

                onProgress(TranscriptionProgress(92, TranscriptionProgress.Stage.MERGING, "Merging"))
                val fullText = sb.toString().trim()

                onProgress(TranscriptionProgress(98, TranscriptionProgress.Stage.SAVING, "Saving"))
                upsert(
                    status = TranscriptionStatus.SUCCEEDED,
                    percent = 100,
                    error = null,
                    // Privacy default: keep status metadata even when transcript text is not persisted.
                    text = if (storeTranscript) fullText else null
                )

                onProgress(TranscriptionProgress(100, TranscriptionProgress.Stage.DONE, "Done"))
                TranscriptionResult.Success(text = fullText, provider = provider.name)
            } catch (t: Throwable) {
                val failure = mapFailure(t)
                upsert(
                    status = TranscriptionStatus.FAILED,
                    percent = 0,
                    error = failure.message,
                    text = null
                )
                failure
            }
        }
    }

    override fun transcribe(request: TranscriptionRequest): kotlinx.coroutines.flow.Flow<TranscriptionEvent> {
        TODO("Not yet implemented - Chapter 6 Flow-based transcription")
    }

    private fun mapFailure(t: Throwable): TranscriptionResult.Failure {
        return when (t) {
            is IOException -> TranscriptionResult.Failure(
                kind = TranscriptionResult.FailureKind.NETWORK,
                message = t.message ?: "Network error",
                canRetry = true
            )
            is TranscriptionHttpException -> {
                val kind = when (t.code) {
                    401, 403 -> TranscriptionResult.FailureKind.AUTH
                    429 -> TranscriptionResult.FailureKind.RATE_LIMIT
                    in 500..599 -> TranscriptionResult.FailureKind.SERVER
                    else -> TranscriptionResult.FailureKind.BAD_REQUEST
                }
                val canRetry = t.code == 429 || t.code == 408 || (t.code in 500..599)
                TranscriptionResult.Failure(kind, "HTTP ${t.code}", canRetry)
            }
            else -> TranscriptionResult.Failure(
                kind = TranscriptionResult.FailureKind.UNKNOWN,
                message = t.message ?: "Unknown error",
                canRetry = true
            )
        }
    }
}
