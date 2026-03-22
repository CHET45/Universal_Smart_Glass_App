package com.fersaiyan.cyanbridge.ai.transcription

import android.content.Context

/**
 * Stores HTTP transcription endpoint configuration for manual POC.
 *
 * This is intentionally a simple SharedPreferences-backed config to avoid UI changes in Settings.
 */
object TranscriptionEndpointPrefs {
    private const val PREFS = "transcription_endpoint"

    private const val KEY_ENDPOINT_URL = "endpoint_url"
    private const val KEY_API_KEY = "api_key"

    fun getEndpointUrl(context: Context): String? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return p.getString(KEY_ENDPOINT_URL, null)
    }

    fun setEndpointUrl(context: Context, url: String?) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.edit().apply {
            if (url.isNullOrBlank()) remove(KEY_ENDPOINT_URL) else putString(KEY_ENDPOINT_URL, url.trim())
        }.apply()
    }

    fun getApiKey(context: Context): String? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return p.getString(KEY_API_KEY, null)
    }

    fun setApiKey(context: Context, apiKey: String?) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.edit().apply {
            if (apiKey.isNullOrBlank()) remove(KEY_API_KEY) else putString(KEY_API_KEY, apiKey)
        }.apply()
    }
}
