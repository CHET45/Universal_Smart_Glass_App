package com.fersaiyan.cyanbridge.localagent.userfacts

import android.content.Context
import com.fersaiyan.cyanbridge.localagent.memory.LocalAgentMemoryStore

object UserFactsStorage {

    fun normalizedFacts(context: Context): Set<String> {
        LocalAgentMemoryStore.ensureSeedFiles(context)
        val f = LocalAgentMemoryStore.userFactsFile(context)
        val cur = LocalAgentMemoryStore.readText(f)
        return parseExistingFacts(cur).toSet()
    }

    /**
     * Appends new facts to USER_FACTS.md without rewriting the whole file.
     * Keeps it simple and preserves manual edits.
     */
    fun appendUniqueFacts(context: Context, facts: List<String>) {
        val clean = facts.map { it.trim() }.filter { it.isNotBlank() }
        if (clean.isEmpty()) return

        LocalAgentMemoryStore.ensureSeedFiles(context)
        val f = LocalAgentMemoryStore.userFactsFile(context)

        val cur = LocalAgentMemoryStore.readText(f)
        val existing = parseExistingFacts(cur)

        val sb = StringBuilder(cur.ifBlank { "# User Facts\n\n" }.trimEnd())
        var added = 0

        for (fact in clean) {
            val key = normalize(fact)
            if (key.isBlank()) continue
            if (existing.contains(key)) continue

            // Avoid accidentally storing secrets / passwords.
            if (looksSensitive(fact)) continue

            sb.append("\n- ").append(fact.trim())
            existing.add(key)
            added++
        }

        if (added > 0) {
            sb.append("\n")
            LocalAgentMemoryStore.writeText(f, sb.toString())
        }
    }

    private fun parseExistingFacts(md: String): MutableSet<String> {
        val set = linkedSetOf<String>()
        md.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filterNot { it.startsWith("#") }
            .forEach { line ->
                val cleaned = line.removePrefix("- ").removePrefix("* ").trim()
                val key = normalize(cleaned)
                if (key.isNotBlank()) set.add(key)
            }
        return set
    }

    private fun normalize(s: String): String {
        return s.trim().lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun looksSensitive(s: String): Boolean {
        val t = s.lowercase()
        if (t.contains("password") || t.contains("passwd") || t.contains("token") || t.contains("apikey") || t.contains("api key")) return true
        // crude: long digit sequences often indicate codes/ids
        if (Regex("\\d{6,}").containsMatchIn(s)) return true
        return false
    }
}
