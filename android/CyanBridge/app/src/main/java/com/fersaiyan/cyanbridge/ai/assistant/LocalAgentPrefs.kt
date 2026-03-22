package com.fersaiyan.cyanbridge.ai.assistant

import android.content.Context

/**
 * Minimal SharedPreferences-backed config for the on-device Local Agent.
 *
 * Intentionally simple to reduce merge conflicts while we iterate on the on-device runtime.
 */
object LocalAgentPrefs {
    private const val PREFS = "local_agent_prefs"

    private const val KEY_ENABLED = "enabled"
    private const val KEY_MODEL_ID = "model_id"

    fun isEnabled(context: Context): Boolean {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return p.getBoolean(KEY_ENABLED, false)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /**
     * Optional identifier for the on-device model (e.g., a GGUF filename or a friendly alias).
     */
    fun getModelId(context: Context): String? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return p.getString(KEY_MODEL_ID, null)
    }

    fun setModelId(context: Context, modelId: String?) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.edit().apply {
            if (modelId.isNullOrBlank()) remove(KEY_MODEL_ID) else putString(KEY_MODEL_ID, modelId.trim())
        }.apply()
    }
}
