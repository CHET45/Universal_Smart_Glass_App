package com.fersaiyan.cyanbridge.ui.chat

import android.content.Context
import androidx.core.content.ContextCompat
import com.fersaiyan.cyanbridge.R

object ChatAppearancePrefs {
    private const val PREFS = "chat_appearance_prefs"
    private const val KEY_USER_BUBBLE_COLOR = "user_bubble_color"
    private const val KEY_ASSISTANT_BUBBLE_COLOR = "assistant_bubble_color"
    private const val KEY_WALLPAPER_URI = "chat_wallpaper_uri"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun defaultUserBubbleColor(context: Context): Int =
        ContextCompat.getColor(context, R.color.cyan_accent)

    fun defaultAssistantBubbleColor(context: Context): Int =
        ContextCompat.getColor(context, R.color.card_bg)

    fun getUserBubbleColor(context: Context): Int =
        prefs(context).getInt(KEY_USER_BUBBLE_COLOR, defaultUserBubbleColor(context))

    fun setUserBubbleColor(context: Context, color: Int) {
        prefs(context).edit().putInt(KEY_USER_BUBBLE_COLOR, color).apply()
    }

    fun getAssistantBubbleColor(context: Context): Int =
        prefs(context).getInt(KEY_ASSISTANT_BUBBLE_COLOR, defaultAssistantBubbleColor(context))

    fun setAssistantBubbleColor(context: Context, color: Int) {
        prefs(context).edit().putInt(KEY_ASSISTANT_BUBBLE_COLOR, color).apply()
    }

    fun getWallpaperUri(context: Context): String =
        prefs(context).getString(KEY_WALLPAPER_URI, "") ?: ""

    fun setWallpaperUri(context: Context, uri: String) {
        prefs(context).edit().putString(KEY_WALLPAPER_URI, uri).apply()
    }

    fun clearWallpaper(context: Context) {
        prefs(context).edit().remove(KEY_WALLPAPER_URI).apply()
    }

    fun reset(context: Context) {
        prefs(context).edit()
            .putInt(KEY_USER_BUBBLE_COLOR, defaultUserBubbleColor(context))
            .putInt(KEY_ASSISTANT_BUBBLE_COLOR, defaultAssistantBubbleColor(context))
            .remove(KEY_WALLPAPER_URI)
            .apply()
    }
}
