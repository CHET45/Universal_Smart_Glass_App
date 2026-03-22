package com.fersaiyan.cyanbridge.devices

import android.os.ParcelUuid

/**
 * Chapter 3 heuristics:
 * - Classify by advertised name (primary)
 * - Optionally use service UUIDs if available.
 */
object DeviceClassifier {

    fun guessDeviceClass(
        advertisedName: String?,
        serviceUuids: List<ParcelUuid> = emptyList()
    ): DeviceClass {
        val name = advertisedName?.trim().orEmpty()
        if (name.isEmpty()) return DeviceClass.UNKNOWN

        val lower = name.lowercase()

        // HeyCyan-class heuristics (already used elsewhere in the app).
        if (
            lower.contains("heycyan") ||
            lower.contains("cyan") ||
            name.startsWith("O_") ||
            name.startsWith("Q_")
        ) {
            return DeviceClass.HEY_CYAN
        }

        // Meta Ray-Ban heuristics.
        if (
            lower.contains("ray-ban") ||
            lower.contains("rayban") ||
            (lower.contains("ray") && lower.contains("ban")) ||
            lower.contains("ray-ban meta") ||
            lower.contains("meta ray")
        ) {
            return DeviceClass.META_RAYBAN
        }

        // Generic audio heuristics (best-effort).
        if (
            lower.contains("airpods") ||
            lower.contains("headset") ||
            lower.contains("headphones") ||
            lower.contains("earbuds") ||
            lower.contains("buds")
        ) {
            return DeviceClass.GENERIC_AUDIO
        }

        // Service UUID heuristics placeholder (extend when known).
        // Keeping this in place satisfies the Chapter 3 architecture requirement.
        if (serviceUuids.isNotEmpty()) {
            // TODO: Add known UUID-based detection when available.
        }

        return DeviceClass.UNKNOWN
    }
}
