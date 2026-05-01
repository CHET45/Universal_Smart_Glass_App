package com.fersaiyan.cyanbridge.protocol.eyevues2

import android.app.Activity
import android.content.Context
import com.fersaiyan.cyanbridge.protocol.GlassesDevice
import com.fersaiyan.cyanbridge.protocol.GlassesProtocol
import com.fersaiyan.cyanbridge.protocol.GlassesProtocolId
import com.fersaiyan.cyanbridge.protocol.GlassesProtocolProvider

class EyevueS2GlassesProtocolProvider(
    private val activity: Activity,
) : GlassesProtocolProvider {
    override val id: GlassesProtocolId = GlassesProtocolId.S100

    override fun supports(device: GlassesDevice): Boolean {
        if (device.protocolHint == id) return true

        val hasKnownService = device.serviceUuids.any {
            it.equals(EyevueS2PacketCodec.SERVICE_UUID.toString(), ignoreCase = true)
        }
        if (hasKnownService) return true

        val name = device.name?.trim().orEmpty().lowercase()
        return name.contains("eyevue") || name.contains("s2") || name.contains("lensiq")
    }

    override fun create(): GlassesProtocol = getShared(activity.applicationContext)

    companion object {
        @Volatile
        private var sharedProtocol: EyevueS2GlassesProtocol? = null

        private fun getShared(context: Context): EyevueS2GlassesProtocol {
            val existing = sharedProtocol
            if (existing != null) return existing

            return synchronized(this) {
                sharedProtocol ?: EyevueS2GlassesProtocol(context.applicationContext).also {
                    sharedProtocol = it
                }
            }
        }
    }
}
