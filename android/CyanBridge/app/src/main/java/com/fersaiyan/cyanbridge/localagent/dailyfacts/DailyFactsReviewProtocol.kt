package com.fersaiyan.cyanbridge.localagent.dailyfacts

import org.json.JSONArray
import org.json.JSONObject

object DailyFactsReviewProtocol {

    data class AiUpdate(
        val assistantMessage: String,
        val confirmedFacts: List<String> = emptyList(),
        val rejectedFacts: List<String> = emptyList(),
        val newFacts: List<String> = emptyList(),
        // User facts flow (reviewed here; written to USER_FACTS.md only when user confirms)
        val confirmedUserFacts: List<String> = emptyList(),
        val rejectedUserFacts: List<String> = emptyList(),
        val newUserFactsCandidates: List<String> = emptyList(),
    )

    fun buildSystemMessage(
        state: DailyFactsStorage.State,
        userFactsMd: String,
        candidateUserFacts: List<String>,
        dailyFactsBatch: List<String>,
        userFactsBatch: List<String>,
    ): String {
        return buildString {
            appendLine("You are a helpful, casual, playful assistant helping the user verify DAILY FACTS.")
            appendLine("Goal: confirm facts, correct mistakes, and keep a clean daily memory.")
            appendLine("You also review CANDIDATE USER FACTS collected from normal chats.")
            appendLine()
            appendLine("IMPORTANT OUTPUT FORMAT:")
            appendLine("- You MUST respond with a single JSON object and nothing else (no markdown, no code fences).")
            appendLine("- JSON schema:")
            appendLine("  {")
            appendLine("    \"assistant_message\": string,")
            appendLine("    \"confirmed_facts\": string[],")
            appendLine("    \"rejected_facts\": string[],")
            appendLine("    \"new_facts\": string[],")
            appendLine("    \"confirmed_user_facts\": string[],")
            appendLine("    \"rejected_user_facts\": string[],")
            appendLine("    \"new_user_facts_candidates\": string[]")
            appendLine("  }")
            appendLine()
            appendLine("RULES:")
            appendLine("- Ask about 3 to 5 items at a time (never more than 5).")
            appendLine("- Only confirm facts that the USER explicitly confirms.")
            appendLine("- If the user confirms a daily fact, include it in confirmed_facts.")
            appendLine("- If a daily fact is wrong, include it in rejected_facts.")
            appendLine("- If the user mentions new daily facts, include them in new_facts.")
            appendLine("- If user says a fact was only an ad / not their intention / about another person, reject original fact and add corrected fact.")
            appendLine("- For USER FACTS:")
            appendLine("  - Candidate user facts must be approved by the user before adding to USER_FACTS.md.")
            appendLine("  - If user approves a candidate user fact -> confirmed_user_facts.")
            appendLine("  - If user rejects a candidate user fact -> rejected_user_facts.")
            appendLine("  - If user mentions a new durable fact about themselves -> new_user_facts_candidates.")
            appendLine("  - If fact is actually about someone else (mother/friend/company), do NOT store it as a user fact.")
            appendLine("- assistant_message must be friendly and include the next 1–3 questions.")
            appendLine()
            appendLine("DATE: ${state.date}")
            appendLine()
            appendLine("QUEUE STATUS:")
            appendLine("- pending_daily_facts_total: ${state.draft.size}")
            appendLine("- pending_user_fact_candidates_total: ${candidateUserFacts.size}")
            appendLine("- confirmed_daily_facts_total: ${state.confirmed.size}")
            appendLine()
            appendLine("CURRENT REVIEW BATCH: DAILY FACTS")
            if (dailyFactsBatch.isEmpty()) {
                appendLine("(none)")
            } else {
                dailyFactsBatch.forEachIndexed { idx, f -> appendLine("${idx + 1}. $f") }
            }
            appendLine()
            appendLine("CURRENT REVIEW BATCH: USER FACT CANDIDATES")
            if (userFactsBatch.isEmpty()) {
                appendLine("(none)")
            } else {
                userFactsBatch.forEachIndexed { idx, f -> appendLine("${idx + 1}. $f") }
            }
            appendLine()
            appendLine("CURRENT USER FACTS (reference only; do not rewrite blindly):")
            userFactsMd.trim().lineSequence().take(60).forEach { appendLine(it) }
        }
    }

    fun parseUpdate(raw: String): AiUpdate {
        val jsonText = extractJsonObject(raw)
        val obj = parseJsonObjectWithRepair(jsonText)

        fun arr(key: String): List<String> {
            val a = obj.optJSONArray(key) ?: JSONArray()
            return (0 until a.length()).mapNotNull { i -> a.optString(i)?.trim()?.takeIf { it.isNotBlank() } }
        }

        val assistant = obj.optString("assistant_message", "").trim()
        if (assistant.isBlank()) {
            throw IllegalArgumentException("Missing assistant_message")
        }

        return AiUpdate(
            assistantMessage = assistant,
            confirmedFacts = arr("confirmed_facts"),
            rejectedFacts = arr("rejected_facts"),
            newFacts = arr("new_facts"),
            confirmedUserFacts = arr("confirmed_user_facts"),
            rejectedUserFacts = arr("rejected_user_facts"),
            newUserFactsCandidates = arr("new_user_facts_candidates"),
        )
    }

    private fun extractJsonObject(text: String): String {
        val s = text.trim()
        if (s.startsWith("{") && s.endsWith("}")) return s

        // Strip ```json fences if present
        val fenceStart = s.indexOf("```")
        if (fenceStart >= 0) {
            val fenceEnd = s.indexOf("```", fenceStart + 3)
            if (fenceEnd > fenceStart) {
                val inner = s.substring(fenceStart + 3, fenceEnd)
                    .replaceFirst("json", "")
                    .trim()
                if (inner.startsWith("{") && inner.endsWith("}")) return inner
            }
        }

        // Balanced brace scan
        var depth = 0
        var start = -1
        for (i in s.indices) {
            val c = s[i]
            if (c == '{') {
                if (depth == 0) start = i
                depth++
            } else if (c == '}') {
                if (depth > 0) depth--
                if (depth == 0 && start >= 0) {
                    return s.substring(start, i + 1)
                }
            }
        }

        throw IllegalArgumentException("Could not extract JSON object")
    }

    private fun parseJsonObjectWithRepair(jsonText: String): JSONObject {
        return runCatching { JSONObject(jsonText) }
            .recoverCatching {
                val repaired = jsonText
                    .replace(Regex("(?m)(^|[,{]\\s*)([A-Za-z_][A-Za-z0-9_]*)\\s*:"), "$1\"$2\":")
                    .replace(Regex("(?<!\\\\)'"), "\"")
                    .replace(Regex(",\\s*([}\\]])"), "$1")
                JSONObject(repaired)
            }
            .getOrElse {
                throw IllegalArgumentException("Failed to parse JSON update")
            }
    }
}
