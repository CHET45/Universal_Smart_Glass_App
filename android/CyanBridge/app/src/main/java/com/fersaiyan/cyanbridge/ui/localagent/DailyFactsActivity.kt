package com.fersaiyan.cyanbridge.ui.localagent

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.fersaiyan.cyanbridge.databinding.ActivityDailyFactsBinding
import com.fersaiyan.cyanbridge.localagent.memory.LocalAgentMemoryStore

class DailyFactsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDailyFactsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDailyFactsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        LocalAgentMemoryStore.ensureSeedFiles(this)

        val mode = intent.getStringExtra(EXTRA_MODE)?.trim().orEmpty().ifBlank { MODE_DRAFT }
        val date = intent.getStringExtra(EXTRA_DATE)?.trim().orEmpty().ifBlank {
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(java.util.Date(System.currentTimeMillis()))
        }

        val file = when (mode) {
            MODE_CONFIRMED -> LocalAgentMemoryStore.confirmedDailyFactsFileForDate(this, date)
            else -> LocalAgentMemoryStore.dailyFactsFileForDate(this, date)
        }

        binding.tvTitle.text = when (mode) {
            MODE_CONFIRMED -> "Confirmed daily facts ($date)"
            else -> "Daily facts ($date)"
        }

        binding.tilFacts.hint = when (mode) {
            MODE_CONFIRMED -> "Confirmed facts (used by the agent as true for this day)"
            else -> "Write facts you want to remember / verify"
        }

        binding.tvPath.text = file.absolutePath
        binding.editFacts.setText(LocalAgentMemoryStore.readText(file))

        binding.btnSave.setOnClickListener {
            LocalAgentMemoryStore.writeText(file, binding.editFacts.text?.toString().orEmpty())
            Toast.makeText(
                this,
                if (mode == MODE_CONFIRMED) "Saved confirmed facts" else "Saved daily facts",
                Toast.LENGTH_SHORT
            ).show()
        }

        binding.btnClose.setOnClickListener { finish() }
    }

    companion object {
        const val EXTRA_MODE = "extra_mode"
        const val EXTRA_DATE = "extra_date"

        const val MODE_DRAFT = "draft"
        const val MODE_CONFIRMED = "confirmed"
    }
}
