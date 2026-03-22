package com.fersaiyan.cyanbridge.ai.router

import android.content.Context
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

data class RelayServerCapabilities(
    val raw: JSONObject,
    val chat: Boolean,
    val voiceQuery: Boolean,
    val imageQuery: Boolean,
    val openAiChatCompletions: Boolean,
    val subscriptionVerify: Boolean,
    val transcriptionHttp: Boolean,
)

object RelayServerCapabilitiesClient {
    private const val CONNECT_TIMEOUT_MS = 5000
    private const val READ_TIMEOUT_MS = 10000
    private const val CACHE_TTL_MS = 60_000L

    private var cachedAtMs: Long = 0L
    private var cachedBaseUrl: String = ""
    private var cachedCapabilities: RelayServerCapabilities? = null

    @Synchronized
    fun get(context: Context, forceRefresh: Boolean = false): Result<RelayServerCapabilities> {
        val baseUrl = AiProviderPrefs.getRelayBaseUrl(context).trim().trimEnd('/')
        if (baseUrl.isBlank()) {
            return Result.failure(IllegalStateException("Relay base URL is blank"))
        }
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            return Result.failure(IllegalStateException("Relay base URL must start with http:// or https://"))
        }

        val now = System.currentTimeMillis()
        if (!forceRefresh && cachedCapabilities != null && cachedBaseUrl == baseUrl && now - cachedAtMs <= CACHE_TTL_MS) {
            return Result.success(cachedCapabilities!!)
        }

        return runCatching {
            val url = "$baseUrl/capabilities"
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = BufferedReader(InputStreamReader(stream ?: conn.inputStream)).use { it.readText() }
            conn.disconnect()
            if (code !in 200..299) {
                throw IllegalStateException("Capabilities HTTP $code: $body")
            }
            parseCapabilities(JSONObject(body)).also { parsed ->
                cachedBaseUrl = baseUrl
                cachedAtMs = now
                cachedCapabilities = parsed
            }
        }
    }

    @Synchronized
    fun clearCache() {
        cachedCapabilities = null
        cachedAtMs = 0L
        cachedBaseUrl = ""
    }

    private fun parseCapabilities(payload: JSONObject): RelayServerCapabilities {
        val caps = payload.optJSONObject("capabilities") ?: payload
        return RelayServerCapabilities(
            raw = payload,
            chat = caps.optBoolean("chat", true),
            voiceQuery = caps.optBoolean("voice_query", caps.optBoolean("chat", true)),
            imageQuery = caps.optBoolean("image_query", true),
            openAiChatCompletions = caps.optBoolean("openai_chat_completions", true),
            subscriptionVerify = caps.optBoolean("subscription_verify", false),
            transcriptionHttp = caps.optBoolean("transcription_http", false),
        )
    }
}
