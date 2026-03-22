package com.fersaiyan.cyanbridge.memoryvault

import android.content.Context
import com.fersaiyan.cyanbridge.localagent.dailyfacts.DailyFactsStorage
import com.fersaiyan.cyanbridge.localagent.memory.LocalAgentMemoryRoomIndex
import com.fersaiyan.cyanbridge.localagent.memory.LocalAgentMemoryStore
import java.util.Calendar
import java.util.Locale

object MemorySearchOrchestrator {
    data class SearchParams(
        val lookbackDaysFacts: Int,
        val topFacts: Int,
        val topSummaryLines: Int,
        val topScreenHits: Int,
        val maxChars: Int,
    )

    private data class Candidate(
        val raw: String,
        val display: String,
        val section: Section,
        val date: String,
        val memoryRef: String,
    )

    private enum class Section {
        FACT,
        SUMMARY,
        SCREEN_OCR,
    }

    suspend fun buildRelevantMemoryBlock(
        context: Context,
        queryText: String,
        date: String,
        params: SearchParams,
    ): String {
        val tokens = tokenizeQuery(queryText)
        if (tokens.isEmpty()) return ""

        val mode = MemoryModeManager.getSelectedMode(context)

        val factCandidates = buildFactCandidates(context, date, params.lookbackDaysFacts)
            .filter { MemoryPolicyService.isEligibleForRetrieval(mode, resolvePolicy(context, it.memoryRef)) }
        val summaryCandidates = buildSummaryCandidates(context, date)
            .filter { MemoryPolicyService.isEligibleForRetrieval(mode, resolvePolicy(context, it.memoryRef)) }

        val screenHits = LocalAgentMemoryRoomIndex.searchScreenCaptures(
            query = queryText,
            limit = params.topScreenHits,
            context = context,
        ).mapNotNull { hit ->
            val line = hit.snippet.trim().replace("[", "").replace("]", "").ifBlank { hit.text.trim() }
            if (line.isBlank()) return@mapNotNull null
            Candidate(
                raw = line,
                display = "(${formatTime(hit.tsMs)}) ${hit.packageName.orEmpty()}: ${line.take(220)}",
                section = Section.SCREEN_OCR,
                date = formatDate(hit.tsMs),
                memoryRef = MemoryRefMapper.forMemoryChunk(hit.id),
            )
        }

        val factHits = scoreAndSelect(factCandidates, tokens, params.topFacts)
        val summaryHits = scoreAndSelect(summaryCandidates, tokens, params.topSummaryLines)
        val screenSelected = scoreAndSelect(screenHits, tokens, params.topScreenHits)

        if (factHits.isEmpty() && summaryHits.isEmpty() && screenSelected.isEmpty()) return ""

        val block = buildString {
            appendLine("## Relevant memory")

            if (factHits.isNotEmpty()) {
                appendLine("### Confirmed daily facts")
                factHits.forEach { appendLine("- ${it.display}") }
                appendLine()
            }

            if (summaryHits.isNotEmpty()) {
                appendLine("### Daily summary")
                summaryHits.forEach { appendLine("- ${it.display}") }
                appendLine()
            }

            if (screenSelected.isNotEmpty()) {
                appendLine("### Screen OCR")
                screenSelected.forEach { appendLine("- ${it.display}") }
            }
        }.trim()

        return if (block.length <= params.maxChars) block else block.take(params.maxChars).trimEnd() + "..."
    }

    private fun resolvePolicy(context: Context, memoryRef: String): MemoryPolicyMetadata {
        return MemoryPolicyService.getPolicyBlocking(memoryRef)
            ?: MemoryPolicyService.classifyForMemoryRef(context, memoryRef = memoryRef, text = "")
    }

    private fun buildFactCandidates(context: Context, date: String, lookbackDays: Int): List<Candidate> {
        val days = dayStringsIncludingLookback(date, lookbackDays)
        val out = ArrayList<Candidate>(days.size * 8)
        for (d in days) {
            val facts = runCatching { DailyFactsStorage.load(context, d).confirmed }.getOrDefault(emptyList())
            val ref = MemoryRefMapper.forFile(context, LocalAgentMemoryStore.confirmedDailyFactsFileForDate(context, d))
            for (f in facts) {
                val clean = f.trim()
                if (clean.isBlank()) continue
                val display = if (d == date) clean else "($d) $clean"
                out.add(
                    Candidate(
                        raw = clean,
                        display = display,
                        section = Section.FACT,
                        date = d,
                        memoryRef = ref,
                    )
                )
            }
        }
        return out
    }

