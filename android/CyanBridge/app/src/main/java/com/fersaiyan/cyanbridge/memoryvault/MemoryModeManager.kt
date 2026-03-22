package com.fersaiyan.cyanbridge.memoryvault

import android.content.Context
import com.fersaiyan.cyanbridge.data.local.entity.MemoryModePreferenceEntity
import com.fersaiyan.cyanbridge.ui.MyApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

object MemoryModeManager {
    private const val PREFS = "memory_mode_prefs"
    private const val KEY_MODE = "mode"
    private const val KEY_OCR_RETENTION_DAYS = "ocr_retention_days"
    private const val KEY_OCR_CAPTURE_ENABLED = "ocr_capture_enabled"
    private const val KEY_SYNC_EXPLICIT = "sync_explicit"
    private const val KEY_SYNC_DAILY = "sync_daily"
    private const val KEY_SYNC_OCR = "sync_ocr"
    private const val KEY_SYNC_DERIVED = "sync_derived"

    fun getSelectedMode(context: Context): MemoryPrivacyMode {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODE, MemoryPrivacyMode.PRIVATE_LOCAL.name)
        return MemoryPrivacyMode.fromRaw(raw)
    }

    fun setSelectedMode(context: Context, mode: MemoryPrivacyMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODE, mode.name)
            .apply()
        persistSnapshotBestEffort(context)
    }

    fun getScreenOcrRetentionDays(context: Context): Int {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_OCR_RETENTION_DAYS, 7)
            .coerceIn(1, 365)
    }

    fun setScreenOcrRetentionDays(context: Context, days: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_OCR_RETENTION_DAYS, days.coerceIn(1, 365))
            .apply()
        persistSnapshotBestEffort(context)
    }

    fun isScreenOcrCaptureEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_OCR_CAPTURE_ENABLED, true)
    }

    fun setScreenOcrCaptureEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_OCR_CAPTURE_ENABLED, enabled)
            .apply()
        persistSnapshotBestEffort(context)
    }

    fun isSourceSyncEnabled(context: Context, sourceType: MemorySourceType): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return when (sourceType) {
            MemorySourceType.EXPLICIT_USER_FACT -> prefs.getBoolean(KEY_SYNC_EXPLICIT, true)
            MemorySourceType.AUTO_DAILY_FACT -> prefs.getBoolean(KEY_SYNC_DAILY, true)
            MemorySourceType.SCREEN_OCR -> prefs.getBoolean(KEY_SYNC_OCR, false)
            MemorySourceType.DERIVED_SUMMARY -> prefs.getBoolean(KEY_SYNC_DERIVED, false)
            MemorySourceType.IMPORTED_TEXT -> true
            MemorySourceType.SYSTEM_NOTE -> false
        }
    }

    fun setSourceSyncEnabled(context: Context, sourceType: MemorySourceType, enabled: Boolean) {
        val key = when (sourceType) {
            MemorySourceType.EXPLICIT_USER_FACT -> KEY_SYNC_EXPLICIT
            MemorySourceType.AUTO_DAILY_FACT -> KEY_SYNC_DAILY
            MemorySourceType.SCREEN_OCR -> KEY_SYNC_OCR
            MemorySourceType.DERIVED_SUMMARY -> KEY_SYNC_DERIVED
            MemorySourceType.IMPORTED_TEXT -> null
            MemorySourceType.SYSTEM_NOTE -> null
        } ?: return

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(key, enabled)
            .apply()
        persistSnapshotBestEffort(context)
    }

    fun isEncryptedSyncBackendConfigured(): Boolean = false

    fun isFastCloudBackendConfigured(): Boolean = false

    fun isConfidentialCloudBackendConfigured(): Boolean = false

    fun isModeBackendAvailable(mode: MemoryPrivacyMode): Boolean {
        return when (mode) {
            MemoryPrivacyMode.PRIVATE_LOCAL -> true
            MemoryPrivacyMode.ENCRYPTED_SYNC -> isEncryptedSyncBackendConfigured()
            MemoryPrivacyMode.FAST_CLOUD_MEMORY -> isFastCloudBackendConfigured()
            MemoryPrivacyMode.CONFIDENTIAL_CLOUD_BETA -> isConfidentialCloudBackendConfigured()
        }
    }

    fun modeAvailabilityText(mode: MemoryPrivacyMode): String {
        return when (mode) {
            MemoryPrivacyMode.PRIVATE_LOCAL -> "Fully available on this device."
            MemoryPrivacyMode.ENCRYPTED_SYNC -> "Prepared locally. Secure sync backend is not configured yet."
            MemoryPrivacyMode.FAST_CLOUD_MEMORY -> "Unavailable: requires future cloud memory backend."
            MemoryPrivacyMode.CONFIDENTIAL_CLOUD_BETA -> "Unavailable: requires future confidential cloud backend."
        }
    }

    fun persistSnapshotBestEffort(context: Context) {
        runCatching {
            runBlocking(Dispatchers.IO) {
                persistSnapshot(context)
            }
        }
    }

    suspend fun persistSnapshot(context: Context) = withContext(Dispatchers.IO) {
        val dao = MyApplication.database.memoryVaultDao()
        dao.upsertModePreference(
            MemoryModePreferenceEntity(
                selectedMode = getSelectedMode(context).name,
                screenOcrRetentionDays = getScreenOcrRetentionDays(context),
                screenOcrCaptureEnabled = isScreenOcrCaptureEnabled(context),
                explicitFactsSyncEnabled = isSourceSyncEnabled(context, MemorySourceType.EXPLICIT_USER_FACT),
                dailyFactsSyncEnabled = isSourceSyncEnabled(context, MemorySourceType.AUTO_DAILY_FACT),
                screenOcrSyncEnabled = isSourceSyncEnabled(context, MemorySourceType.SCREEN_OCR),
                derivedSummariesSyncEnabled = isSourceSyncEnabled(context, MemorySourceType.DERIVED_SUMMARY),
                updatedAt = System.currentTimeMillis(),
            )
        )
    }
}
