package com.fersaiyan.cyanbridge.ui.recordings

import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.fersaiyan.cyanbridge.BuildConfig
import com.fersaiyan.cyanbridge.MainActivity
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.ai.transcription.DefaultTranscriptionService
import com.fersaiyan.cyanbridge.ai.transcription.Mp4AudioChunker
import com.fersaiyan.cyanbridge.ai.transcription.NoOpAudioChunker
import com.fersaiyan.cyanbridge.ai.transcription.OpenAIWhisperTranscriptionProvider
import com.fersaiyan.cyanbridge.ai.transcription.RetryPolicy
import com.fersaiyan.cyanbridge.ai.transcription.RetryingTranscriptionProvider
import com.fersaiyan.cyanbridge.ai.transcription.TranscriptionProgress
import com.fersaiyan.cyanbridge.ai.transcription.TranscriptionResult
import com.fersaiyan.cyanbridge.ai.transcription.TranscriptionService
import com.fersaiyan.cyanbridge.ai.transcription.moonshine.MoonshineModelManager
import com.fersaiyan.cyanbridge.ai.transcription.moonshine.MoonshineTranscriptionProvider
import com.fersaiyan.cyanbridge.data.local.entity.CaptureSession
import com.fersaiyan.cyanbridge.databinding.ActivityRecordingsListBinding
import com.fersaiyan.cyanbridge.privacy.PrivacyPrefs
import com.fersaiyan.cyanbridge.ui.ChatListActivity
import com.fersaiyan.cyanbridge.ui.ChatThreadActivity
import com.fersaiyan.cyanbridge.ui.CommunityPluginsActivity
import com.fersaiyan.cyanbridge.ui.MeetingRecordingBannerController
import com.fersaiyan.cyanbridge.ui.MyApplication
import com.fersaiyan.cyanbridge.ui.SettingsActivity
import com.fersaiyan.cyanbridge.chat.ChatStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class RecordingsListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRecordingsListBinding
    private lateinit var adapter: RecordingListAdapter

    private var meetingBannerController: MeetingRecordingBannerController? = null

    private val uiScope = MainScope()
    private var sessionsJob: Job? = null

    private var mediaPlayer: MediaPlayer? = null
    private var currentlyPlayingId: Long? = null

    private var transcribingId: Long? = null
    private val ephemeralTranscripts = mutableMapOf<Long, String>()

    // Offline transcription debug state (since user may not have logcat access).
    private var lastMoonshineKind: MoonshineModelManager.ModelKind? = null
    private var lastMoonshineModelDir: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecordingsListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        meetingBannerController = MeetingRecordingBannerController(
            context = this,
            banner = findViewById(R.id.meeting_recording_banner)!!,
            bannerText = findViewById(R.id.tv_meeting_banner)!!,
            stopButton = findViewById(R.id.btn_meeting_banner_stop)!!,
        ).also { it.bind() }

        adapter = RecordingListAdapter(
            onPlayClick = { session -> onPlayClicked(session) },
            onTranscribeClick = { session -> onTranscribeClicked(session) },
            onViewTranscriptionClick = { session -> onViewTranscriptionClicked(session) },
        )

        binding.recyclerRecordings.layoutManager = LinearLayoutManager(this)
        binding.recyclerRecordings.adapter = adapter

        binding.btnOpenSyncedMedia.setOnClickListener {
            startActivity(Intent(this, SyncedMediaGalleryActivity::class.java))
        }

        setupBottomNavigation()
    }

    override fun onResume() {
        super.onResume()
        // Ensure correct nav highlight when returning via CLEAR_TOP/SINGLE_TOP.
        binding.bottomNavigation.post {
            binding.bottomNavigation.menu.findItem(R.id.nav_transcriptions_recordings).isChecked = true
        }
    }

    override fun onStart() {
        super.onStart()
        meetingBannerController?.onStart()

        sessionsJob?.cancel()
        sessionsJob = uiScope.launch {
            MyApplication.repository.getAllCaptureSessions().collect { sessions ->
                adapter.submitList(sessions)
                binding.emptyState.visibility = if (sessions.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
                binding.recyclerRecordings.visibility = if (sessions.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
            }
        }
    }

    override fun onStop() {
        super.onStop()
        meetingBannerController?.onStop()

        sessionsJob?.cancel()
        sessionsJob = null
        stopPlayback()
    }

    override fun onDestroy() {
        super.onDestroy()
        uiScope.cancel()
        stopPlayback()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun setupBottomNavigation() {
        binding.bottomNavigation.selectedItemId = R.id.nav_transcriptions_recordings
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_transcriptions_recordings -> true
                R.id.nav_glasses -> {
                    binding.bottomNavigation.post {
                        startActivity(Intent(this, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        })
                    }
                    true
                }
                R.id.nav_chats -> {
                    binding.bottomNavigation.post {
                        val last = ChatStore.listNonEmptyThreads().firstOrNull()
                        val now = System.currentTimeMillis()

                        fun lastUserMessageAtMs(chatId: String): Long? {
                            val msgs = ChatStore.listMessages(chatId)
                            return msgs.lastOrNull { it.role == com.fersaiyan.cyanbridge.chat.ChatRole.USER }?.createdAt
                        }

                        val openChatId = if (last != null) {
                            val lastUserAt = lastUserMessageAtMs(last.id) ?: 0L
                            if (lastUserAt > 0L && (now - lastUserAt) < 30 * 60 * 1000) last.id else null
                        } else null

                        val intent = Intent(this, ChatThreadActivity::class.java)
                        if (openChatId != null) {
                            intent.putExtra(ChatThreadActivity.EXTRA_CHAT_ID, openChatId)
                        }
                        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        startActivity(intent)
                    }
                    true
                }
                R.id.nav_settings -> {
                    binding.bottomNavigation.post {
                        startActivity(Intent(this, SettingsActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        })
                    }
                    true
                }
                R.id.nav_community_plugins -> {
                    binding.bottomNavigation.post {
                        startActivity(Intent(this, CommunityPluginsActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        })
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun onPlayClicked(session: CaptureSession) {
        val path = session.audioPath
        if (path.isBlank()) {
            Toast.makeText(this, "Missing audio path", Toast.LENGTH_SHORT).show()
            return
        }
        val f = File(path)
        if (!f.exists()) {
            Toast.makeText(this, "Audio file not found", Toast.LENGTH_LONG).show()
            return
        }

        // Toggle play/pause on the same item.
        if (currentlyPlayingId == session.id) {
            stopPlayback()
            return
        }

        stopPlayback()

        val mp = MediaPlayer()
        mediaPlayer = mp
        currentlyPlayingId = session.id
        adapter.setPlaying(session.id)

        runCatching {
            mp.setDataSource(path)
            mp.setOnCompletionListener {
                stopPlayback()
            }
            mp.prepare()
            mp.start()
        }.onFailure {
            Toast.makeText(this, "Failed to play audio: ${it.message}", Toast.LENGTH_LONG).show()
            stopPlayback()
        }
    }

    private fun onTranscribeClicked(session: CaptureSession) {
        if (transcribingId != null) {
            Toast.makeText(this, "Already transcribing…", Toast.LENGTH_SHORT).show()
            return
        }

        val path = session.audioPath
        if (path.isBlank() || !File(path).exists()) {
            Toast.makeText(this, "Audio file not found", Toast.LENGTH_LONG).show()
            return
        }

        transcribingId = session.id
        adapter.setTranscribing(session.id)

        val progressUi = showProgressDialog(title = "Transcribing", initialMessage = "Preparing…")

        uiScope.launch {
            try {
                // Reset per-run debug info.
                lastMoonshineKind = null
                lastMoonshineModelDir = null

                val result = withContext(Dispatchers.IO) {
                    val apiKey = BuildConfig.OPENAI_API_KEY

                    val provider: com.fersaiyan.cyanbridge.ai.transcription.TranscriptionProvider
                    val chunker: com.fersaiyan.cyanbridge.ai.transcription.AudioChunker

                    if (apiKey.isBlank()) {
                        // Offline fallback: Moonshine (on-device)
                        val kind = MoonshineModelManager.chooseDefault(languageHint = null)
                        val modelDir = MoonshineModelManager.modelDir(applicationContext, kind)
                        lastMoonshineKind = kind
                        lastMoonshineModelDir = modelDir

                        if (!MoonshineModelManager.isInstalled(applicationContext, kind)) {
                            val approved = CompletableDeferred<Boolean>()
                            withContext(Dispatchers.Main) {
                                AlertDialog.Builder(this@RecordingsListActivity)
                                    .setTitle("Download offline transcription model?")
                                    .setMessage(
                                        "To transcribe without OpenAI, the app can download a Moonshine offline model (large download). Proceed?"
                                    )
                                    .setNegativeButton("Not now") { _, _ -> approved.complete(false) }
                                    .setPositiveButton("Download") { _, _ -> approved.complete(true) }
                                    .setCancelable(false)
                                    .show()
                            }

                            if (!approved.await()) {
                                return@withContext TranscriptionResult.Failure(
                                    kind = TranscriptionResult.FailureKind.BAD_REQUEST,
                                    message = "Offline model not installed",
                                    canRetry = true,
                                )
                            }

                            MoonshineModelManager.installIfNeeded(applicationContext, kind) { p ->
                                runOnUiThread {
                                    progressUi.progress.progress = p.percent.coerceIn(0, 100)
                                    progressUi.message.text = p.message
                                }
                            }
                        }

                        // "Flash" debug info for users without logcat.
                        runOnUiThread {
                            Toast.makeText(
                                this@RecordingsListActivity,
                                "Offline transcription: moonshine (${kind.id})",
                                Toast.LENGTH_SHORT
                            ).show()
                        }

                        provider = RetryingTranscriptionProvider(
                            MoonshineTranscriptionProvider(applicationContext, modelDir, kind.modelArch),
                            policy = RetryPolicy(maxAttempts = 1)
                        )
                        chunker = NoOpAudioChunker()
                    } else {
                        provider = RetryingTranscriptionProvider(
                            OpenAIWhisperTranscriptionProvider(apiKey = apiKey),
                            policy = RetryPolicy(maxAttempts = 3)
                        )
                        chunker = Mp4AudioChunker(applicationContext)
                    }

                    val service: TranscriptionService = DefaultTranscriptionService(
                        context = applicationContext,
                        repository = MyApplication.repository,
                        provider = provider,
                        chunker = chunker,
                    )

                    val isOfflineVosk = apiKey.isBlank()

                    service.transcribe(
                        session = session,
                        options = TranscriptionService.Options(chunkDurationSec = 60),
                        onProgress = { p: TranscriptionProgress ->
                            runOnUiThread {
                                val detail = p.detail?.let { " · $it" } ?: ""

                                // Offline Vosk typically runs as one big chunk (NoOpAudioChunker),
                                // so progress would sit at ~5% for a long time. Use an indeterminate
                                // bar during the heavy TRANSCRIBING stage to avoid the "stuck" look.
                                if (isOfflineVosk && p.stage == TranscriptionProgress.Stage.TRANSCRIBING) {
                                    progressUi.progress.isIndeterminate = true
                                    progressUi.message.text = "Transcribing offline…$detail"
                                    return@runOnUiThread
                                }

                                progressUi.progress.isIndeterminate = false
                                progressUi.progress.progress = p.percent.coerceIn(0, 100)

                                progressUi.message.text = when (p.stage) {
                                    TranscriptionProgress.Stage.PREPARING -> "Preparing… ${p.percent}%$detail"
                                    TranscriptionProgress.Stage.CHUNKING -> "Chunking… ${p.percent}%$detail"
                                    TranscriptionProgress.Stage.TRANSCRIBING -> "Transcribing… ${p.percent}%$detail"
                                    TranscriptionProgress.Stage.MERGING -> "Merging… ${p.percent}%$detail"
                                    TranscriptionProgress.Stage.SAVING -> "Saving… ${p.percent}%$detail"
                                    TranscriptionProgress.Stage.DONE -> "Done"
                                }
                            }
                        }
                    )
                }

                when (result) {
                    is TranscriptionResult.Success -> {
                        ephemeralTranscripts[session.id] = result.text
                        Toast.makeText(this@RecordingsListActivity, "Transcription complete", Toast.LENGTH_SHORT).show()
                    }

                    is TranscriptionResult.Failure -> {
                        Toast.makeText(
                            this@RecordingsListActivity,
                            "Transcription failed: ${result.message}",
                            Toast.LENGTH_LONG
                        ).show()

                        // If this looks like the offline Vosk path, show a persistent debug dialog.
                        if (result.message.contains("Vosk", ignoreCase = true) ||
                            result.message.contains("model", ignoreCase = true)
                        ) {
                            showOfflineTranscriptionDebugDialog(error = result.message)
                        }
                    }
                }
            } catch (t: Throwable) {
                Toast.makeText(this@RecordingsListActivity, "Transcription failed: ${t.message}", Toast.LENGTH_LONG).show()
                showOfflineTranscriptionDebugDialog(error = t.message ?: t.toString())
            } finally {
                runCatching { progressUi.dialog.dismiss() }
                transcribingId = null
                adapter.setTranscribing(null)
            }
        }
    }

    private fun onViewTranscriptionClicked(session: CaptureSession) {
        uiScope.launch {
            val record = withContext(Dispatchers.IO) {
                MyApplication.repository.getTranscriptionByCaptureSessionId(session.id)
            }

            val storedText = record?.transcriptText
            val textToShow = storedText ?: ephemeralTranscripts[session.id]

            if (textToShow.isNullOrBlank()) {
                Toast.makeText(this@RecordingsListActivity, "No transcription available yet", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val stored = !storedText.isNullOrBlank()
            val storeToggle = PrivacyPrefs.isTranscriptStorageEnabled(applicationContext)
            val title = if (stored) "Transcription (stored)" else "Transcription"
            val prefix = if (!stored && !storeToggle) {
                "(Transcript storage is OFF in Settings; this text may not be persisted.)\n\n"
            } else {
                ""
            }

            AlertDialog.Builder(this@RecordingsListActivity)
                .setTitle(title)
                .setMessage(prefix + textToShow)
                .setPositiveButton("Close", null)
                .show()
        }
    }

    private data class ProgressUi(
        val dialog: AlertDialog,
        val progress: LinearProgressIndicator,
        val message: TextView,
    )

    private fun showOfflineTranscriptionDebugDialog(error: String) {
        val kind = lastMoonshineKind
        val modelDir = lastMoonshineModelDir
        if (kind == null || modelDir == null) {
            // If this wasn't the offline Moonshine flow, don't spam dialogs.
            return
        }

        val report = MoonshineModelManager.validationReport(modelDir, kind)
        val msg = buildString {
            appendLine("Error: $error")
            appendLine()
            appendLine("Offline model: ${kind.id}")
            appendLine(report)
            appendLine()
            appendLine("Tip: if the model install is partial/corrupt, reset it and try again.")
        }

        AlertDialog.Builder(this)
            .setTitle("Offline transcription debug")
            .setMessage(msg)
            .setPositiveButton("Close", null)
            .setNegativeButton("Reset offline model") { _, _ ->
                uiScope.launch {
                    val deleted = withContext(Dispatchers.IO) {
                        runCatching { modelDir.deleteRecursively() }.isSuccess
                    }
                    Toast.makeText(
                        this@RecordingsListActivity,
                        if (deleted) "Offline model cleared. Try transcribe again." else "Failed to clear offline model",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            .show()
    }

    private fun showProgressDialog(title: String, initialMessage: String): ProgressUi {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        val progress = LinearProgressIndicator(this).apply {
            isIndeterminate = false
            max = 100
            progress = 0
        }

        val tv = TextView(this).apply {
            text = initialMessage
            setTextColor(getColor(R.color.text_secondary))
            val mt = (10 * resources.displayMetrics.density).toInt()
            setPadding(0, mt, 0, 0)
        }

        container.addView(progress)
        container.addView(tv)

        val dlg = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(container)
            .setCancelable(false)
            .create()

        dlg.show()
        return ProgressUi(dlg, progress, tv)
    }

    private fun stopPlayback() {
        runCatching {
            mediaPlayer?.stop()
        }
        runCatching {
            mediaPlayer?.release()
        }
        mediaPlayer = null
        currentlyPlayingId = null
        adapter.setPlaying(null)
    }
}
