package com.fersaiyan.cyanbridge.agent

import android.content.Context
import com.fersaiyan.cyanbridge.BuildConfig
import com.fersaiyan.cyanbridge.ai.router.AiProviderPrefs
import java.util.Locale

object SubscriptionCheckoutPolicy {

    fun resolveWebCheckoutUrl(context: Context): String {
        val configured = BuildConfig.WEB_SUBSCRIBE_URL.trim()
        if (configured.isNotBlank()) return configured

        val relayBase = AiProviderPrefs.getRelayBaseUrl(context).trim().trimEnd('/')
        if (!relayBase.startsWith("http://") && !relayBase.startsWith("https://")) return ""
        return "$relayBase/web-subscribe"
    }

    fun isWebCheckoutEnabled(context: Context): Boolean {
        val url = resolveWebCheckoutUrl(context)
        if (url.isBlank()) return false

        val allowedCsv = BuildConfig.WEB_SUB_ALLOWED_COUNTRIES.trim()
        if (allowedCsv.isBlank()) {
            // Safe default for development: when URL exists and no country list is configured,
            // show both paths so integrations can be tested.
            return true
        }

        val allowed = allowedCsv
            .split(',')
            .map { it.trim().uppercase(Locale.US) }
            .filter { it.isNotBlank() }
            .toSet()

        if (allowed.isEmpty()) return false

        val country = context.resources.configuration.locales[0]?.country
            ?.uppercase(Locale.US)
            ?.trim()
            .orEmpty()

        return country in allowed
    }
}
