package com.fersaiyan.cyanbridge.agent

import android.content.Context

object ProSubscriptionAiPrefs {
    private const val PREFS_NAME = "pro_subscription_ai_prefs"
    private const val KEY_REQUESTS_MODEL = "requests_model"
    private const val KEY_QUESTIONS_MODEL = "questions_model"
    private const val KEY_TASKS_MODEL = "tasks_model"

    private const val DEFAULT_MODEL = "auto"

    private fun normalizeModel(model: String?): String {
        val clean = model.orEmpty().trim()
        if (clean.isBlank()) return DEFAULT_MODEL
        val withoutMultiplier = clean
            .replace(Regex("\\s*\\(\\s*\\d+\\s*x\\s*\\)\\s*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\(\\s*x\\s*\\d+\\s*\\)\\s*$", RegexOption.IGNORE_CASE), "")
            .trim()
        val withoutDecoratedId = withoutMultiplier.substringBefore(" · ").trim()
        val withoutFree = if (withoutDecoratedId.endsWith(":free", ignoreCase = true)) {
            withoutDecoratedId.dropLast(5)
        } else {
            withoutDecoratedId
        }
        return withoutFree.trim().ifBlank { DEFAULT_MODEL }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getRequestsModel(context: Context): String =
        normalizeModel(prefs(context).getString(KEY_REQUESTS_MODEL, DEFAULT_MODEL))

    fun setRequestsModel(context: Context, model: String) {
        prefs(context).edit().putString(KEY_REQUESTS_MODEL, normalizeModel(model)).apply()
    }

    fun getQuestionsModel(context: Context): String =
        normalizeModel(prefs(context).getString(KEY_QUESTIONS_MODEL, DEFAULT_MODEL))

    fun setQuestionsModel(context: Context, model: String) {
        prefs(context).edit().putString(KEY_QUESTIONS_MODEL, normalizeModel(model)).apply()
    }

    fun getTasksModel(context: Context): String =
        normalizeModel(prefs(context).getString(KEY_TASKS_MODEL, DEFAULT_MODEL))

    fun setTasksModel(context: Context, model: String) {
        prefs(context).edit().putString(KEY_TASKS_MODEL, normalizeModel(model)).apply()
    }
}
