    package com.fersaiyan.cyanbridge.protocol.hsc

import android.app.Activity
import android.content.Context
import com.fersaiyan.cyanbridge.protocol.GlassesDevice
import com.fersaiyan.cyanbridge.protocol.GlassesProtocol
import com.fersaiyan.cyanbridge.protocol.GlassesProtocolId
import com.fersaiyan.cyanbridge.protocol.GlassesProtocolProvider

class HscH515GlassesProtocolProvider(
    private val activity: Activity,
) : GlassesProtocolProvider {
    override val id: GlassesProtocolId = GlassesProtocolId.HSC_H5_15

    override fun supports(device: GlassesDevice): Boolean {
        if (device.protocolHint == id) return true

        val hasKnownService = device.serviceUuids.any {
            it.equals(HscH515PacketCodec.SERVICE_UUID.toString(), ignoreCase = true)
        }
        if (hasKnownService) return true

        val name = device.name?.trim().orEmpty().lowercase()
        return name.contains("hsc") ||
            name.contains("h5") ||
            name.contains("h15") ||
            name.contains("hy15") ||
            name.contains("h5-15")
    }

    override fun create(): GlassesProtocol = getShared(activity.applicationContext)

    companion object {
        @Volatile
        private var sharedProtocol: HscH515GlassesProtocol? = null

        private fun getShared(context: Context): HscH515GlassesProtocol {
            val existing = sharedProtocol
            if (existing != null) return existing

            return synchronized(this) {
                sharedProtocol ?: HscH515GlassesProtocol(context.applicationContext).also {
                    sharedProtocol = it
                }
            }
        }
    }
}
