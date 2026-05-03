package com.fersaiyan.cyanbridge.media

import android.content.Context

object GlassesMediaPrefs {
    private const val PREFS = "glasses_media"
    private const val KEY_VIDEO_RECORDING = "video_recording"
    private const val KEY_AUDIO_RECORDING = "audio_recording"

    fun isVideoRecording(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_VIDEO_RECORDING, false)
    }

    fun setVideoRecording(context: Context, recording: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_VIDEO_RECORDING, recording)
            .apply()
    }

    fun isAudioRecording(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUDIO_RECORDING, false)
    }

    fun setAudioRecording(context: Context, recording: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUDIO_RECORDING, recording)
            .apply()
    }
}
