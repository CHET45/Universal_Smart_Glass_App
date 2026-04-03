package com.fersaiyan.cyanbridge.media.autocapture

import android.content.Context

object AutoAudioCapturePrefs {
    private const val PREFS = "auto_audio_capture_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_SUCCESSFUL_LOOPS = "successful_loops"
    private const val KEY_LOOPS_PER_SYNC = "loops_per_sync"
    private const val KEY_VISUAL_NOTES_ENABLED = "visual_notes_enabled"
    private const val KEY_SPEECH_EXTEND_ENABLED = "speech_extend_enabled"

    private const val KEY_PAUSED_MEETING = "paused_meeting"
    private const val KEY_PAUSED_VIDEO = "paused_video"
    private const val KEY_PAUSE_UNTIL_MS = "pause_until_ms"
    private const val KEY_LAST_PAUSE_REASON = "last_pause_reason"
    fun isEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }

    fun getSuccessfulLoops(context: Context): Int {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_SUCCESSFUL_LOOPS, 0)
            .coerceAtLeast(0)
    }

    fun incrementSuccessfulLoops(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val next = (prefs.getInt(KEY_SUCCESSFUL_LOOPS, 0) + 1).coerceAtLeast(0)
        prefs.edit().putInt(KEY_SUCCESSFUL_LOOPS, next).apply()
        return next
    }

    fun resetSuccessfulLoops(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_SUCCESSFUL_LOOPS, 0)
            .apply()
    }

    fun getLoopsPerSync(context: Context): Int {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_LOOPS_PER_SYNC, 12)
        return raw.coerceIn(1, 96)
    }

    fun setLoopsPerSync(context: Context, loops: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_LOOPS_PER_SYNC, loops.coerceIn(1, 96))
            .apply()
    }

    fun isVisualNotesEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_VISUAL_NOTES_ENABLED, false)
    }

    fun setVisualNotesEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_VISUAL_NOTES_ENABLED, enabled)
            .apply()
    }

    fun isSpeechExtendEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SPEECH_EXTEND_ENABLED, true)
    }

    fun setSpeechExtendEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SPEECH_EXTEND_ENABLED, enabled)
            .apply()
    }

    fun isPausedForMeeting(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_PAUSED_MEETING, false)
    }

    fun setPausedForMeeting(context: Context, paused: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PAUSED_MEETING, paused)
            .apply()
    }

    fun isPausedForVideo(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_PAUSED_VIDEO, false)
    }

    fun setPausedForVideo(context: Context, paused: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PAUSED_VIDEO, paused)
            .apply()
    }

    fun getPauseUntilMs(context: Context): Long {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_PAUSE_UNTIL_MS, 0L)
    }

    fun pauseForMs(context: Context, durationMs: Long) {
        val now = System.currentTimeMillis()
        val until = (now + durationMs).coerceAtLeast(now)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_PAUSE_UNTIL_MS, until)
            .apply()
    }

    fun clearPauseUntil(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_PAUSE_UNTIL_MS, 0L)
            .apply()
    }

    fun shouldPauseNow(context: Context): Boolean {
        val now = System.currentTimeMillis()
        return isPausedForMeeting(context) || isPausedForVideo(context) || getPauseUntilMs(context) > now
    }

    fun getLastPauseReason(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_PAUSE_REASON, "")
            .orEmpty()
    }

    fun setLastPauseReason(context: Context, reason: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_PAUSE_REASON, reason.take(200))
            .apply()
    }

    fun clearLastPauseReason(context: Context) {
        setLastPauseReason(context, "")
    }
}
