package com.fersaiyan.cyanbridge.media.autocapture

import android.content.Context
import android.media.MediaMetadataRetriever
import android.util.Log
import com.fersaiyan.cyanbridge.agent.AgentProviderType
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs as AutomationPrefs
import com.fersaiyan.cyanbridge.ai.transcription.DefaultTranscriptionService
import com.fersaiyan.cyanbridge.ai.transcription.GemmaLiteRtTranscriptionProvider
import com.fersaiyan.cyanbridge.ai.transcription.MoonshotTranscriptionProvider
import com.fersaiyan.cyanbridge.ai.transcription.Mp4AudioChunker
import com.fersaiyan.cyanbridge.ai.transcription.RetryPolicy
import com.fersaiyan.cyanbridge.ai.transcription.RetryingTranscriptionProvider
import com.fersaiyan.cyanbridge.ai.transcription.TranscriptionProvider
import com.fersaiyan.cyanbridge.ai.transcription.TranscriptionResult
import com.fersaiyan.cyanbridge.ai.transcription.TranscriptionService
import com.fersaiyan.cyanbridge.data.local.entity.CaptureSession
import com.fersaiyan.cyanbridge.localagent.userfacts.CandidateUserFactsStorage
import com.fersaiyan.cyanbridge.ui.MyApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

object GlassesSyncedAudioIngestor {
    private const val TAG = "GlassesAudioIngest"
    private const val CAPTURE_SOURCE = "GLASSES_SYNC_P2P"
    private const val DEFAULT_DEVICE_CLASS = "HEY_CYAN"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlightSessionIds = ConcurrentHashMap.newKeySet<Long>()

    data class PersistResult(
        val createdSessionId: Long?,
        val localPath: String?,
    )

    suspend fun persistDownloadedAudio(
        context: Context,
        displayName: String,
        payloadBytes: ByteArray,
        takenTimeMs: Long,
    ): PersistResult {
        val appContext = context.applicationContext
        val localFile = prepareLocalFile(appContext, displayName, takenTimeMs)

        if (!localFile.exists() || localFile.length() <= 0L) {
            FileOutputStream(localFile).use { out ->
                out.write(payloadBytes)
                out.flush()
            }
        }

        val existingSession = MyApplication.repository.getCaptureSessionByAudioPath(localFile.absolutePath)
        if (existingSession != null) {
            maybeQueueTranscription(appContext, existingSession)
            return PersistResult(createdSessionId = null, localPath = localFile.absolutePath)
        }

        val startedAt = if (takenTimeMs > 0L) takenTimeMs else System.currentTimeMillis()
        val durationSec = estimateAudioDurationSec(localFile)
        val endedAt = if (durationSec > 0L) startedAt + (durationSec * 1000L) else startedAt

        val session = CaptureSession(
            startedAt = startedAt,
            endedAt = endedAt,
            durationSec = durationSec,
            deviceClass = DEFAULT_DEVICE_CLASS,
            captureSource = CAPTURE_SOURCE,
            audioPath = localFile.absolutePath,
            timerDurationSec = null,
            stopReason = "p2p_sync",
            error = null,
        )
        val id = MyApplication.repository.insertCaptureSession(session)
        maybeQueueTranscription(appContext, session.copy(id = id))
        return PersistResult(createdSessionId = id, localPath = localFile.absolutePath)
    }

