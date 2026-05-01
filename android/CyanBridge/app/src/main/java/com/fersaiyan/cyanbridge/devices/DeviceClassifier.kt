package com.fersaiyan.cyanbridge.devices

import android.os.ParcelUuid

/**
 * Chapter 3: Device scanning + pairing.
 * - Classify by advertised name when it exists.
 * - Also classify by service UUID.
 * - HSC/H5-15 can also be identified by raw manufacturer advertising data.
 */
object DeviceClassifier {

    private const val EYEVUE_S2_SERVICE_UUID = "0000aa12-0000-1000-8000-00805f9b34fb"
    private const val HSC_H5_15_SERVICE_UUID = "01000100-0000-2000-8000-009078563412"

    fun guessDeviceClass(
        advertisedName: String?,
        serviceUuids: List<ParcelUuid> = emptyList(),
        rawScanRecord: ByteArray? = null,
    ): DeviceClass {
        val name = advertisedName?.trim()
            .orEmpty()
        val lower = name.lowercase()

        if (serviceUuids.any {
                it.uuid.toString()
                    .equals(
                        HSC_H5_15_SERVICE_UUID,
                        ignoreCase = true
                    )
            }) {
            return DeviceClass.HSC_H5_15
        }

        if (containsHscManufacturerData(rawScanRecord)) {
            return DeviceClass.HSC_H5_15
        }

        if (serviceUuids.any {
                it.uuid.toString()
                    .equals(
                        EYEVUE_S2_SERVICE_UUID,
                        ignoreCase = true
                    )
            }) {
            return DeviceClass.EYEVUE_S2
        }

        if (lower.contains("hsc") || lower.contains("h5") || lower.contains("h15") || lower.contains("hy15") || lower.contains("h5-15")) {
            return DeviceClass.HSC_H5_15
        }

        if (lower.contains("eyevue") || lower.contains("eye") || lower.contains("vue") || lower.contains("s100") || lower.contains("lensiq") || lower.contains("s2")) {
            return DeviceClass.EYEVUE_S2
        }

        // HeyCyan-class heuristics (already used elsewhere in the app).
        if (lower.contains("heycyan") || lower.contains("cyan") || name.startsWith("O_") || name.startsWith("Q_")) {
            return DeviceClass.HEY_CYAN
        }

        // Meta Ray-Ban heuristics.
        if (lower.contains("ray-ban") || lower.contains("rayban") || (lower.contains("ray") && lower.contains("ban")) || lower.contains("ray-ban meta") || lower.contains("meta ray")) {
            return DeviceClass.META_RAYBAN
        }

        // Generic audio heuristics (best-effort).
        if (lower.contains("airpods") || lower.contains("headset") || lower.contains("headphones") || lower.contains("earbuds") || lower.contains("buds")) {
            return DeviceClass.GENERIC_AUDIO
        }

        return DeviceClass.UNKNOWN
    }

    /**
     * HSC/H5-15 can be advertised without a useful BLE name and sometimes without
     * the primary service UUID in the parsed scan result. Protocol v2.0.15 describes
     * the HSC marker in manufacturer/custom advertising data.
     *
     * Raw BLE advertising format:
     *   length | AD type | AD payload
     *
     * This checks manufacturer specific data blocks, AD type 0xFF, for ASCII "HSC".
     */
    private fun containsHscManufacturerData(rawScanRecord: ByteArray?): Boolean {
        if (rawScanRecord == null || rawScanRecord.isEmpty()) return false

        var index = 0
        while (index < rawScanRecord.size) {
            val length = rawScanRecord[index].toInt() and 0xFF
            if (length == 0) break

            val typeIndex = index + 1
            val dataStart = index + 2
            val nextIndex = index + 1 + length
            if (typeIndex >= rawScanRecord.size || nextIndex > rawScanRecord.size) break

            val adType = rawScanRecord[typeIndex].toInt() and 0xFF
            if (adType == 0xFF) {
                val data = rawScanRecord.copyOfRange(
                    dataStart,
                    nextIndex
                )
                if (containsAsciiHsc(data)) return true
            }

            index = nextIndex
        }

        return false
    }

    private fun containsAsciiHsc(data: ByteArray): Boolean {
        if (data.size < 3) return false

        for (i in 0..(data.size - 3)) {
            if (data[i] == 0x48.toByte() && data[i + 1] == 0x53.toByte() && data[i + 2] == 0x43.toByte()) {
                return true
            }
        }

        return false
    }
}
