package com.fersaiyan.cyanbridge.localagent.dailyfacts

import org.json.JSONArray
import org.json.JSONObject

object DailyFactsReviewProtocol {

    data class AiUpdate(
        val assistantMessage: String,
        val confirmedFacts: List<String> = emptyList(),
        val rejectedFacts: List<String> = emptyList(),
        val newFacts: List<String> = emptyList(),
        val draftFacts: List<String>? = null,
        // User facts flow (reviewed here; written to USER_FACTS.md only when user confirms)
        val confirmedUserFacts: List<String> = emptyList(),
        val rejectedUserFacts: List<String> = emptyList(),
        val newUserFactsCandidates: List<String> = emptyList(),
    )

    fun buildSystemMessage(
        state: DailyFactsStorage.State,
        userFactsMd: String,
        candidateUserFacts: List<String>,
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
            appendLine("    \"draft_facts\": string[] | null,")
            appendLine("    \"confirmed_user_facts\": string[],")
            appendLine("    \"rejected_user_facts\": string[],")
            appendLine("    \"new_user_facts_candidates\": string[]")
            appendLine("  }")
            appendLine()
            appendLine("RULES:")
            appendLine("- Ask about at most 3 items at a time total (mix daily facts + user facts if you want).")
            appendLine("- Only confirm facts that the USER explicitly confirms.")
            appendLine("- If the user confirms a daily fact, include it in confirmed_facts.")
            appendLine("- If a daily fact is wrong, include it in rejected_facts.")
            appendLine("- If the user mentions new daily facts, include them in new_facts.")
            appendLine("- If you want to fully rewrite the draft list, set draft_facts (else null).")
            appendLine("- For USER FACTS:")
            appendLine("  - Candidate user facts must be approved by the user before adding to USER_FACTS.md.")
            appendLine("  - If user approves a candidate user fact -> confirmed_user_facts.")
            appendLine("  - If user rejects a candidate user fact -> rejected_user_facts.")
            appendLine("  - If user mentions a new durable fact about themselves -> new_user_facts_candidates.")
            appendLine("- assistant_message must be friendly and include the next 1–3 questions.")
            appendLine()
            appendLine("DATE: ${state.date}")
            appendLine()
            appendLine("CURRENT DRAFT DAILY FACTS (unconfirmed):")
            state.draft.take(50).forEachIndexed { idx, f -> appendLine("${idx + 1}. $f") }
            appendLine()
            appendLine("ALREADY CONFIRMED DAILY FACTS:")
            state.confirmed.take(50).forEachIndexed { idx, f -> appendLine("${idx + 1}. $f") }
            appendLine()
            appendLine("CURRENT USER FACTS (reference only; do not rewrite blindly):")
            userFactsMd.trim().lineSequence().take(80).forEach { appendLine(it) }
            appendLine()
            appendLine("CANDIDATE USER FACTS TO REVIEW (from today's chats):")
            if (candidateUserFacts.isEmpty()) {
                appendLine("(none)")
            } else {
                candidateUserFacts.take(50).forEachIndexed { idx, f -> appendLine("${idx + 1}. $f") }
            }
        }
    }

    fun parseUpdate(raw: String): AiUpdate {
        val jsonText = extractJsonObject(raw)
        val obj = JSONObject(jsonText)

        fun arr(key: String): List<String> {
            val a = obj.optJSONArray(key) ?: JSONArray()
            return (0 until a.length()).mapNotNull { i -> a.optString(i)?.trim()?.takeIf { it.isNotBlank() } }
        }

        val assistant = obj.optString("assistant_message", "").trim()
        if (assistant.isBlank()) {
            throw IllegalArgumentException("Missing assistant_message")
        }

        val draftFacts = obj.optJSONArray("draft_facts")?.let { a ->
            (0 until a.length()).mapNotNull { i -> a.optString(i)?.trim()?.takeIf { it.isNotBlank() } }
        }

        return AiUpdate(
            assistantMessage = assistant,
            confirmedFacts = arr("confirmed_facts"),
            rejectedFacts = arr("rejected_facts"),
            newFacts = arr("new_facts"),
            draftFacts = draftFacts,
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
}
