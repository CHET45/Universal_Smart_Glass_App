package com.fersaiyan.cyanbridge.localagent.dailysummary

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.fersaiyan.cyanbridge.ai.router.AiAssistantRouter
import com.fersaiyan.cyanbridge.localagent.memory.LocalAgentMemoryStore

object DailySummaryGenerator {

    data class Input(
        val date: String,
        val confirmedFacts: String,
        val screenSnippets: String,
        val outputFile: File,
    )

    private fun todayString(nowMs: Long = System.currentTimeMillis()): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(nowMs))
    }

    fun buildInputForDate(
        context: Context,
        date: String = todayString(),
        maxCaptureLines: Int = 80,
        maxCharsPerCapture: Int = 600,
        maxTotalChars: Int = 12_000,
    ): Input {
        LocalAgentMemoryStore.ensureSeedFiles(context)

        val confirmedFile = LocalAgentMemoryStore.confirmedDailyFactsFileForDate(context, date)
        if (!confirmedFile.exists()) {
            LocalAgentMemoryStore.writeText(
                confirmedFile,
                "# Confirmed daily facts ($date)\n\n- \n",
            )
        }
        val confirmedFacts = LocalAgentMemoryStore.readText(confirmedFile).trim()

        val captureLines = LocalAgentMemoryStore.readScreenCaptureLines(context, date, maxCaptureLines)
        val snippets = if (captureLines.isNotEmpty()) {
            formatTailScreenCaptures(
                lines = captureLines,
                maxCharsPerCapture = maxCharsPerCapture,
                maxTotalChars = maxTotalChars,
            )
        } else {
            "(no screen captures found for this date)"
        }

        val out = LocalAgentMemoryStore.dailySummaryFileForDate(context, date)

        return Input(
            date = date,
            confirmedFacts = confirmedFacts,
            screenSnippets = snippets,
            outputFile = out,
        )
    }

    private fun formatTailScreenCaptures(
        lines: List<String>,
        maxCharsPerCapture: Int,
        maxTotalChars: Int,
    ): String {
        if (lines.isEmpty()) return "(screen captures file is empty)"

        val timeFmt = SimpleDateFormat("HH:mm", Locale.US)

        val out = StringBuilder()
        var remaining = maxTotalChars

        for (line in lines) {
            if (remaining <= 0) break
            val obj = runCatching { JSONObject(line) }.getOrNull() ?: continue
            val ts = obj.optLong("ts_ms", 0L)
            val pkg = obj.optString("package", "?").ifBlank { "?" }
            val rawText = obj.optString("text", "").trim()
            if (rawText.isBlank()) continue

            val text = rawText
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(maxCharsPerCapture)

            val time = if (ts > 0L) timeFmt.format(Date(ts)) else "??:??"

            val row = "- [$time] $pkg: $text\n"
            if (row.length > remaining) {
                val clipped = row.take(remaining.coerceAtLeast(0))
                out.append(clipped)
                break
            }
            out.append(row)
            remaining -= row.length
        }

        return out.toString().trimEnd()
    }

    fun buildPrompt(input: Input): String {
        return """
You are my personal assistant.

Write a concise daily summary for ${input.date} based ONLY on the information below.

Output requirements:
- Output Markdown only.
- Start with: # Daily Summary (${input.date})
- Then a short narrative (3–6 sentences) written in a mildly first-person style.
- Then: ## Highlights with EXACTLY 5 bullet points.
- Then (optional): ## Open questions (include only if there are real uncertainties).

Rules:
- Do not invent events.
- If something is unclear, say it's unclear.
- Prefer concrete facts over speculation.

Confirmed facts for the day:
${input.confirmedFacts.ifBlank { "(none)" }}

Recent screen-capture snippets (noisy / incomplete):
${input.screenSnippets.ifBlank { "(none)" }}
""".trim()
    }

    suspend fun generateAndStore(
        context: Context,
        date: String = todayString(),
    ): Result<File> {
        return runCatching {
            val input = buildInputForDate(context, date)
            val prompt = buildPrompt(input)

            val summary = AiAssistantRouter.textReply(context, prompt).trim()
            require(summary.isNotBlank()) { "Empty summary returned" }

            LocalAgentMemoryStore.writeText(input.outputFile, summary + "\n")
            DailySummaryPrefs.setLastGeneratedAtMs(context, date, System.currentTimeMillis())

            input.outputFile
        }
    }
}
