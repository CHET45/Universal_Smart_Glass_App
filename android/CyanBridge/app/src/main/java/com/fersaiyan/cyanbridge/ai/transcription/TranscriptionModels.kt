package com.fersaiyan.cyanbridge.ai.transcription

import java.io.File

sealed class TranscriptionResult {
    data class Success(
        val text: String,
        val provider: String,
    ) : TranscriptionResult()

    data class Failure(
        val kind: FailureKind,
        val message: String,
        val canRetry: Boolean,
    ) : TranscriptionResult()

    enum class FailureKind {
        NETWORK,
        AUTH,
        RATE_LIMIT,
        SERVER,
        BAD_REQUEST,
        IO,
        UNKNOWN,
    }
}

data class TranscriptionProgress(
    val percent: Int,
    val stage: Stage,
    val detail: String? = null,
) {
    enum class Stage {
        PREPARING,
        CHUNKING,
        TRANSCRIBING,
        MERGING,
        SAVING,
        DONE,
    }
}

enum class TranscriptionStatus {
    IN_PROGRESS,
    SUCCEEDED,
    FAILED,
}

data class TranscriptionRequest(
    val audioFile: File,
    /** Optional DB linkage for persistence (capture_sessions.id). */
    val captureSessionId: Long? = null,
    val languageHint: String? = null,
    /** Max bytes per chunk for upload/transcription requests. */
    val maxChunkBytes: Long = DEFAULT_MAX_CHUNK_BYTES,
) {
    companion object {
        /** Conservative default to keep requests small in POC. */
        const val DEFAULT_MAX_CHUNK_BYTES: Long = 5L * 1024L * 1024L // 5 MiB
    }
}

sealed class TranscriptionEvent {
    data class Started(
        val totalChunks: Int,
        val totalBytes: Long,
        val provider: String,
    ) : TranscriptionEvent()

    data class Progress(
        val percent: Int,
        val message: String,
        val chunkIndex: Int,
        val chunkCount: Int,
    ) : TranscriptionEvent()

    data class Completed(
        val transcript: String,
        val provider: String,
        val persisted: Boolean,
    ) : TranscriptionEvent()

    data class Failed(
        val error: TranscriptionError,
        val canRetry: Boolean,
    ) : TranscriptionEvent()
}

sealed class TranscriptionError(open val debugMessage: String, open val cause: Throwable? = null) {
    data class FileNotFound(override val debugMessage: String) : TranscriptionError(debugMessage)
    data class NotConfigured(override val debugMessage: String) : TranscriptionError(debugMessage)
    data class Network(override val debugMessage: String, override val cause: Throwable? = null) : TranscriptionError(debugMessage, cause)
    data class Http(override val debugMessage: String, val code: Int) : TranscriptionError(debugMessage)
    data class Provider(override val debugMessage: String, override val cause: Throwable? = null) : TranscriptionError(debugMessage, cause)
    data class Unknown(override val debugMessage: String, override val cause: Throwable? = null) : TranscriptionError(debugMessage, cause)
}
