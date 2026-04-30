package com.fersaiyan.cyanbridge.protocol

class GlassesProtocolRegistry(
    private val providers: List<GlassesProtocolProvider>,
) {
    fun byId(id: GlassesProtocolId): GlassesProtocolProvider? =
        providers.firstOrNull { it.id == id }

    fun forDevice(device: GlassesDevice): GlassesProtocolProvider? {
        device.protocolHint?.let { hinted ->
            byId(hinted)?.let { return it }
        }
        return providers.firstOrNull { it.supports(device) }
    }

    fun createForDevice(device: GlassesDevice): GlassesProtocol? =
        forDevice(device)?.create()
}