    private fun buildSummaryCandidates(context: Context, date: String): List<Candidate> {
        val file = LocalAgentMemoryStore.dailySummaryFileForDate(context, date)
        val text = LocalAgentMemoryStore.readText(file)
        if (text.isBlank()) return emptyList()

        val lines = parseMarkdownLines(text)
        val ref = MemoryRefMapper.forFile(context, file)
        return lines.map { line ->
            Candidate(
                raw = line,
                display = line,
                section = Section.SUMMARY,
                date = date,
                memoryRef = ref,
            )
        }
    }

    private fun scoreAndSelect(candidates: List<Candidate>, tokens: List<String>, topK: Int): List<Candidate> {
        if (candidates.isEmpty()) return emptyList()
        val queryVector = LocalEmbeddingService.embed(tokens.joinToString(" "))

        val scored = candidates.mapNotNull { c ->
            val hay = c.raw.lowercase(Locale.US)
            var lexical = 0
            for (t in tokens) {
                if (hay.contains(t)) lexical += 1
            }
            val semantic = LocalEmbeddingService.cosine(LocalEmbeddingService.embed(c.raw), queryVector)
            val score = lexical.toDouble() + semantic.toDouble()
            if (score > 0.0) c to score else null
        }

        return scored
            .sortedWith(
                compareByDescending<Pair<Candidate, Double>> { it.second }
                    .thenByDescending { it.first.date }
                    .thenBy { it.first.section.name }
                    .thenBy { it.first.display.length }
            )
            .map { it.first }
            .distinctBy { it.display.lowercase(Locale.US) }
            .take(topK)
    }

    private fun tokenizeQuery(text: String): List<String> {
        val parts = text
            .lowercase(Locale.US)
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .map { it.trim() }
            .filter { it.length >= 3 }
        return parts.filterNot { it in STOPWORDS }.distinct()
    }

    private fun parseMarkdownLines(md: String): List<String> {
        return md.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filterNot { it.startsWith("#") }
            .map {
                when {
                    it.startsWith("- ") -> it.removePrefix("- ").trim()
                    it.startsWith("* ") -> it.removePrefix("* ").trim()
                    else -> it
                }
            }
            .filter { it.isNotBlank() && it != "-" }
            .toList()
    }

    private fun dayStringsIncludingLookback(date: String, lookbackDays: Int): List<String> {
        val clamped = lookbackDays.coerceIn(1, 31)
        val cal = Calendar.getInstance().apply {
            val parts = date.split("-")
            if (parts.size == 3) {
                val y = parts[0].toIntOrNull() ?: get(Calendar.YEAR)
                val m = (parts[1].toIntOrNull() ?: (get(Calendar.MONTH) + 1)) - 1
                val d = parts[2].toIntOrNull() ?: get(Calendar.DAY_OF_MONTH)
                set(Calendar.YEAR, y)
                set(Calendar.MONTH, m)
                set(Calendar.DAY_OF_MONTH, d)
            }
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val out = ArrayList<String>(clamped)
        for (i in 0 until clamped) {
            out.add(fmt.format(cal.time))
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        return out
    }

    private fun formatDate(tsMs: Long): String {
        return java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date(tsMs))
    }

    private fun formatTime(tsMs: Long): String {
        return java.text.SimpleDateFormat("HH:mm", Locale.US).format(java.util.Date(tsMs))
    }

    private val STOPWORDS: Set<String> = setOf(
        "the", "and", "for", "with", "that", "this", "from", "into", "what", "when",
        "how", "who", "why", "are", "was", "were", "can", "could", "should", "would",
        "will", "just", "like", "your", "you", "about", "have", "has", "had", "then",
        "que", "para", "com", "uma", "nao", "nao", "isso", "essa", "esse", "foi", "tem",
        "tinha", "como", "porque", "por", "das", "dos", "uns", "umas"
    )
}
