package com.fersaiyan.cyanbridge.devices

import android.os.ParcelUuid

/**
 * Chapter 3 heuristics:
 * - Classify by advertised name when it exists.
 * - Also classify by service UUID, because HSC/H5-15 can be identified without a useful BLE name.
 */
object DeviceClassifier {

    private const val EYEVUE_S2_SERVICE_UUID = "0000aa12-0000-1000-8000-00805f9b34fb"
    private const val HSC_H5_15_SERVICE_UUID = "01000100-0000-2000-8000-009078563412"

    fun guessDeviceClass(
        advertisedName: String?,
        serviceUuids: List<ParcelUuid> = emptyList()
    ): DeviceClass {
        val name = advertisedName?.trim().orEmpty()
        val lower = name.lowercase()

        if (serviceUuids.any { it.uuid.toString().equals(HSC_H5_15_SERVICE_UUID, ignoreCase = true) }) {
            return DeviceClass.HSC_H5_15
        }

        if (serviceUuids.any { it.uuid.toString().equals(EYEVUE_S2_SERVICE_UUID, ignoreCase = true) }) {
            return DeviceClass.EYEVUE_S2
        }

        if (lower.contains("hsc") || lower.contains("h5") || lower.contains("h15") || lower.contains("hy15") || lower.contains("h5-15")) {
            return DeviceClass.HSC_H5_15
        }

        if (lower.contains("eyevue") || lower.contains("lensiq") || lower.contains("s2")) {
            return DeviceClass.EYEVUE_S2
        }

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

        return DeviceClass.UNKNOWN
    }
}
