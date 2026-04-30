package com.fersaiyan.cyanbridge.protocol

/**
 * Optional filter for scan implementations.
 * Leave fields empty when the protocol should decide by service UUID/name internally.
 */
data class GlassesScanFilter(
    val protocolId: GlassesProtocolId? = null,
    val namePrefix: String? = null,
    val serviceUuids: List<String> = emptyList(),
    val timeoutMillis: Long = 15_000L,
)
