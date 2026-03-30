package com.fersaiyan.cyanbridge.localagent.dailysummary

import android.content.Context
import com.fersaiyan.cyanbridge.agent.AgentProviderType
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs as AutomationPrefs
import com.fersaiyan.cyanbridge.agent.ProSubscriptionAiPrefs
import com.fersaiyan.cyanbridge.agent.ProSubscriptionPrefs
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.fersaiyan.cyanbridge.ai.router.AiAssistantRouter
import com.fersaiyan.cyanbridge.ai.router.CliRelayClient
import com.fersaiyan.cyanbridge.localmodels.provider.LocalModelsProvider
import com.fersaiyan.cyanbridge.localagent.memory.LocalAgentMemoryStore

object DailySummaryGenerator {
    private val localModelsProvider = LocalModelsProvider()

    private data class ProviderResponse(
        val text: String,
        val metrics: DailySummaryRunHistory.RunMetrics,
    )

    data class Input(
        val date: String,
        val confirmedFacts: String,
        val previousSummary: String?,
        val newScreenSnippets: String,
        val isIncremental: Boolean,
        val outputFile: File,
    )

    private fun todayString(nowMs: Long = System.currentTimeMillis()): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(nowMs))
    }

    fun providerHint(context: Context): String {
        return when (AutomationPrefs.getProviderType(context)) {
            AgentProviderType.LOCAL_AGENT -> "local_models"
            AgentProviderType.PRO_SUBSCRIPTION -> "cli_relay"
            AgentProviderType.TASKER -> if (ProSubscriptionPrefs.isActiveLocally(context)) "cli_relay" else "ai_router"
        }
    }

    fun estimateInputTokensForDate(
        context: Context,
        date: String = todayString(),
    ): Int {
        val input = buildInputForDate(context = context, date = date)
        val prompt = buildPrompt(input)
        return DailySummaryRunHistory.estimateTokenCount(prompt)
    }

    fun buildInputForDate(
        context: Context,
        date: String = todayString(),
        maxCaptureLines: Int = 80,
        maxCharsPerCapture: Int = 600,
        maxTotalChars: Int = 12_000,
        forceFullRebuild: Boolean = false,
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

        val lastProcessedAtMs = DailySummaryPrefs.getLastCaptureProcessedAtMs(context, date)
        val hasExistingSummary = DailySummaryPrefs.getLastGeneratedAtMs(context, date) > 0L
        
        val previousSummary = if (hasExistingSummary && !forceFullRebuild) {
            val summaryFile = LocalAgentMemoryStore.dailySummaryFileForDate(context, date)
            val existing = LocalAgentMemoryStore.readText(summaryFile).trim()
            if (existing.isNotBlank() && !existing.contains("(Generate from Settings")) existing else null
        } else {
            null
        }

        val allCaptureLines = LocalAgentMemoryStore.readScreenCaptureLines(context, date, maxCaptureLines * 3)
        
        val newCapturesOnly = if (lastProcessedAtMs > 0L && allCaptureLines.isNotEmpty() && previousSummary != null && !forceFullRebuild) {
            val filtered = allCaptureLines.filter { line ->
                val obj = runCatching { JSONObject(line) }.getOrNull()
                val ts = obj?.optLong("ts_ms", 0L) ?: 0L
                ts > lastProcessedAtMs
            }
            filtered.takeLast(maxCaptureLines)
        } else {
            allCaptureLines.takeLast(maxCaptureLines)
        }

        val snippets = if (newCapturesOnly.isNotEmpty()) {
            formatTailScreenCaptures(
                lines = newCapturesOnly,
                maxCharsPerCapture = maxCharsPerCapture,
                maxTotalChars = maxTotalChars,
            )
        } else {
            "(no new screen captures since last summary)"
        }

        val isIncremental = lastProcessedAtMs > 0L && previousSummary != null && !forceFullRebuild

        val out = LocalAgentMemoryStore.dailySummaryFileForDate(context, date)

        return Input(
            date = date,
            confirmedFacts = confirmedFacts,
            previousSummary = previousSummary,
            newScreenSnippets = snippets,
            isIncremental = isIncremental,
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
        val seen = HashSet<String>()

        for (line in lines) {
            if (remaining <= 0) break
            val obj = runCatching { JSONObject(line) }.getOrNull() ?: continue
            val ts = obj.optLong("ts_ms", 0L)
            val pkg = obj.optString("package", "?").ifBlank { "?" }
            val rawText = obj.optString("text", "").trim()
            if (rawText.isBlank()) continue

            val text = rawText
                .replace(Regex("\\s+"), " ")
                .replace(Regex("[\\u0000-\\u001F]"), " ")
                .trim()
                .take(maxCharsPerCapture)
            if (!looksLikeUsefulCapture(text)) continue

            val dedupeKey = text
                .lowercase(Locale.US)
                .replace(Regex("\\s+"), " ")
                .take(180)
            if (!seen.add(dedupeKey)) continue

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

        val rendered = out.toString().trimEnd()
        return if (rendered.isBlank()) {
            "(screen captures existed but were mostly noisy/duplicated UI text)"
        } else {
            rendered
        }
    }

    private fun looksLikeUsefulCapture(text: String): Boolean {
        val clean = text.trim()
        if (clean.length < 12) return false

        val alphaNum = clean.count { it.isLetterOrDigit() }
        if (alphaNum < 8) return false

        val symbolRatio = 1.0 - (alphaNum.toDouble() / clean.length.toDouble())
        if (symbolRatio > 0.60) return false

        return true
    }

    fun buildPrompt(input: Input): String {
        return if (input.isIncremental && input.previousSummary != null) {
            buildIncrementalPrompt(input)
        } else {
            buildFullPrompt(input)
        }
    }

    private fun buildFullPrompt(input: Input): String {
        return """
You are my personal AI assistant. Create a daily summary from the information below.

FACTS I CONFIRMED TODAY:
${input.confirmedFacts.ifBlank { "(none)" }}

SCREEN ACTIVITY (OCR from my phone - may have UI noise but meaningful content is real):
${input.newScreenSnippets.ifBlank { "(no screen captures)" }}

OUTPUT FORMAT:
# Daily Summary (${input.date})

[Short first-person narrative - what you did today]

## Highlights
- [bullet 1]
- [bullet 2]
- [bullet 3]
- [bullet 4]
- [bullet 5]

## Open questions
[Only include if real uncertainties exist]

IMPORTANT:
- Do not invent facts
- Do not refuse - always produce a summary even if input is messy
- Do not apologize or say you can't help
- If uncertain, note it in "Open questions"
""".trim()
    }

    private fun buildIncrementalPrompt(input: Input): String {
        return """
You are my personal AI assistant. Your task is to UPDATE a daily summary.

CURRENT SUMMARY (keep this, just add to it):
${input.previousSummary}

NEW SCREEN ACTIVITY (add relevant items to the summary above):
${input.newScreenSnippets.ifBlank { "(no new captures since last summary)" }}

OUTPUT INSTRUCTIONS:
- Output Markdown
- Keep the "# Daily Summary (YYYY-MM-DD)" header  
- Keep existing narrative and bullets
- ADD new events from the new captures above
- If new captures contain nothing useful, just return the original summary unchanged
- NEVER refuse, NEVER say you can't, NEVER ask for more details
- If uncertain, note it briefly in "## Open questions"

Remember: You MUST output a valid summary. Do not refuse.
""".trim()
    }

    suspend fun generateAndStore(
        context: Context,
        date: String = todayString(),
    ): Result<File> {
        val forceFullRebuild = false

        return runCatching {
            val input = buildInputForDate(context, date, forceFullRebuild = forceFullRebuild)
            val prompt = buildPrompt(input)

            val providerResult = runCatching {
                generateSummary(context, prompt)
            }.recoverCatching { firstErr ->
                val fallbackInput = buildInputForDate(
                    context = context,
                    date = date,
                    maxCaptureLines = 50,
                    maxCharsPerCapture = 400,
                    maxTotalChars = 8_000,
                    forceFullRebuild = true,
                )
                val fallbackPrompt = buildFullPrompt(fallbackInput)
                generateSummary(context, fallbackPrompt)
            }.getOrThrow()

            val summary = providerResult.text.trim()

            require(summary.isNotBlank()) { "Empty summary returned" }

            LocalAgentMemoryStore.writeText(input.outputFile, summary + "\n")
            DailySummaryPrefs.setLastGeneratedAtMs(context, date, System.currentTimeMillis())
            DailySummaryRunHistory.record(context, providerResult.metrics)

            input.outputFile
        }
    }

    private suspend fun generateSummary(context: Context, prompt: String): ProviderResponse {
        val agentType = AutomationPrefs.getProviderType(context)
        val hasPro = ProSubscriptionPrefs.isActiveLocally(context)

        return when (agentType) {
            AgentProviderType.LOCAL_AGENT -> {
                runCatching { runLocalModels(context, prompt) }
                    .recoverCatching { localErr ->
                        if (!hasPro) {
                            throw IllegalStateException("Local model unavailable (${localErr.message}).")
                        }
                        runRelay(context, prompt)
                    }
                    .getOrThrow()
            }

            AgentProviderType.PRO_SUBSCRIPTION -> runRelay(context, prompt)

            AgentProviderType.TASKER -> {
                if (hasPro) {
                    runRelay(context, prompt)
                } else {
                    runRouterFallback(context, prompt)
                }
            }
        }
    }

    private suspend fun runRelay(context: Context, prompt: String): ProviderResponse {
        val modelOverride = ProSubscriptionAiPrefs.getTasksModel(context)
            .trim()
            .takeIf { it.isNotBlank() }

        val details = CliRelayClient.voiceQueryDetailed(
            context = context,
            prompt = prompt,
            modelOverride = modelOverride,
        ).getOrElse { err ->
            throw IllegalStateException("Pro relay unavailable (${err.message}).")
        }

        val reply = details.reply.trim()
        if (!isUsableSummaryReply(reply)) {
            throw IllegalStateException("Unable to generate daily summary from active provider.")
        }

        return ProviderResponse(
            text = reply,
            metrics = DailySummaryRunHistory.RunMetrics(
                provider = "cli_relay",
                inputTokens = details.telemetry.inputTokens,
                outputTokens = details.telemetry.outputTokens,
                promptTokensPerSec = details.telemetry.promptTokensPerSec,
                generationTokensPerSec = details.telemetry.generationTokensPerSec,
                totalMs = details.telemetry.totalMs,
            ),
        )
    }

    private suspend fun runLocalModels(context: Context, prompt: String): ProviderResponse {
        val inputTokens = DailySummaryRunHistory.estimateTokenCount(prompt)
        var tokenCount = 0
        var firstTokenAtMs = 0L
        val started = System.currentTimeMillis()

        val reply = localModelsProvider.streamChat(
            context = context,
            messages = listOf(mapOf("role" to "User", "content" to prompt)),
            onToken = {
                if (firstTokenAtMs <= 0L) {
                    firstTokenAtMs = System.currentTimeMillis()
                }
                tokenCount += 1
            },
        ).trim()

        if (!isUsableSummaryReply(reply)) {
            throw IllegalStateException("Local model returned an unusable response.")
        }

        val ended = System.currentTimeMillis()
        val totalMs = (ended - started).coerceAtLeast(1L)
        val outputTokens = tokenCount.coerceAtLeast(DailySummaryRunHistory.estimateTokenCount(reply))
        val promptMs = if (firstTokenAtMs > started) {
            (firstTokenAtMs - started).coerceAtLeast(1L)
        } else {
            (totalMs * 0.35).toLong().coerceAtLeast(1L)
        }
        val generationMs = (totalMs - promptMs).coerceAtLeast(1L)

        return ProviderResponse(
            text = reply,
            metrics = DailySummaryRunHistory.RunMetrics(
                provider = "local_models",
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                promptTokensPerSec = inputTokens / (promptMs / 1000.0),
                generationTokensPerSec = outputTokens / (generationMs / 1000.0),
                totalMs = totalMs,
            ),
        )
    }

    private suspend fun runRouterFallback(context: Context, prompt: String): ProviderResponse {
        val inputTokens = DailySummaryRunHistory.estimateTokenCount(prompt)
        val started = System.currentTimeMillis()
        val reply = AiAssistantRouter.textReply(context, prompt).trim()
        val totalMs = (System.currentTimeMillis() - started).coerceAtLeast(1L)

        if (!isUsableSummaryReply(reply)) {
            throw IllegalStateException(
                "AI provider returned a placeholder reply. Choose Pro Subscription or Local Models in Settings.",
            )
        }

        val outputTokens = DailySummaryRunHistory.estimateTokenCount(reply)
        val promptMs = (totalMs * 0.35).toLong().coerceAtLeast(1L)
        val generationMs = (totalMs - promptMs).coerceAtLeast(1L)

        return ProviderResponse(
            text = reply,
            metrics = DailySummaryRunHistory.RunMetrics(
                provider = "ai_router",
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                promptTokensPerSec = inputTokens / (promptMs / 1000.0),
                generationTokensPerSec = outputTokens / (generationMs / 1000.0),
                totalMs = totalMs,
            ),
        )
    }

    private fun isUsableSummaryReply(text: String): Boolean {
        val s = text.trim()
        val lower = s.lowercase(Locale.US)
        if (s.isBlank()) return false
        if (s.startsWith("Demo mode reply:", ignoreCase = true)) return false
        if (s.startsWith("Relay unavailable (", ignoreCase = true)) return false
        if (s.startsWith("Company backend is not configured", ignoreCase = true)) return false
        if (s.startsWith("I couldn't generate a reply yet.", ignoreCase = true)) return false
        if (s.startsWith("No local model is installed.", ignoreCase = true)) return false
        if (lower.startsWith("i apologize")) return false
        if (lower.startsWith("i'm sorry")) return false
        if (lower.startsWith("sorry")) return false
        if (lower.contains("i don't have any specific context")) return false
        if (lower.contains("i don't have enough information")) return false
        if (lower.contains("random lines of text")) return false
        if (lower.contains("cannot generate a summary")) return false
        if (lower.contains("please provide more details")) return false
        if (lower.contains("can't provide")) return false
        if (lower.contains("unable to provide")) return false
        if (lower.contains("don't have enough context")) return false
        if (lower.contains("not enough information")) return false
        if (lower.contains("provide more details")) return false
        if (lower.contains("what specific content")) return false
        if (lower.contains("what you're looking for")) return false
        if (lower.contains("clarify what you need")) return false
        if (lower.contains("mix of different apps")) return false
        return true
    }

    private fun shouldRetryWithCompactPrompt(error: Throwable): Boolean {
        val msg = error.message?.lowercase().orEmpty()
        return msg.contains("timed out") ||
            msg.contains("timeout") ||
            msg.contains("relay unavailable") ||
            msg.contains("http 5") ||
            msg.contains("unusable response") ||
            msg.contains("couldn't generate")
    }
}
