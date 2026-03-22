package com.fersaiyan.cyanbridge.ui.localagent

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.fersaiyan.cyanbridge.databinding.ActivityDailySummaryBinding
import com.fersaiyan.cyanbridge.localagent.dailysummary.DailySummaryGenerator
import com.fersaiyan.cyanbridge.localagent.dailysummary.DailySummaryPrefs
import com.fersaiyan.cyanbridge.localagent.memory.LocalAgentMemoryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DailySummaryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDailySummaryBinding

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
    }

    private fun regenerate() {
        val cooldown = DailySummaryPrefs.remainingCooldownMs(this, date)
        if (cooldown > 0L) {
            val seconds = (cooldown / 1000L).coerceAtLeast(1L)
            Toast.makeText(this, "Please wait ${seconds}s before regenerating.", Toast.LENGTH_SHORT).show()
            return
        }

        setBusy(true)
        binding.tvStatus.text = "Generating…"
        Toast.makeText(this, "Generating daily summary…", Toast.LENGTH_SHORT).show()

        CoroutineScope(Dispatchers.Main).launch {
            val result = withContext(Dispatchers.IO) {
                DailySummaryGenerator.generateAndStore(this@DailySummaryActivity, date)
            }

            if (result.isSuccess) {
                refreshFromDisk()
                Toast.makeText(this@DailySummaryActivity, "Daily summary saved", Toast.LENGTH_SHORT).show()
            } else {
                binding.tvStatus.text = "Generation failed"
                Toast.makeText(
                    this@DailySummaryActivity,
                    "Failed: ${result.exceptionOrNull()?.message}",
                    Toast.LENGTH_LONG
                ).show()
            }

            setBusy(false)
        }
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
