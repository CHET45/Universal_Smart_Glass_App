package com.fersaiyan.cyanbridge.localagent

import org.json.JSONArray
import org.json.JSONObject

object LocalAgentActionParser {

    fun parseList(json: String?): List<LocalAgentAction> {
        if (json.isNullOrBlank()) return emptyList()

        val trimmed = json.trim()
        return try {
            when {
                trimmed.startsWith("[") -> parseArray(JSONArray(trimmed))
                trimmed.startsWith("{") -> listOfNotNull(parseOne(JSONObject(trimmed)))
                else -> emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseArray(arr: JSONArray): List<LocalAgentAction> {
        val out = ArrayList<LocalAgentAction>(arr.length())
        for (i in 0 until arr.length()) {
            val v = arr.opt(i) ?: continue
            val obj = when (v) {
                is JSONObject -> v
                is String -> runCatching { JSONObject(v) }.getOrNull()
                else -> null
            } ?: continue

            parseOne(obj)?.let(out::add)
        }
        return out
    }

    private fun parseOne(obj: JSONObject): LocalAgentAction? {
        val type = obj.optString("type", "").trim().lowercase()
        return when (type) {
            "sleep" -> LocalAgentAction.Sleep(ms = obj.optLong("ms", 0L).coerceAtLeast(0L))
            "global_back", "back" -> LocalAgentAction.GlobalBack
            "global_home", "home" -> LocalAgentAction.GlobalHome
            "click_text" -> {
                val text = obj.optString("text", "")
                if (text.isBlank()) null else LocalAgentAction.ClickText(text)
            }
            "type_text" -> {
                val text = obj.optString("text", "")
                if (text.isBlank()) null else LocalAgentAction.TypeText(text)
            }
            "send_email", "email" -> {
                LocalAgentAction.SendEmail(
                    to = obj.optString("to", ""),
                    subject = obj.optString("subject", ""),
                    body = obj.optString("body", "")
                )
            }
            else -> null
        }
    }
}
