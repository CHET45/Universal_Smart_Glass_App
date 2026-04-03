package com.fersaiyan.cyanbridge.localagent.dailyfacts

import org.json.JSONArray
import org.json.JSONObject
import java.util.LinkedHashSet

object DailyFactsReviewProtocol {

    enum class OutputMode {
        JSON,
        LINE_IDS,
    }

    data class ReviewBatchItem(
        val id: String,
        val text: String,
    )

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
        dailyFactsBatch: List<ReviewBatchItem>,
        userFactsBatch: List<ReviewBatchItem>,
        outputMode: OutputMode,
    ): String {
        return buildString {
            appendLine("You are a helpful, casual, playful assistant helping the user verify DAILY FACTS.")
            appendLine("Goal: confirm facts, correct mistakes, and keep a clean daily memory.")
            appendLine("You also review CANDIDATE USER FACTS collected from normal chats.")
            appendLine()
            appendLine("IMPORTANT OUTPUT FORMAT:")
            if (outputMode == OutputMode.LINE_IDS) {
                appendLine("- First write a natural assistant reply for the user.")
                appendLine("- Then include an <UPDATE> block with exact lines below.")
                appendLine("- Use ONLY IDs from current batches for confirm/reject.")
                appendLine("- Do NOT use JSON unless explicitly asked.")
                appendLine()
                appendLine("<UPDATE>")
                appendLine("CONFIRM_DAILY: D1,D2")
                appendLine("REJECT_DAILY: D3")
                appendLine("ADD_DAILY: text A | text B")
                appendLine("CONFIRM_USER: U1")
                appendLine("REJECT_USER: U2")
                appendLine("ADD_USER: text C")
                appendLine("</UPDATE>")
            } else {
                appendLine("- You MUST respond with a single JSON object and nothing else (no markdown, no code fences).")
                appendLine("- JSON schema:")
                appendLine("  {")
                appendLine("    \"assistant_message\": string,")
                appendLine("    \"confirmed_facts\": string[],   // IDs only: D1, D2, ...")
                appendLine("    \"rejected_facts\": string[],    // IDs only: D1, D2, ...")
                appendLine("    \"new_facts\": string[],")
                appendLine("    \"confirmed_user_facts\": string[],   // IDs only: U1, U2, ...")
                appendLine("    \"rejected_user_facts\": string[],    // IDs only: U1, U2, ...")
                appendLine("    \"new_user_facts_candidates\": string[]")
                appendLine("  }")
                appendLine("- For confirmed/rejected arrays, use only batch IDs (D#/U#), not full fact text.")
                appendLine("- Use text content only for new_facts and new_user_facts_candidates.")
            }
            appendLine()
            appendLine("RULES:")
            appendLine("- Ask about up to 3 items at a time (prefer exactly 3 when available).")
            appendLine("- Only confirm facts that the USER explicitly confirms.")
            if (outputMode == OutputMode.LINE_IDS) {
                appendLine("- If user confirms a daily fact, add its ID to CONFIRM_DAILY.")
                appendLine("- If a daily fact is wrong, add its ID to REJECT_DAILY.")
                appendLine("- If user mentions new daily facts, place them in ADD_DAILY.")
            } else {
                appendLine("- If user confirms a daily fact, include its ID (D#) in confirmed_facts.")
                appendLine("- If a daily fact is wrong, include its ID (D#) in rejected_facts.")
                appendLine("- If user mentions new daily facts, include them in new_facts.")
            }
            appendLine("- If user says a fact was only an ad / not their intention / about another person, reject original fact and add corrected fact.")
            appendLine("- For USER FACTS:")
            appendLine("  - Candidate user facts must be approved by the user before adding to USER_FACTS.md.")
            if (outputMode == OutputMode.LINE_IDS) {
                appendLine("  - If user approves candidate -> add ID to CONFIRM_USER.")
                appendLine("  - If user rejects candidate -> add ID to REJECT_USER.")
                appendLine("  - If user mentions new durable user fact -> ADD_USER.")
            } else {
                appendLine("  - If user approves candidate -> add U# to confirmed_user_facts.")
                appendLine("  - If user rejects candidate -> add U# to rejected_user_facts.")
                appendLine("  - If user mentions new durable user fact -> new_user_facts_candidates.")
            }
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
                dailyFactsBatch.forEach { item -> appendLine("${item.id}. ${item.text}") }
            }
            appendLine()
            appendLine("CURRENT REVIEW BATCH: USER FACT CANDIDATES")
            if (userFactsBatch.isEmpty()) {
                appendLine("(none)")
            } else {
                userFactsBatch.forEach { item -> appendLine("${item.id}. ${item.text}") }
            }
            appendLine()
            appendLine("CURRENT USER FACTS (reference only; do not rewrite blindly):")
            userFactsMd.trim().lineSequence().take(60).forEach { appendLine(it) }
        }
    }

    fun parseUpdate(raw: String): AiUpdate {
        parseLineProtocolUpdate(raw)?.let { return it }

        runCatching {
            val jsonText = extractJsonObject(raw)
            val obj = parseJsonObjectWithRepair(jsonText)
            aiUpdateFromJsonObject(obj)
        }.getOrNull()?.let { return it }

        parseBestEffortJsonish(raw)?.let { return it }

        throw IllegalArgumentException("Failed to parse JSON update")
    }

    private fun aiUpdateFromJsonObject(obj: JSONObject): AiUpdate {
        fun arr(key: String): List<String> {
            val a = obj.optJSONArray(key) ?: JSONArray()
            return (0 until a.length())
                .mapNotNull { i -> a.optString(i)?.trim()?.takeIf { it.isNotBlank() } }
                .distinct()
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

    private fun parseBestEffortJsonish(raw: String): AiUpdate? {
        val normalized = normalizeJsonishText(raw)
        val assistant = extractStringField(normalized, "assistant_message")

        val confirmedFacts = extractArrayField(normalized, "confirmed_facts")
        val rejectedFacts = extractArrayField(normalized, "rejected_facts")
        val newFacts = extractArrayField(normalized, "new_facts")
        val confirmedUserFacts = extractArrayField(normalized, "confirmed_user_facts")
        val rejectedUserFacts = extractArrayField(normalized, "rejected_user_facts")
        val newUserFactsCandidates = extractArrayField(normalized, "new_user_facts_candidates")

        val hasUpdates = confirmedFacts.isNotEmpty() || rejectedFacts.isNotEmpty() ||
            newFacts.isNotEmpty() || confirmedUserFacts.isNotEmpty() ||
            rejectedUserFacts.isNotEmpty() || newUserFactsCandidates.isNotEmpty()

        if (assistant.isBlank() && !hasUpdates) return null

        return AiUpdate(
            assistantMessage = assistant.ifBlank { "Got it. I updated your review queue." },
            confirmedFacts = confirmedFacts,
            rejectedFacts = rejectedFacts,
            newFacts = newFacts,
            confirmedUserFacts = confirmedUserFacts,
            rejectedUserFacts = rejectedUserFacts,
            newUserFactsCandidates = newUserFactsCandidates,
        )
    }

    private fun parseLineProtocolUpdate(raw: String): AiUpdate? {
        val block = extractUpdateBlock(raw) ?: return null
        val body = block.body

        val confirmDaily = parseCsvIds(readKeyValue(body, "CONFIRM_DAILY"), defaultPrefix = "D")
        val rejectDaily = parseCsvIds(readKeyValue(body, "REJECT_DAILY"), defaultPrefix = "D")
        val addDaily = parsePipeOrCsvTexts(readKeyValue(body, "ADD_DAILY"))
        val confirmUser = parseCsvIds(readKeyValue(body, "CONFIRM_USER"), defaultPrefix = "U")
        val rejectUser = parseCsvIds(readKeyValue(body, "REJECT_USER"), defaultPrefix = "U")
        val addUser = parsePipeOrCsvTexts(readKeyValue(body, "ADD_USER"))

        val assistant = block.message.trim()
            .ifBlank { "Got it. I updated your review queue." }

        val hasUpdates = confirmDaily.isNotEmpty() || rejectDaily.isNotEmpty() || addDaily.isNotEmpty() ||
            confirmUser.isNotEmpty() || rejectUser.isNotEmpty() || addUser.isNotEmpty()
        if (!hasUpdates && block.message.isBlank()) return null

        return AiUpdate(
            assistantMessage = assistant,
            confirmedFacts = confirmDaily,
            rejectedFacts = rejectDaily,
            newFacts = addDaily,
            confirmedUserFacts = confirmUser,
            rejectedUserFacts = rejectUser,
            newUserFactsCandidates = addUser,
        )
    }

    private data class UpdateBlock(
        val body: String,
        val message: String,
    )

    private fun extractUpdateBlock(raw: String): UpdateBlock? {
        val pattern = Regex("(?is)<UPDATE>(.*?)</UPDATE>")
        val match = pattern.find(raw) ?: return null
        val body = match.groupValues.getOrNull(1)?.trim().orEmpty()
        val message = raw.replaceRange(match.range, "").trim()
        return UpdateBlock(body = body, message = message)
    }

    private fun readKeyValue(body: String, key: String): String {
        val re = Regex("(?im)^\\s*${Regex.escape(key)}\\s*:\\s*(.*)$")
        val m = re.find(body) ?: return ""
        return m.groupValues.getOrNull(1)?.trim().orEmpty()
    }

    private fun parseCsvIds(raw: String, defaultPrefix: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return raw.split(',', ';', '|')
            .map { it.trim().uppercase() }
            .map { it.removePrefix("#") }
            .map { if (it.matches(Regex("^[0-9]{1,3}$"))) "$defaultPrefix$it" else it }
            .filter { it.matches(Regex("^[DU][0-9]{1,3}$")) }
            .distinct()
    }

    private fun parsePipeOrCsvTexts(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return raw.split('|', ';', ',')
            .map { it.trim() }
            .map { it.removePrefix("-").trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun extractJsonObject(text: String): String {
        val s = normalizeJsonishText(text).trim()
        if (s.startsWith("{") && s.endsWith("}")) return s

        if (s.startsWith("(") && s.endsWith(")")) {
            val inner = s.substring(1, s.length - 1).trim()
            if (inner.startsWith("{") && inner.endsWith("}")) return inner
            if (inner.contains("\"assistant_message\"") || inner.contains("assistant_message")) {
                return "{$inner}"
            }
        }

        if (!s.contains('{') && (s.contains("\"assistant_message\"") || s.contains("assistant_message"))) {
            return "{${s.trim().trimStart('(').trimEnd(')')}}"
        }

        // Strip ```json fences if present
        val fenceStart = s.indexOf("```")
        if (fenceStart >= 0) {
            val fenceEnd = s.indexOf("```", fenceStart + 3)
            if (fenceEnd > fenceStart) {
                val inner = s.substring(fenceStart + 3, fenceEnd)
                    .replaceFirst("json", "")
                    .trim()
                if (inner.startsWith("{") && inner.endsWith("}")) return inner
                if (inner.startsWith("(") && inner.endsWith(")")) {
                    val wrapped = inner.substring(1, inner.length - 1).trim()
                    if (wrapped.contains("assistant_message")) return "{$wrapped}"
                }
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
                val repaired = normalizeJsonishText(jsonText)
                    .replace(Regex("(?m)(^|[,{]\\s*)([A-Za-z_][A-Za-z0-9_]*)\\s*:"), "$1\"$2\":")
                    .replace(Regex("(?<!\\\\)'"), "\"")
                    .replace(Regex("([,\\[]\\s*)[A-Za-z]\\\""), "$1\"")
                    .replace(Regex(",\\s*([}\\]])"), "$1")
                JSONObject(repaired)
            }
            .getOrElse {
                throw IllegalArgumentException("Failed to parse JSON update")
            }
    }

    private fun normalizeJsonishText(raw: String): String {
        return raw
            .replace('\u201c', '"')
            .replace('\u201d', '"')
            .replace('\u2018', '\'')
            .replace('\u2019', '\'')
            .replace("[DEBUG_DAILY_REVIEWI", "[DEBUG_DAILY_REVIEW]")
    }

    private fun extractStringField(text: String, key: String): String {
        val start = indexAfterKey(text, key) ?: return ""
        var i = skipSpaces(text, start)
        if (i >= text.length) return ""

        if (text[i] == '"' || text[i] == '\'') {
            val parsed = parseQuotedString(text, i)
            return parsed.first.trim()
        }

        val end = indexOfNextKeyStart(text, i)
        return text.substring(i, end)
            .trim()
            .trim(',', '}', ']')
            .trim()
    }

    private fun extractArrayField(text: String, key: String): List<String> {
        val start = indexAfterKey(text, key) ?: return emptyList()
        var i = skipSpaces(text, start)
        if (i >= text.length) return emptyList()

        val rawSegment = if (text[i] == '[') {
            val bracket = extractBracketSegment(text, i)
            bracket ?: run {
                val end = indexOfNextKeyStart(text, i)
                text.substring(i, end)
            }
        } else {
            val end = indexOfNextKeyStart(text, i)
            text.substring(i, end)
        }

        return parseLooseArrayItems(rawSegment)
    }

    private fun parseLooseArrayItems(rawSegment: String): List<String> {
        val segment = rawSegment
            .trim()
            .removePrefix("[")
            .removeSuffix("]")
            .trim()
        if (segment.isBlank()) return emptyList()

        val collected = LinkedHashSet<String>()

        val quoted = Regex("\"((?:\\\\.|[^\"\\\\])*)\"|'((?:\\\\.|[^'\\\\])*)'")
            .findAll(segment)
            .mapNotNull { match ->
                val token = when {
                    match.groupValues[1].isNotBlank() -> match.groupValues[1]
                    else -> match.groupValues[2]
                }
                decodeEscapedToken(token).trim().takeIf { it.isNotBlank() }
            }
            .toList()
        quoted.forEach { collected += it }

        val ids = Regex("\\b([DU]\\s*\\d{1,3})\\b", RegexOption.IGNORE_CASE)
            .findAll(segment)
            .map { it.groupValues[1].replace(" ", "").uppercase() }
            .toList()
        ids.forEach { collected += it }

        if (collected.isNotEmpty()) return collected.toList()

        return segment
            .split('|', ';', '\n')
            .map { it.trim() }
            .map { it.removePrefix("-").trim().trim(',') }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun decodeEscapedToken(token: String): String {
        return token
            .replace("\\\"", "\"")
            .replace("\\n", "\n")
            .replace("\\t", "\t")
            .replace("\\r", "\r")
            .replace("\\/", "/")
            .replace("\\\\", "\\")
    }

    private fun indexAfterKey(text: String, key: String): Int? {
        val pattern = Regex("(?is)[\"']?${Regex.escape(key)}[\"']?\\s*:\\s*")
        val match = pattern.find(text) ?: return null
        return match.range.last + 1
    }

    private fun skipSpaces(text: String, from: Int): Int {
        var i = from
        while (i < text.length && text[i].isWhitespace()) i++
        return i
    }

    private fun parseQuotedString(text: String, startQuote: Int): Pair<String, Int> {
        if (startQuote !in text.indices) return "" to startQuote
        val quote = text[startQuote]
        if (quote != '"' && quote != '\'') return "" to startQuote

        val sb = StringBuilder()
        var i = startQuote + 1
        var escaped = false
        while (i < text.length) {
            val c = text[i]
            if (escaped) {
                sb.append(c)
                escaped = false
                i++
                continue
            }
            if (c == '\\') {
                escaped = true
                i++
                continue
            }
            if (c == quote) {
                return sb.toString() to (i + 1)
            }
            sb.append(c)
            i++
        }
        return sb.toString() to i
    }

    private fun extractBracketSegment(text: String, startBracket: Int): String? {
        if (startBracket !in text.indices || text[startBracket] != '[') return null
        var depth = 0
        var i = startBracket
        var inQuote = false
        var quoteChar = '\u0000'
        var escaped = false

        while (i < text.length) {
            val c = text[i]
            if (inQuote) {
                if (escaped) {
                    escaped = false
                } else if (c == '\\') {
                    escaped = true
                } else if (c == quoteChar) {
                    inQuote = false
                }
            } else {
                when (c) {
                    '"', '\'' -> {
                        inQuote = true
                        quoteChar = c
                    }
                    '[' -> depth++
                    ']' -> {
                        depth--
                        if (depth == 0) {
                            return text.substring(startBracket, i + 1)
                        }
                    }
                }
            }
            i++
        }
        return null
    }

    private fun indexOfNextKeyStart(text: String, from: Int): Int {
        val keyStart = Regex(
            "(?is),?\\s*[\"']?(assistant_message|confirmed_facts|rejected_facts|new_facts|confirmed_user_facts|rejected_user_facts|new_user_facts_candidates)[\"']?\\s*:",
        ).find(text, from)?.range?.first
        return keyStart ?: text.length
    }
}
