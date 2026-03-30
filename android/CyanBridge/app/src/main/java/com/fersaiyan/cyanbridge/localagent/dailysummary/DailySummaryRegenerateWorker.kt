package com.fersaiyan.cyanbridge.localagent.dailysummary

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

class DailySummaryRegenerateWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = supervisorScope {
        val date = inputData.getString(KEY_DATE)?.trim().orEmpty()
        if (date.isBlank()) {
            return@supervisorScope Result.failure(workDataOf(KEY_ERROR to "Missing summary date"))
        }

        val provider = DailySummaryGenerator.providerHint(applicationContext)
        val inputTokens = DailySummaryGenerator.estimateInputTokensForDate(applicationContext, date)
        val estimate = DailySummaryRunHistory.estimate(
            context = applicationContext,
            providerHint = provider,
            inputTokens = inputTokens,
        )

        setProgress(progressData(
            percent = 1,
            etaMs = estimate.expectedTotalMs,
            stage = "Preparing prompt",
            provider = estimate.provider,
            sampleCount = estimate.sampleCount,
        ))

        val started = System.currentTimeMillis()
        val ticker = launch {
            while (isActive) {
                val elapsed = (System.currentTimeMillis() - started).coerceAtLeast(0L)
                val total = estimate.expectedTotalMs.coerceAtLeast(2_000L)
                val rawPercent = ((elapsed.toDouble() / total.toDouble()) * 100.0).toInt()
                val percent = rawPercent.coerceIn(1, 95)
                val eta = (total - elapsed).coerceAtLeast(0L)
                val stage = if (elapsed < estimate.expectedPromptMs) {
                    "Processing prompt"
                } else {
                    "Generating summary"
                }
                setProgress(progressData(percent, eta, stage, estimate.provider, estimate.sampleCount))
                delay(500L)
            }
        }

        val generation = DailySummaryGenerator.generateAndStore(applicationContext, date)
        ticker.cancel()

        return@supervisorScope generation.fold(
            onSuccess = { file ->
                setProgress(progressData(100, 0L, "Completed", estimate.provider, estimate.sampleCount))
                Result.success(
                    workDataOf(
                        KEY_DATE to date,
                        KEY_OUTPUT_PATH to file.absolutePath,
                    ),
                )
            },
            onFailure = { err ->
                Result.failure(
                    workDataOf(
                        KEY_DATE to date,
                        KEY_ERROR to (err.message ?: "Failed to regenerate summary"),
                    ),
                )
            },
        )
    }

    private fun progressData(
        percent: Int,
        etaMs: Long,
        stage: String,
        provider: String,
        sampleCount: Int,
    ): Data {
        return workDataOf(
            KEY_PROGRESS_PERCENT to percent.coerceIn(0, 100),
            KEY_ETA_MS to etaMs.coerceAtLeast(0L),
            KEY_STAGE to stage,
            KEY_PROVIDER to provider,
            KEY_SAMPLE_COUNT to sampleCount.coerceAtLeast(0),
        )
    }

    companion object {
        const val KEY_DATE = "key_date"
        const val KEY_OUTPUT_PATH = "key_output_path"
        const val KEY_ERROR = "key_error"

        const val KEY_PROGRESS_PERCENT = "key_progress_percent"
        const val KEY_ETA_MS = "key_eta_ms"
        const val KEY_STAGE = "key_stage"
        const val KEY_PROVIDER = "key_provider"
        const val KEY_SAMPLE_COUNT = "key_sample_count"

        fun uniqueWorkName(date: String): String = "daily_summary_regen_${date.trim()}"

        fun buildRequest(date: String) = OneTimeWorkRequestBuilder<DailySummaryRegenerateWorker>()
            .setInputData(workDataOf(KEY_DATE to date.trim()))
            .build()
    }
}