    private fun maybeQueueTranscription(context: Context, session: CaptureSession) {
        if (!inFlightSessionIds.add(session.id)) return

        scope.launch {
            try {
                val existing = MyApplication.repository.getTranscriptionByCaptureSessionId(session.id)
                if (existing != null && existing.status.equals("SUCCEEDED", ignoreCase = true)) {
                    return@launch
                }

                val providerType = AutomationPrefs.getProviderType(context)
                val engine = buildTranscriptionEngine(context, providerType)
                val service = DefaultTranscriptionService(
                    context = context,
                    repository = MyApplication.repository,
                    provider = engine.provider,
                    chunker = Mp4AudioChunker(context),
                )
                val result = service.transcribe(
                    session = session,
                    options = TranscriptionService.Options(
                        chunkDurationSec = engine.chunkDurationSec,
                        mimeType = mimeTypeForPath(session.audioPath),
                    ),
                )

                when (result) {
                    is TranscriptionResult.Success -> {
                        val transcript = result.text.trim()
                        if (transcript.isNotBlank()) {
                            appendTranscriptCandidateFacts(context, session, transcript)
                        }
                        Log.i(TAG, "Auto transcription completed for session=${session.id} provider=${result.provider}")
                    }

                    is TranscriptionResult.Failure -> {
                        Log.w(TAG, "Auto transcription failed for session=${session.id}: ${result.message}")
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Auto transcription pipeline failed for session=${session.id}: ${t.message}", t)
            } finally {
                inFlightSessionIds.remove(session.id)
            }
        }
    }

    private data class EngineSelection(
        val provider: TranscriptionProvider,
        val chunkDurationSec: Long,
    )

    private fun buildTranscriptionEngine(context: Context, providerType: AgentProviderType): EngineSelection {
        return when (providerType) {
            AgentProviderType.LOCAL_AGENT -> EngineSelection(
                provider = RetryingTranscriptionProvider(
                    GemmaLiteRtTranscriptionProvider(context),
                    policy = RetryPolicy(maxAttempts = 1),
                ),
                chunkDurationSec = 45L,
            )

            AgentProviderType.PRO_SUBSCRIPTION,
            AgentProviderType.TASKER -> EngineSelection(
                provider = RetryingTranscriptionProvider(
                    MoonshotTranscriptionProvider(context),
                    policy = RetryPolicy(maxAttempts = 3),
                ),
                chunkDurationSec = 60L,
            )
        }
    }

    private fun appendTranscriptCandidateFacts(context: Context, session: CaptureSession, transcript: String) {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(session.startedAt))
        val facts = transcriptToCandidateFacts(session.startedAt, transcript)
        if (facts.isEmpty()) return
        CandidateUserFactsStorage.append(context, date, facts)
    }

    private fun transcriptToCandidateFacts(startedAtMs: Long, transcript: String): List<String> {
        val cleaned = transcript
            .replace('\r', '\n')
            .replace(Regex("[\\t ]+"), " ")
            .trim()
        if (cleaned.isBlank()) return emptyList()

        val timeLabel = SimpleDateFormat("HH:mm", Locale.US).format(Date(startedAtMs))
        val out = ArrayList<String>()
        val seen = LinkedHashSet<String>()

        val segments = cleaned
            .split(Regex("\\n+|(?<=[.!?])\\s+"))
            .map { it.trim() }
            .filter { it.length >= 14 }

        for (segment in segments) {
            val normalized = segment
                .lowercase(Locale.US)
                .replace(Regex("[^a-z0-9\\s]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (normalized.length < 10 || !seen.add(normalized)) continue

            val clipped = if (segment.length > 190) segment.take(187).trimEnd() + "..." else segment
            out += "Glasses audio $timeLabel: $clipped"
            if (out.size >= 8) break
        }

        return out
    }

    private fun mimeTypeForPath(path: String): String {
        return when (File(path).extension.lowercase(Locale.US)) {
            "ogg", "opus" -> "audio/ogg"
            "wav" -> "audio/wav"
            "mp3" -> "audio/mpeg"
            else -> "audio/mp4"
        }
    }

    private fun estimateAudioDurationSec(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?: 0L
            (durationMs / 1000L).coerceAtLeast(0L)
        } catch (_: Throwable) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun prepareLocalFile(context: Context, displayName: String, takenTimeMs: Long): File {
        val recordingsDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "recordings")
        if (!recordingsDir.exists()) recordingsDir.mkdirs()

        val fallbackTs = if (takenTimeMs > 0L) takenTimeMs else System.currentTimeMillis()
        val base = displayName
            .substringBeforeLast('.')
            .ifBlank { "glasses_audio_$fallbackTs" }
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .take(96)
            .ifBlank { "glasses_audio_$fallbackTs" }

        return File(recordingsDir, "glasses_sync_${base}.ogg")
    }
}
