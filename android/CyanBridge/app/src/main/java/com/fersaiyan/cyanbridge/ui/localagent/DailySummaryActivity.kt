package com.fersaiyan.cyanbridge.ui.localagent

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.fersaiyan.cyanbridge.databinding.ActivityDailySummaryBinding
import com.fersaiyan.cyanbridge.localagent.dailysummary.DailySummaryGenerator
import com.fersaiyan.cyanbridge.localagent.dailysummary.DailySummaryPrefs
import com.fersaiyan.cyanbridge.localagent.dailysummary.DailySummaryRegenerateWorker
import com.fersaiyan.cyanbridge.localagent.dailysummary.DailySummaryRunHistory
import com.fersaiyan.cyanbridge.localagent.memory.LocalAgentMemoryStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DailySummaryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDailySummaryBinding
    private val workManager by lazy { WorkManager.getInstance(this) }

    private val date: String by lazy {
        intent.getStringExtra(EXTRA_DATE)?.trim().orEmpty().ifBlank {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(System.currentTimeMillis()))
        }
    }

    private val file by lazy { LocalAgentMemoryStore.dailySummaryFileForDate(this, date) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDailySummaryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        LocalAgentMemoryStore.ensureSeedFiles(this)

        binding.tvTitle.text = "Daily summary ($date)"
        binding.tvPath.text = file.absolutePath

        binding.btnClose.setOnClickListener { finish() }
        binding.btnRefresh.setOnClickListener { refreshFromDisk() }
        binding.btnShare.setOnClickListener { shareCurrent() }
        binding.btnRegenerate.setOnClickListener { regenerate() }

        refreshFromDisk()
        observeRegenerationWork()
    }

    private fun refreshFromDisk() {
        val loaded = LocalAgentMemoryStore.readText(file).trimEnd()
        val text = if (loaded.isNotBlank()) loaded else "(No daily summary generated yet. Tap Regenerate.)"
        binding.tvSummary.text = text

        val last = DailySummaryPrefs.getLastGeneratedAtMs(this, date)
        binding.tvStatus.text = if (last > 0L) {
            val t = SimpleDateFormat("HH:mm", Locale.US).format(Date(last))
            "Last generated: $t"
        } else {
            "Not generated yet"
        }
    }

    private fun setBusy(busy: Boolean) {
        binding.btnRefresh.isEnabled = !busy
        binding.btnRegenerate.isEnabled = !busy
        binding.btnShare.isEnabled = !busy
        binding.layoutProgress.visibility = if (busy) View.VISIBLE else View.GONE
    }

    private fun regenerate() {
        val cooldown = DailySummaryPrefs.remainingCooldownMs(this, date)
        if (cooldown > 0L) {
            val seconds = (cooldown / 1000L).coerceAtLeast(1L)
            Toast.makeText(this, "Please wait ${seconds}s before regenerating.", Toast.LENGTH_SHORT).show()
            return
        }

        setBusy(true)
        val inputTokens = DailySummaryGenerator.estimateInputTokensForDate(this, date)
        val estimate = DailySummaryRunHistory.estimate(
            context = this,
            providerHint = DailySummaryGenerator.providerHint(this),
            inputTokens = inputTokens,
        )

        binding.progressSummary.progress = 1
        binding.tvProgressTitle.text = "Regenerating daily summary (${estimate.provider})"
        binding.tvProgressEta.text = formatEtaText(
            etaMs = estimate.expectedTotalMs,
            sampleCount = estimate.sampleCount,
            stage = "Queued",
        )
        binding.tvStatus.text = "Generating…"

        val request = DailySummaryRegenerateWorker.buildRequest(date)
        workManager.enqueueUniqueWork(
            DailySummaryRegenerateWorker.uniqueWorkName(date),
            ExistingWorkPolicy.REPLACE,
            request,
        )

        Toast.makeText(this, "Generating daily summary in background…", Toast.LENGTH_SHORT).show()
    }

    private fun observeRegenerationWork() {
        workManager.getWorkInfosForUniqueWorkLiveData(
            DailySummaryRegenerateWorker.uniqueWorkName(date),
        ).observe(this) { infos ->
            val info = infos.firstOrNull() ?: run {
                setBusy(false)
                return@observe
            }
            renderWorkInfo(info)
        }
    }

    private fun renderWorkInfo(info: WorkInfo) {
        when (info.state) {
            WorkInfo.State.ENQUEUED,
            WorkInfo.State.BLOCKED,
            WorkInfo.State.RUNNING -> {
                setBusy(true)
                val percent = info.progress.getInt(DailySummaryRegenerateWorker.KEY_PROGRESS_PERCENT, 0)
                    .coerceIn(0, 100)
                val etaMs = info.progress.getLong(DailySummaryRegenerateWorker.KEY_ETA_MS, 0L)
                val stage = info.progress.getString(DailySummaryRegenerateWorker.KEY_STAGE)
                    ?.trim()
                    .orEmpty()
                    .ifBlank { "Generating summary" }
                val provider = info.progress.getString(DailySummaryRegenerateWorker.KEY_PROVIDER)
                    ?.trim()
                    .orEmpty()
                    .ifBlank { DailySummaryGenerator.providerHint(this) }
                val sampleCount = info.progress.getInt(DailySummaryRegenerateWorker.KEY_SAMPLE_COUNT, 0)
                val bulletDone = info.progress.getInt(DailySummaryRegenerateWorker.KEY_BULLET_DONE, 0)
                val bulletTotal = info.progress.getInt(DailySummaryRegenerateWorker.KEY_BULLET_TOTAL, 0)

                binding.progressSummary.progress = percent
                binding.tvProgressTitle.text = "Regenerating daily summary (${provider})"
                binding.tvProgressEta.text = formatEtaText(
                    etaMs = etaMs,
                    sampleCount = sampleCount,
                    stage = stage,
                    bulletDone = bulletDone,
                    bulletTotal = bulletTotal,
                )
                binding.tvStatus.text = if (percent > 0) {
                    "Generating… $percent%"
                } else {
                    "Generating…"
                }
            }

            WorkInfo.State.SUCCEEDED -> {
                setBusy(false)
                binding.progressSummary.progress = 100
                binding.tvStatus.text = "Generation complete"
                refreshFromDisk()
                Toast.makeText(this, "Daily summary saved", Toast.LENGTH_SHORT).show()
            }

            WorkInfo.State.FAILED,
            WorkInfo.State.CANCELLED -> {
                setBusy(false)
                binding.tvStatus.text = "Generation failed"
                val error = info.outputData.getString(DailySummaryRegenerateWorker.KEY_ERROR)
                    ?.trim()
                    .orEmpty()
                    .ifBlank { "Failed to regenerate summary" }
                Toast.makeText(this, error, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun formatEtaText(
        etaMs: Long,
        sampleCount: Int,
        stage: String,
        bulletDone: Int = 0,
        bulletTotal: Int = 0,
    ): String {
        val safeEta = etaMs.coerceAtLeast(0L)
        val totalSeconds = (safeEta / 1000L).coerceAtLeast(0L)
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        val etaLabel = if (minutes > 0L) {
            String.format(Locale.US, "%dm %02ds", minutes, seconds)
        } else {
            String.format(Locale.US, "%ds", seconds)
        }
        val sampleLabel = if (sampleCount > 0) {
            "avg of last ${sampleCount.coerceAtMost(3)} runs"
        } else {
            "cold-start estimate"
        }
        val bulletLabel = if (bulletTotal > 0) {
            " · bullets $bulletDone/$bulletTotal"
        } else {
            ""
        }
        return "$stage$bulletLabel · ETA ~$etaLabel ($sampleLabel)"
    }

    private fun shareCurrent() {
        val content = binding.tvSummary.text?.toString().orEmpty().trim()
        if (content.isBlank()) {
            Toast.makeText(this, "Nothing to share", Toast.LENGTH_SHORT).show()
            return
        }

        val payload = buildString {
            append("Daily summary ($date)\n")
            append("File: ${file.absolutePath}\n\n")
            append(content)
        }

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Daily summary ($date)")
            putExtra(Intent.EXTRA_TEXT, payload)
        }

        startActivity(Intent.createChooser(intent, "Share daily summary"))
    }

    companion object {
        const val EXTRA_DATE = "extra_date"
    }
}
