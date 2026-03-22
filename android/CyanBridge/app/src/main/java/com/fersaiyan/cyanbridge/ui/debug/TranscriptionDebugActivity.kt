package com.fersaiyan.cyanbridge.ui.debug

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.fersaiyan.cyanbridge.ai.transcription.ChunkingTranscriptionService
import com.fersaiyan.cyanbridge.ai.transcription.TranscriptionEndpointPrefs
import com.fersaiyan.cyanbridge.ai.transcription.TranscriptionEvent
import com.fersaiyan.cyanbridge.ai.transcription.TranscriptionRequest
import com.fersaiyan.cyanbridge.ai.transcription.backend.FakeTranscriptionBackend
import com.fersaiyan.cyanbridge.ai.transcription.backend.HttpTranscriptionBackend
import com.fersaiyan.cyanbridge.ai.transcription.backend.TranscriptionBackend
import com.fersaiyan.cyanbridge.ai.transcription.storage.RoomTranscriptStore
import com.fersaiyan.cyanbridge.data.local.entity.CaptureSession
import com.fersaiyan.cyanbridge.databinding.ActivityTranscriptionDebugBinding
import com.fersaiyan.cyanbridge.privacy.PrivacyPrefs
import com.fersaiyan.cyanbridge.ui.MyApplication
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.File

/**
 * Minimal manual POC entry point for Chapter 6.
 *
 * Launch via:
 *  adb shell am start -n com.fersaiyan.cyanbridge/.ui.debug.TranscriptionDebugActivity
 */
class TranscriptionDebugActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTranscriptionDebugBinding
    private val scope = MainScope()

    private var latestSession: CaptureSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTranscriptionDebugBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.endpointUrl.setText(TranscriptionEndpointPrefs.getEndpointUrl(this).orEmpty())
        binding.apiKey.setText(TranscriptionEndpointPrefs.getApiKey(this).orEmpty())

        binding.transcriptStorageEnabled.isChecked = PrivacyPrefs.isTranscriptStorageEnabled(this)
        binding.transcriptStorageEnabled.setOnCheckedChangeListener { _, isChecked ->
            PrivacyPrefs.setTranscriptStorageEnabled(this, isChecked)
        }

        binding.saveEndpoint.setOnClickListener {
            TranscriptionEndpointPrefs.setEndpointUrl(this, binding.endpointUrl.text?.toString())
            TranscriptionEndpointPrefs.setApiKey(this, binding.apiKey.text?.toString())
            Toast.makeText(this, "Saved endpoint config", Toast.LENGTH_SHORT).show()
        }

        binding.loadLatest.setOnClickListener { loadLatestSession() }
        binding.transcribe.setOnClickListener { startTranscription() }

        loadLatestSession()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun loadLatestSession() {
        scope.launch {
            val sessions = MyApplication.database.captureSessionDao().getAllSessions().first()
            latestSession = sessions.firstOrNull()

            val s = latestSession
            binding.latestSessionInfo.text = if (s == null) {
                "No capture sessions found yet. Record a meeting first."
            } else {
                "Latest session id=${s.id}\nstartedAt=${s.startedAt}\ndurationSec=${s.durationSec}\naudioPath=${s.audioPath}"
            }
        }
    }

    private fun startTranscription() {
        val session = latestSession
        if (session == null) {
            Toast.makeText(this, "No capture session available", Toast.LENGTH_SHORT).show()
            return
        }

        val audioFile = File(session.audioPath)
        if (!audioFile.exists()) {
            Toast.makeText(this, "Audio file missing: ${session.audioPath}", Toast.LENGTH_LONG).show()
            return
        }

        val backend: TranscriptionBackend = if (binding.providerHttp.isChecked) {
            val endpoint = binding.endpointUrl.text?.toString().orEmpty().trim()
            val apiKey = binding.apiKey.text?.toString()?.trim().takeUnless { it.isNullOrBlank() }
            HttpTranscriptionBackend(endpointUrl = endpoint, apiKey = apiKey)
        } else {
            FakeTranscriptionBackend(fixedText = null)
        }

        val store = RoomTranscriptStore(
            context = applicationContext,
            dao = MyApplication.database.captureTranscriptDao(),
        )

        val service = ChunkingTranscriptionService(
            backend = backend,
            transcriptStore = store,
        )

        binding.progressBar.progress = 0
        binding.progressText.text = "Starting…"
        binding.output.text = ""
        binding.persisted.text = ""
        binding.progressBar.visibility = View.VISIBLE

        scope.launch {
            service.transcribe(
                TranscriptionRequest(
                    audioFile = audioFile,
                    captureSessionId = session.id,
                    languageHint = null,
                )
            ).collect { event ->
                when (event) {
                    is TranscriptionEvent.Started -> {
                        binding.progressText.text = "Started (${event.totalChunks} chunks) provider=${event.provider}"
                    }

                    is TranscriptionEvent.Progress -> {
                        binding.progressBar.progress = event.percent
                        binding.progressText.text = "${event.percent}% — ${event.message}"
                    }

                    is TranscriptionEvent.Completed -> {
                        binding.progressBar.progress = 100
                        binding.progressText.text = "Done (provider=${event.provider})"
                        binding.output.text = event.transcript
                        binding.persisted.text = if (event.persisted) {
                            "Transcript persisted to DB (capture_transcripts)."
                        } else {
                            "Transcript NOT persisted (storage disabled or missing session id)."
                        }
                    }

                    is TranscriptionEvent.Failed -> {
                        binding.progressText.text = "Failed: ${event.error.debugMessage}"
                        binding.persisted.text = if (event.canRetry) "Retryable" else "Not retryable"
                    }
                }
            }
        }
    }
}
