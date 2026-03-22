package com.fersaiyan.cyanbridge.audio

import android.content.Context

/**
 * Lightweight state snapshot for capture UI surfaces.
 *
 * This is not a source of truth for historical data; completed sessions are persisted in Room.
 * Instead, this helps screens recover "is recording" state if process/activity is recreated.
 */
object MeetingCapturePrefs {
    private const val PREFS = "meeting_capture"

    private const val KEY_IS_RECORDING = "is_recording"
    private const val KEY_START_AT_MS = "start_at_ms"
    private const val KEY_SOURCE = "source"
    private const val KEY_AUDIO_PATH = "audio_path"
    private const val KEY_DEVICE_CLASS = "device_class"

    fun setRecording(
        context: Context,
        isRecording: Boolean,
        startAtMs: Long? = null,
        source: CaptureSource? = null,
        audioPath: String? = null,
        deviceClass: String? = null,
    ) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.edit().apply {
            putBoolean(KEY_IS_RECORDING, isRecording)
            if (startAtMs != null) putLong(KEY_START_AT_MS, startAtMs) else remove(KEY_START_AT_MS)
            if (source != null) putString(KEY_SOURCE, source.name) else remove(KEY_SOURCE)
            if (audioPath != null) putString(KEY_AUDIO_PATH, audioPath) else remove(KEY_AUDIO_PATH)
            if (deviceClass != null) putString(KEY_DEVICE_CLASS, deviceClass) else remove(KEY_DEVICE_CLASS)
        }.apply()
    }

    data class State(
        val isRecording: Boolean,
        val startAtMs: Long?,
        val source: CaptureSource?,
        val audioPath: String?,
        val deviceClass: String?,
    )

    fun getState(context: Context): State {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val isRecording = p.getBoolean(KEY_IS_RECORDING, false)
        val startAt = if (p.contains(KEY_START_AT_MS)) p.getLong(KEY_START_AT_MS, 0L) else null
        val source = p.getString(KEY_SOURCE, null)?.let {
            runCatching { CaptureSource.valueOf(it) }.getOrNull()
        }
        val audioPath = p.getString(KEY_AUDIO_PATH, null)
        val deviceClass = p.getString(KEY_DEVICE_CLASS, null)
        return State(isRecording, startAt, source, audioPath, deviceClass)
    }

    fun clear(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.edit().clear().apply()
    }
}
