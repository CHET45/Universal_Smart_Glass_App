package com.fersaiyan.cyanbridge.protocol

data class GlassesDeviceInfo(
    val hardwareVersion: String? = null,
    val firmwareVersion: String? = null,
    val bluetoothFirmwareVersion: String? = null,
    val wifiFirmwareVersion: String? = null,
    val macAddress: String? = null,
    val raw: Map<String, String> = emptyMap(),
)
