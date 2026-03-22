package com.fersaiyan.cyanbridge.localagent.userfacts

import android.content.Context
import com.fersaiyan.cyanbridge.localagent.memory.LocalAgentMemoryStore
import java.util.LinkedHashSet

object CandidateUserFactsStorage {

    fun load(context: Context, date: String): List<String> {
        LocalAgentMemoryStore.ensureSeedFiles(context)
        val f = LocalAgentMemoryStore.userFactsCandidatesFileForDate(context, date)
        return parseMarkdownBullets(LocalAgentMemoryStore.readText(f))
    }

    fun append(context: Context, date: String, facts: List<String>) {
        if (facts.isEmpty()) return
        LocalAgentMemoryStore.ensureSeedFiles(context)

        val existing = load(context, date)
        val merged = uniqueClean(existing + facts)

        val f = LocalAgentMemoryStore.userFactsCandidatesFileForDate(context, date)
        LocalAgentMemoryStore.writeText(
            f,
            "# Candidate user facts ($date)\n\n" + merged.joinToString("\n") { "- $it" } + "\n",
        )
    }

    fun remove(context: Context, date: String, facts: List<String>) {
        if (facts.isEmpty()) return
        LocalAgentMemoryStore.ensureSeedFiles(context)

        val current = load(context, date)
        val removeSet = uniqueClean(facts).map { it.lowercase() }.toSet()
        val remaining = current.filterNot { removeSet.contains(it.lowercase()) }

        val f = LocalAgentMemoryStore.userFactsCandidatesFileForDate(context, date)
        LocalAgentMemoryStore.writeText(
            f,
            "# Candidate user facts ($date)\n\n" + remaining.joinToString("\n") { "- $it" } + "\n",
        )
    }

    private fun parseMarkdownBullets(md: String): List<String> {
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
        val set = LinkedHashSet<String>()
        for (i in items) {
            val c = i.trim().removePrefix("- ").removePrefix("* ").trim()
            if (c.isNotBlank()) set.add(c)
        }
        return set.toList()
    }
}
