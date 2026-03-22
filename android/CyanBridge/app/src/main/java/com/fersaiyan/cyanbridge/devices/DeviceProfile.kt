package com.fersaiyan.cyanbridge.devices

/**
 * Stored selection for the last connected/selected device.
 */
data class DeviceProfile(
    val macAddress: String,
    val advertisedName: String?,
    val detectedClass: DeviceClass,
    val selectedClass: DeviceClass,
    val userOverridden: Boolean,
)
