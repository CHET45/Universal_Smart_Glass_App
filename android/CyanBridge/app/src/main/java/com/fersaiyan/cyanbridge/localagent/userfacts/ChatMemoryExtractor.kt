package com.fersaiyan.cyanbridge.localagent.userfacts

import android.content.Context
import com.fersaiyan.cyanbridge.ai.router.AiAssistantRouter
import org.json.JSONArray
import org.json.JSONObject

object ChatMemoryExtractor {

    data class Extracted(
        val candidateUserFacts: List<String> = emptyList(),
        val candidateDailyFacts: List<String> = emptyList(),
    )

    suspend fun extract(
        context: Context,
        userMessage: String,
        assistantReply: String,
        date: String,
    ): Result<Extracted> {
        return runCatching {
            val prompt = buildPrompt(
                date = date,
                userMessage = userMessage,
                assistantReply = assistantReply,
            )

            val raw = AiAssistantRouter.textReply(context, prompt)
            val obj = JSONObject(extractJsonObject(raw))

            fun arr(key: String): List<String> {
                val a = obj.optJSONArray(key) ?: JSONArray()
                return (0 until a.length()).mapNotNull { i ->
                    a.optString(i)?.trim()?.takeIf { it.isNotBlank() }
                }
            }

            Extracted(
                candidateUserFacts = arr("candidate_user_facts"),
                candidateDailyFacts = arr("candidate_daily_facts"),
            )
        }
    }

    private fun buildPrompt(
        date: String,
        userMessage: String,
        assistantReply: String,
    ): String {
        return buildString {
            appendLine("You are a memory extraction module for a personal assistant app.")
            appendLine("Your job: extract CANDIDATE facts that were EXPLICITLY stated by the user.")
            appendLine()
            appendLine("OUTPUT FORMAT:")
            appendLine("- Return ONLY one JSON object. No markdown. No extra text.")
            appendLine("- Schema: {\"candidate_user_facts\": string[], \"candidate_daily_facts\": string[]}")
            appendLine()
            appendLine("RULES:")
            appendLine("- Only extract facts that the USER clearly stated. Do NOT infer.")
            appendLine("- Do NOT include secrets: passwords, tokens, API keys, private identifiers.")
            appendLine("- Keep each fact short (1 line).")
            appendLine("- candidate_user_facts = durable/stable facts about the user (preferences, identity, recurring projects).")
            appendLine("- candidate_daily_facts = events/tasks the user did TODAY ($date).")
            appendLine("- If none, return empty arrays.")
            appendLine()
            appendLine("TODAY: $date")
            appendLine("USER MESSAGE:")
            appendLine(userMessage.trim().take(2500))
            appendLine()
            appendLine("ASSISTANT REPLY (context only; do NOT extract facts from it):")
            appendLine(assistantReply.trim().take(1500))
        }
    }

    private fun extractJsonObject(text: String): String {
        val s = text.trim()
        if (s.startsWith("{") && s.endsWith("}")) return s

        // Strip ``` fences if present
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
