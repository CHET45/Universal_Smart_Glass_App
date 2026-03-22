package com.fersaiyan.cyanbridge.ui.localagent

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.fersaiyan.cyanbridge.databinding.ActivityScreenCapturesBinding
import com.fersaiyan.cyanbridge.localagent.memory.LocalAgentMemoryStore
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScreenCapturesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScreenCapturesBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScreenCapturesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        LocalAgentMemoryStore.ensureSeedFiles(this)

        binding.btnClose.setOnClickListener { finish() }
        binding.btnRefresh.setOnClickListener { loadAndRender() }
        binding.btnShare.setOnClickListener { shareRenderedText() }

        loadAndRender()
    }

    private fun loadAndRender() {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(System.currentTimeMillis()))
        val file = LocalAgentMemoryStore.screenCaptureFileForDate(this, date)
        val lines = LocalAgentMemoryStore.readScreenCaptureLines(this, date, maxLines = 25)

        binding.tvTitle.text = "Screen captures ($date)"
        binding.tvPath.text = file.absolutePath

        val rendered = renderTailPretty(lines)
        binding.editCaptures.setText(rendered)

        if (rendered.startsWith("(no captures")) {
            Toast.makeText(this, "No screen captures yet for today", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareRenderedText() {
        val text = binding.editCaptures.text?.toString().orEmpty().trim()
        if (text.isBlank()) {
            Toast.makeText(this, "Nothing to share", Toast.LENGTH_SHORT).show()
            return
        }

        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Screen captures (tail)")
            putExtra(Intent.EXTRA_TEXT, text.take(120_000))
        }
        startActivity(Intent.createChooser(i, "Share screen captures"))
    }

    private fun renderTailPretty(lines: List<String>, maxTextCharsPerEntry: Int = 2500): String {

        if (lines.isEmpty()) return "(no captures yet)"
        val sb = StringBuilder()

        val tsFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

        for (line in lines) {
            val obj = runCatching { JSONObject(line) }.getOrNull()
            if (obj == null) {
                sb.appendLine(line)
                sb.appendLine("---")
                continue
            }

            val ts = obj.optLong("ts_ms", 0L)
            val pkg = obj.optString("package", "")
            val text = obj.optString("text", "")

            val tsText = if (ts > 0L) tsFmt.format(Date(ts)) else "(no-ts)"

            sb.appendLine("[$tsText] $pkg")
            sb.appendLine(text.take(maxTextCharsPerEntry))
            if (text.length > maxTextCharsPerEntry) sb.appendLine("…(truncated)")
            sb.appendLine("\n---\n")
        }

        return sb.toString().trim()
    }
}
