package com.fersaiyan.cyanbridge.protocol

/**
 * Protocol-neutral scan/connection target.
 * Do not expose Android BluetoothDevice here: every protocol must be replaceable.
 */
data class GlassesDevice(
    val address: String,
    val name: String? = null,
    val rssi: Int? = null,
    val serviceUuids: List<String> = emptyList(),
    val protocolHint: GlassesProtocolId? = null,
    val metadata: Map<String, String> = emptyMap(),
)
