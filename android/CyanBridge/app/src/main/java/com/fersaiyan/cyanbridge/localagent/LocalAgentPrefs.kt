package com.fersaiyan.cyanbridge.localagent

import android.content.Context

object LocalAgentPrefs {
    private const val PREFS = "local_agent_prefs"

    private const val KEY_STATUS = "status"
    private const val KEY_LAST_ERROR = "last_error"

    // Debug: last context injection details (normal chat System prompt)
    private const val KEY_LAST_CONTEXT_INJECTION_DEBUG = "last_context_injection_debug"
    private const val KEY_LAST_CONTEXT_INJECTION_AT_MS = "last_context_injection_at_ms"

    // Unified action approval
    private const val KEY_REQUIRE_ACTION_CONFIRMATION = "require_action_confirmation"
    private const val KEY_AUTO_EXECUTE_LOW_RISK = "auto_execute_low_risk"

    fun isRequireActionConfirmationEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_REQUIRE_ACTION_CONFIRMATION, true)
    }

    fun setRequireActionConfirmationEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_REQUIRE_ACTION_CONFIRMATION, enabled)
            .apply()
    }

    fun isAutoExecuteLowRiskEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_EXECUTE_LOW_RISK, true)
    }

    fun setAutoExecuteLowRiskEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_EXECUTE_LOW_RISK, enabled)
            .apply()
    }

    fun getStatus(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_STATUS, "Unknown")
            ?: "Unknown"
    }

    fun setStatus(context: Context, status: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_STATUS, status)
            .apply()
    }

    fun getLastError(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_ERROR, "(none)")
            ?: "(none)"
    }

    fun setLastError(context: Context, error: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_ERROR, error)
            .apply()
    }

    fun clearLastError(context: Context) {
        setLastError(context, "(none)")
    }

    fun setLastContextInjectionDebug(context: Context, debugText: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_CONTEXT_INJECTION_DEBUG, debugText)
            .putLong(KEY_LAST_CONTEXT_INJECTION_AT_MS, System.currentTimeMillis())
            .apply()
    }

    fun getLastContextInjectionDebug(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_CONTEXT_INJECTION_DEBUG, "")
            .orEmpty()
    }

    fun getLastContextInjectionAtMs(context: Context): Long {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_CONTEXT_INJECTION_AT_MS, 0L)
    }
}
