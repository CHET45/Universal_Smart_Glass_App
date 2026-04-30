package com.fersaiyan.cyanbridge.protocol

sealed interface GlassesEvent {
    data class ConnectionChanged(val state: GlassesConnectionState) : GlassesEvent
    data class BatteryChanged(val battery: GlassesBattery) : GlassesEvent
    data class DeviceInfoChanged(val info: GlassesDeviceInfo) : GlassesEvent
    data class MediaCountsChanged(val counts: GlassesMediaCounts) : GlassesEvent
    data class ButtonPressed(val button: Button, val rawCode: Int? = null) : GlassesEvent
    data class RawPacket(val bytes: ByteArray) : GlassesEvent
    data class Error(val error: GlassesProtocolError) : GlassesEvent

    enum class Button {
        PHOTO,
        VIDEO,
        AUDIO,
        AI,
        VOLUME_UP,
        VOLUME_DOWN,
        UNKNOWN,
    }
}
