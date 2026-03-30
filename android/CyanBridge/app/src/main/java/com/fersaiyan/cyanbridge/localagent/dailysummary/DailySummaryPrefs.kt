package com.fersaiyan.cyanbridge.localagent.dailysummary

import android.content.Context

object DailySummaryPrefs {
    private const val PREFS_NAME = "daily_summary_prefs"
    private const val KEY_LAST_GENERATED_PREFIX = "last_generated_ts_"
    private const val KEY_LAST_CAPTURE_PROCESSED_PREFIX = "last_capture_processed_ts_"

    /**
     * Simple regen rate-limit to avoid accidental hammering of the relay endpoint.
     */
    const val MIN_REGENERATE_INTERVAL_MS: Long = 2 * 60 * 1000L

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getLastGeneratedAtMs(context: Context, date: String): Long {
        return prefs(context).getLong(KEY_LAST_GENERATED_PREFIX + date.trim(), 0L)
    }

    fun setLastGeneratedAtMs(context: Context, date: String, tsMs: Long) {
        prefs(context).edit()
            .putLong(KEY_LAST_GENERATED_PREFIX + date.trim(), tsMs)
            .putLong(KEY_LAST_CAPTURE_PROCESSED_PREFIX + date.trim(), tsMs)
            .apply()
    }

    /**
     * Returns the timestamp up to which captures have been processed for this date.
     * Used for incremental summarization - only captures AFTER this timestamp are fed to the model.
     */
    fun getLastCaptureProcessedAtMs(context: Context, date: String): Long {
        return prefs(context).getLong(KEY_LAST_CAPTURE_PROCESSED_PREFIX + date.trim(), 0L)
    }

    /**
     * Sets the capture processing watermark for incremental updates.
     * Normally set automatically when summary is generated.
     */
    fun setLastCaptureProcessedAtMs(context: Context, date: String, tsMs: Long) {
        prefs(context).edit().putLong(KEY_LAST_CAPTURE_PROCESSED_PREFIX + date.trim(), tsMs).apply()
    }

    fun remainingCooldownMs(context: Context, date: String, nowMs: Long = System.currentTimeMillis()): Long {
        val last = getLastGeneratedAtMs(context, date)
        val elapsed = nowMs - last
        return (MIN_REGENERATE_INTERVAL_MS - elapsed).coerceAtLeast(0L)
    }
}
