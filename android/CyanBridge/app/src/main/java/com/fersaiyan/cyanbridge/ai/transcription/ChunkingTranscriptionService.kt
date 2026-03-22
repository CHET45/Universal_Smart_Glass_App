package com.fersaiyan.cyanbridge.ai.transcription

import com.fersaiyan.cyanbridge.ai.transcription.backend.HttpTranscriptionBackend
import com.fersaiyan.cyanbridge.ai.transcription.backend.TranscriptionBackend
import com.fersaiyan.cyanbridge.ai.transcription.chunking.FileChunker
import com.fersaiyan.cyanbridge.ai.transcription.retry.RetryPolicy
import com.fersaiyan.cyanbridge.ai.transcription.storage.TranscriptStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.IOException

/**
 * Flow-first transcription service that emits lifecycle/progress events.
 *
 * It slices the file into byte chunks, retries retryable backend failures per chunk,
 * and optionally persists the final transcript through [TranscriptStore].
 */
class ChunkingTranscriptionService(
    private val backend: TranscriptionBackend,
    private val retryPolicy: RetryPolicy = RetryPolicy(),
    private val transcriptStore: TranscriptStore? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val errorMapper: (Throwable) -> TranscriptionError = { t ->
        // Special-case our HTTP backend helper if available.
        if (backend is HttpTranscriptionBackend) HttpTranscriptionBackend.toTranscriptionError(t)
        else TranscriptionError.Unknown(t.message ?: "unknown_error", t)
    },
) : TranscriptionService {

    override fun transcribe(request: TranscriptionRequest): Flow<TranscriptionEvent> = flow {
        val f = request.audioFile
        if (!f.exists()) {
            emit(TranscriptionEvent.Failed(TranscriptionError.FileNotFound("Audio file not found: ${f.absolutePath}"), canRetry = false))
            return@flow
        }

        val chunks = FileChunker.chunk(f, request.maxChunkBytes)
        if (chunks.isEmpty()) {
            emit(TranscriptionEvent.Failed(TranscriptionError.Provider("Empty audio file"), canRetry = false))
            return@flow
        }

        emit(
            TranscriptionEvent.Started(
                totalChunks = chunks.size,
                totalBytes = f.length(),
                provider = backend.name,
            )
        )

        val sb = StringBuilder()
        val chunkCount = chunks.size

        for ((i, chunk) in chunks.withIndex()) {
            val progressPercent = ((i.toDouble() / chunkCount.toDouble()) * 100.0).toInt().coerceIn(0, 99)
            emit(
                TranscriptionEvent.Progress(
                    percent = progressPercent,
                    message = "Transcribing chunk ${i + 1}/$chunkCount",
                    chunkIndex = i,
                    chunkCount = chunkCount,
                )
            )

            try {
                val text = retryPolicy.execute(
                    isRetryable = { t -> isRetryable(t) },
                    block = { _ -> backend.transcribeChunk(chunk, languageHint = request.languageHint) }
                )

                if (text.isNotBlank()) {
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(text.trim())
                }
            } catch (t: Throwable) {
                val err = errorMapper(t)
                emit(TranscriptionEvent.Failed(err, canRetry = isRetryable(t)))
                return@flow
            }
        }

        val transcript = sb.toString()
        val persisted = transcriptStore?.maybePersist(
            captureSessionId = request.captureSessionId,
            provider = backend.name,
            language = request.languageHint,
            transcript = transcript,
        ) ?: false

        emit(
            TranscriptionEvent.Completed(
                transcript = transcript,
                provider = backend.name,
                persisted = persisted,
            )
        )
    }.flowOn(ioDispatcher)

    override suspend fun transcribe(
        session: com.fersaiyan.cyanbridge.data.local.entity.CaptureSession,
        options: TranscriptionService.Options,
        onProgress: (TranscriptionProgress) -> Unit,
    ): TranscriptionResult {
        TODO("Not yet implemented - Chapter 9 Callback-based transcription")
    }

    private fun isRetryable(t: Throwable): Boolean {
        return when (t) {
            is HttpTranscriptionBackend.HttpException -> t.code >= 500
            is IOException -> true
            else -> false
        }
    }
}
