package com.fersaiyan.cyanbridge.localagent.userfacts

import android.content.Context

object ChatMemoryPrefs {
    private const val PREFS = "chat_memory_prefs"

    private const val KEY_AUTO_SAVE_DAILY_FACTS = "auto_save_daily_facts"
    private const val KEY_EXTRACT_USER_FACT_CANDIDATES = "extract_user_fact_candidates"
    private const val KEY_LAST_EXTRACT_AT_MS = "last_extract_at_ms"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isAutoSaveDailyFactsEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_AUTO_SAVE_DAILY_FACTS, true)
    }

    fun setAutoSaveDailyFactsEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_SAVE_DAILY_FACTS, enabled).apply()
    }

    fun isExtractUserFactCandidatesEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_EXTRACT_USER_FACT_CANDIDATES, true)
    }

    fun setExtractUserFactCandidatesEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_EXTRACT_USER_FACT_CANDIDATES, enabled).apply()
    }

    fun getLastExtractAtMs(context: Context): Long {
        return prefs(context).getLong(KEY_LAST_EXTRACT_AT_MS, 0L)
    }

    fun setLastExtractAtMs(context: Context, tsMs: Long) {
        prefs(context).edit().putLong(KEY_LAST_EXTRACT_AT_MS, tsMs).apply()
    }
}
