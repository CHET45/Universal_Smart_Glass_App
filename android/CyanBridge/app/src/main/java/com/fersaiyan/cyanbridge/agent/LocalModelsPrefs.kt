package com.fersaiyan.cyanbridge.agent

import android.content.Context
import com.fersaiyan.cyanbridge.localmodels.settings.LocalModelSettingsRepository
import com.fersaiyan.cyanbridge.localmodels.storage.LocalModelStorageRepository

object LocalModelsPrefs {
    private const val PREFS = "local_models_prefs"
    private const val KEY_MODEL_PATH = "model_path"
    private const val KEY_USE_GPU = "use_gpu"
    private const val KEY_MAX_TOKENS = "max_tokens"
    private const val KEY_TEMPERATURE = "temperature"
    private const val KEY_LAST_BENCHMARK = "last_benchmark"

    fun getModelPath(context: Context): String {
        return LocalModelStorageRepository.resolveSelectedModel(context)?.absolutePath
            ?: context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_MODEL_PATH, "")
                .orEmpty()
    }

    fun setModelPath(context: Context, path: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODEL_PATH, path)
            .apply()
    }

    fun isUseGpuEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_USE_GPU, false)
    }

    fun setUseGpuEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_USE_GPU, enabled)
            .apply()
    }

    fun getMaxTokens(context: Context): Int {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_MAX_TOKENS, 4096)
    }

    fun setMaxTokens(context: Context, maxTokens: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_MAX_TOKENS, maxTokens.coerceIn(64, 8192))
            .apply()
    }

    fun getTemperature(context: Context): Float {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getFloat(KEY_TEMPERATURE, 0.7f)
    }

    fun setTemperature(context: Context, temperature: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_TEMPERATURE, temperature.coerceIn(0f, 2f))
            .apply()
    }

    fun getLastBenchmark(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_BENCHMARK, "")
            .orEmpty()
    }

    fun setLastBenchmark(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_BENCHMARK, value.trim())
            .apply()
    }

    fun getSelectedModelId(context: Context): String? {
        return LocalModelStorageRepository.getSelectedModelId(context)
    }

    fun setSelectedModelId(context: Context, modelId: String?) {
        LocalModelStorageRepository.setSelectedModelId(context, modelId)
    }

    fun getHuggingFaceToken(context: Context): String {
        return LocalModelSettingsRepository.getHuggingFaceToken(context)
    }

    fun setHuggingFaceToken(context: Context, token: String) {
        LocalModelSettingsRepository.setHuggingFaceToken(context, token)
    }
}
