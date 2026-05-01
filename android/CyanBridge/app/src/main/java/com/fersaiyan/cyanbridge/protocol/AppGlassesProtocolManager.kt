package com.fersaiyan.cyanbridge.protocol

import android.app.Activity
import com.fersaiyan.cyanbridge.devices.DeviceClass
import com.fersaiyan.cyanbridge.protocol.eyevues2.EyevueS2GlassesProtocolProvider
import com.fersaiyan.cyanbridge.protocol.heycyan.HeyCyanGlassesProtocolProvider

class AppGlassesProtocolManager(
    activity: Activity,
) {
    private val registry = GlassesProtocolRegistry(
        providers = listOf(
            HeyCyanGlassesProtocolProvider(activity),
            EyevueS2GlassesProtocolProvider(activity),
        ),
    )

    private var currentProtocol: GlassesProtocol? = null

    fun currentOrCreate(deviceClass: DeviceClass?): GlassesProtocol? {
        val protocolId = protocolIdFor(deviceClass) ?: return null
        return getOrCreate(protocolId)
    }

    fun getOrCreate(id: GlassesProtocolId): GlassesProtocol {
        val existing = currentProtocol
        if (existing?.id == id) return existing

        existing?.close()

        val provider = registry.byId(id)
            ?: error("No provider registered for protocol: $id")

        return provider.create().also {
            currentProtocol = it
        }
    }

    fun close() {
        currentProtocol?.close()
        currentProtocol = null
    }

    companion object {
        fun protocolIdFor(deviceClass: DeviceClass?): GlassesProtocolId? {
            return when (deviceClass) {
                null -> GlassesProtocolId.HEY_CYAN
                DeviceClass.HEY_CYAN -> GlassesProtocolId.HEY_CYAN
                DeviceClass.EYEVUE_S2 -> GlassesProtocolId.S100

                // Пока для этих типов нет provider-а.
                DeviceClass.META_RAYBAN,
                DeviceClass.GENERIC_AUDIO,
                DeviceClass.UNKNOWN -> null
            }
        }
    }
}
