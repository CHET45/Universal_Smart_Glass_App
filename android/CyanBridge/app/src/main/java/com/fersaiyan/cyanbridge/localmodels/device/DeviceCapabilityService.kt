package com.fersaiyan.cyanbridge.localmodels.device

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import com.fersaiyan.cyanbridge.localmodels.catalog.LocalModelCatalogEntry
import com.fersaiyan.cyanbridge.localmodels.settings.LocalModelPerformanceProfile

data class DeviceSnapshot(
    val primaryAbi: String,
    val supportedAbis: List<String>,
    val totalRamBytes: Long,
    val freeStorageBytes: Long,
    val cpuCoreCount: Int,
)

data class DeviceCapabilityAssessment(
    val supported: Boolean,
    val blockers: List<String>,
    val warnings: List<String>,
    val recommendedProfile: LocalModelPerformanceProfile,
)

object DeviceCapabilityService {
    private const val GIB = 1024.0 * 1024.0 * 1024.0

    fun snapshot(context: Context): DeviceSnapshot {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(mem)

        val statFs = StatFs(context.filesDir.absolutePath)

        return DeviceSnapshot(
            primaryAbi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
            supportedAbis = Build.SUPPORTED_ABIS.toList(),
            totalRamBytes = mem.totalMem,
            freeStorageBytes = statFs.availableBytes,
            cpuCoreCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
        )
    }

    fun assess(
        snapshot: DeviceSnapshot,
        entry: LocalModelCatalogEntry,
        requireDownloadHeadroom: Boolean,
    ): DeviceCapabilityAssessment {
        val blockers = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        val abiSupported = snapshot.supportedAbis.any { it == "arm64-v8a" || it == "x86_64" }
        if (!abiSupported) {
            blockers += "This device ABI is not supported by the current local llama.cpp binding."
        }

        val ramGb = snapshot.totalRamBytes / GIB
        if (ramGb < entry.minRamGb * 0.85) {
            warnings += "This model is likely too heavy for available RAM (${String.format("%.1f", ramGb)} GB)."
        } else if (ramGb < entry.minRamGb) {
            warnings += "This model may be slow on this RAM tier (${String.format("%.1f", ramGb)} GB)."
        }

        val freeGb = snapshot.freeStorageBytes / GIB
        val requiredForDownloadGb = if (requireDownloadHeadroom) {
            (entry.sizeBytes / GIB) + 0.35
        } else {
            entry.minStorageGb
        }
        if (freeGb < requiredForDownloadGb) {
            blockers += "Not enough free storage. Need about ${String.format("%.2f", requiredForDownloadGb)} GB."
        } else if (freeGb < entry.minStorageGb) {
            warnings += "Storage is close to the recommended minimum (${String.format("%.2f", freeGb)} GB free)."
        }

        if (snapshot.cpuCoreCount <= 4 && (entry.tags.contains("quality") || entry.minRamGb >= 8.0)) {
            warnings += "This model may feel slow on low core-count CPUs."
        }

        return DeviceCapabilityAssessment(
            supported = blockers.isEmpty(),
            blockers = blockers,
            warnings = warnings,
            recommendedProfile = recommendProfile(snapshot, entry),
        )
    }

    fun recommendProfile(
        snapshot: DeviceSnapshot,
        entry: LocalModelCatalogEntry,
    ): LocalModelPerformanceProfile {
        val ramGb = snapshot.totalRamBytes / GIB
        return when {
            ramGb < 6.0 || entry.minRamGb <= 4.0 -> LocalModelPerformanceProfile.FAST
            ramGb < 9.0 -> LocalModelPerformanceProfile.BALANCED
            else -> LocalModelPerformanceProfile.HIGH_QUALITY
        }
    }
}
