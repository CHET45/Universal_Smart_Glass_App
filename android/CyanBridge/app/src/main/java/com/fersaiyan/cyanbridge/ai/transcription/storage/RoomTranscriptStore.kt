package com.fersaiyan.cyanbridge.ai.transcription.storage

import android.content.Context
import com.fersaiyan.cyanbridge.data.local.dao.CaptureTranscriptDao
import com.fersaiyan.cyanbridge.data.local.entity.CaptureTranscript
import com.fersaiyan.cyanbridge.privacy.PrivacyPrefs

class RoomTranscriptStore(
    private val context: Context,
    private val dao: CaptureTranscriptDao,
) : TranscriptStore {

    override suspend fun maybePersist(
        captureSessionId: Long?,
        provider: String,
        language: String?,
        transcript: String,
    ): Boolean {
        if (captureSessionId == null) return false
        if (!PrivacyPrefs.isTranscriptStorageEnabled(context)) return false

        val now = System.currentTimeMillis()
        dao.upsert(
            CaptureTranscript(
                captureSessionId = captureSessionId,
                createdAt = now,
                updatedAt = now,
                provider = provider,
                language = language,
                transcript = transcript,
            )
        )
        return true
    }
}
