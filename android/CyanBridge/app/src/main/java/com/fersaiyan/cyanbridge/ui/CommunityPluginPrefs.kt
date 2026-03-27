package com.fersaiyan.cyanbridge.ui

import android.content.Context

object CommunityPluginPrefs {
    private const val PREFS = "community_plugins"
    private const val KEY_GEMINI_CHATGPT_IMAGE_AUTOMATION = "gemini_chatgpt_image_automation"
    private const val KEY_IMAGE_AUTOMATION_BANNER_DISMISSED = "image_automation_banner_dismissed"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isGeminiChatGptImageAutomationEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_GEMINI_CHATGPT_IMAGE_AUTOMATION, false)
    }

    fun setGeminiChatGptImageAutomationEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_GEMINI_CHATGPT_IMAGE_AUTOMATION, enabled).apply()
    }

    fun isImageAutomationBannerDismissed(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_IMAGE_AUTOMATION_BANNER_DISMISSED, false)
    }

    fun setImageAutomationBannerDismissed(context: Context, dismissed: Boolean) {
        prefs(context).edit().putBoolean(KEY_IMAGE_AUTOMATION_BANNER_DISMISSED, dismissed).apply()
    }
}
