package com.fersaiyan.cyanbridge.protocol

interface GlassesProtocolProvider {
    val id: GlassesProtocolId

    fun supports(device: GlassesDevice): Boolean

    fun create(): GlassesProtocol
}
