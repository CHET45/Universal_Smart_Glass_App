package com.fersaiyan.cyanbridge.agent

import android.content.Context

object ProSubscriptionServerPrefs {
    private const val PREFS_NAME = "pro_subscription_server_prefs"
    private const val KEY_VERIFY_URL = "verify_url"
    private const val KEY_API_TOKEN = "api_token"
    private const val KEY_ACCOUNT_EMAIL = "account_email"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getVerifyUrl(context: Context): String =
        prefs(context).getString(KEY_VERIFY_URL, "").orEmpty().trim()

    fun setVerifyUrl(context: Context, url: String?) {
        val value = url?.trim().orEmpty()
        prefs(context).edit().apply {
            if (value.isBlank()) remove(KEY_VERIFY_URL) else putString(KEY_VERIFY_URL, value)
        }.apply()
    }

    fun getApiToken(context: Context): String =
        prefs(context).getString(KEY_API_TOKEN, "").orEmpty().trim()

    fun setApiToken(context: Context, token: String?) {
        val value = token?.trim().orEmpty()
        prefs(context).edit().apply {
            if (value.isBlank()) remove(KEY_API_TOKEN) else putString(KEY_API_TOKEN, value)
        }.apply()
    }

    fun getAccountEmail(context: Context): String =
        prefs(context).getString(KEY_ACCOUNT_EMAIL, "").orEmpty().trim()

    fun setAccountEmail(context: Context, email: String?) {
        val value = email?.trim().orEmpty()
        prefs(context).edit().apply {
            if (value.isBlank()) remove(KEY_ACCOUNT_EMAIL) else putString(KEY_ACCOUNT_EMAIL, value)
        }.apply()
    }
}
