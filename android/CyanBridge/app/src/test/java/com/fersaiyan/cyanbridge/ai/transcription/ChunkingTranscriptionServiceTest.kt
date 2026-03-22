package com.fersaiyan.cyanbridge.ai.transcription

import com.fersaiyan.cyanbridge.ai.transcription.backend.FakeTranscriptionBackend
import com.fersaiyan.cyanbridge.ai.transcription.retry.RetryPolicy
import com.fersaiyan.cyanbridge.ai.transcription.storage.TranscriptStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChunkingTranscriptionServiceTest {

    @Test
    fun transcribe_emitsProgressAndCompletes_andPersistsWhenStoreAllows() = runBlocking {
        val f = tempFileWithBytes(ByteArray(10) { it.toByte() })

        val backend = FakeTranscriptionBackend(fixedText = "hi", failTimes = 1)
        val store = object : TranscriptStore {
            override suspend fun maybePersist(
                captureSessionId: Long?,
                provider: String,
                language: String?,
                transcript: String,
            ): Boolean {
                return captureSessionId == 123L && provider == "fake" && transcript.isNotBlank()
            }
        }

        val service = ChunkingTranscriptionService(
            backend = backend,
            retryPolicy = RetryPolicy(maxAttempts = 3, initialBackoffMs = 0, maxBackoffMs = 0, jitterRatio = 0.0),
            transcriptStore = store,
            ioDispatcher = Dispatchers.Unconfined,
        )

        val events = service.transcribe(
            TranscriptionRequest(
                audioFile = f,
                captureSessionId = 123L,
                maxChunkBytes = 4,
            )
        ).toList()

        assertTrue(events.first() is TranscriptionEvent.Started)
        assertTrue(events.any { it is TranscriptionEvent.Progress })
        assertTrue(events.last() is TranscriptionEvent.Completed)

        val completed = events.last() as TranscriptionEvent.Completed
        assertEquals("fake", completed.provider)
        assertEquals(true, completed.persisted)
        assertEquals("hi\nhi\nhi", completed.transcript)
    }

    @Test
    fun transcribe_missingFile_emitsFailedNotRetryable() = runBlocking {
        val missing = File("/path/does/not/exist.m4a")
        val service = ChunkingTranscriptionService(
            backend = FakeTranscriptionBackend(fixedText = "ignored"),
            ioDispatcher = Dispatchers.Unconfined,
        )

        val events = service.transcribe(TranscriptionRequest(audioFile = missing)).toList()
        assertTrue(events.size == 1)
        val failed = events[0] as TranscriptionEvent.Failed
        assertTrue(failed.error is TranscriptionError.FileNotFound)
        assertEquals(false, failed.canRetry)
    }

    private fun tempFileWithBytes(bytes: ByteArray): File {
        val f = File.createTempFile("audio", ".bin")
        f.deleteOnExit()
        f.writeBytes(bytes)
        return f
    }
}
