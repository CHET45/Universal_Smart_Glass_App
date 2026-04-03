package com.fersaiyan.cyanbridge.localagent.dailyfacts

import android.content.Context
import com.fersaiyan.cyanbridge.localagent.memory.LocalAgentMemoryStore
import java.util.LinkedHashSet

object DailyFactsStorage {

    data class State(
        val date: String,
        val draft: List<String>,
        val confirmed: List<String>,
    )

    fun load(context: Context, date: String): State {
        LocalAgentMemoryStore.ensureSeedFiles(context)

        val draftFile = LocalAgentMemoryStore.dailyFactsFileForDate(context, date)
        val confirmedFile = LocalAgentMemoryStore.confirmedDailyFactsFileForDate(context, date)

        val draftFacts = parseMarkdownFacts(LocalAgentMemoryStore.readText(draftFile))
        val confirmedFacts = parseMarkdownFacts(LocalAgentMemoryStore.readText(confirmedFile))

        return State(date = date, draft = draftFacts, confirmed = confirmedFacts)
    }

    fun writeDraft(context: Context, date: String, facts: List<String>) {
        val uniq = uniqueClean(facts)
        val f = LocalAgentMemoryStore.dailyFactsFileForDate(context, date)
        LocalAgentMemoryStore.writeText(
            f,
            "# Daily facts ($date)\n\n" + uniq.joinToString("\n") { "- $it" } + "\n",
        )
    }

    fun appendDraft(context: Context, date: String, facts: List<String>) {
        if (facts.isEmpty()) return
        val current = parseMarkdownFacts(LocalAgentMemoryStore.readText(LocalAgentMemoryStore.dailyFactsFileForDate(context, date)))
        writeDraft(context, date, current + facts)
    }

    fun appendConfirmed(context: Context, date: String, facts: List<String>) {
        val existing = parseMarkdownFacts(LocalAgentMemoryStore.readText(LocalAgentMemoryStore.confirmedDailyFactsFileForDate(context, date)))
        val merged = uniqueClean(existing + facts)
        val f = LocalAgentMemoryStore.confirmedDailyFactsFileForDate(context, date)
        LocalAgentMemoryStore.writeText(
            f,
            "# Confirmed daily facts ($date)\n\n" + merged.joinToString("\n") { "- $it" } + "\n",
        )
    }

    fun removeFromDraft(context: Context, date: String, facts: List<String>) {
        val current = parseMarkdownFacts(LocalAgentMemoryStore.readText(LocalAgentMemoryStore.dailyFactsFileForDate(context, date)))
        val removeSet = uniqueClean(facts)
            .map { normalizeFactForMatching(it) }
            .filter { it.isNotBlank() }
            .toSet()
        val remaining = current.filterNot { removeSet.contains(normalizeFactForMatching(it)) }
        writeDraft(context, date, remaining)
    }

    private fun parseMarkdownFacts(md: String): List<String> {
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

    private fun uniqueClean(items: List<String>): List<String> {
        val byNorm = LinkedHashSet<String>()
        val out = ArrayList<String>()
        for (i in items) {
            val c = i.trim().removePrefix("- ").removePrefix("* ").trim()
            if (c.isBlank()) continue
            val norm = normalizeFactForMatching(c)
            if (norm.isBlank()) continue
            if (byNorm.add(norm)) out.add(c)
        }
        return out
    }

    private fun normalizeFactForMatching(value: String): String {
        return value
            .lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(260)
    }
}
