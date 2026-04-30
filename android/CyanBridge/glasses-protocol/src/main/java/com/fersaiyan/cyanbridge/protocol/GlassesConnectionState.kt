package com.fersaiyan.cyanbridge.protocol

sealed interface GlassesConnectionState {
    data object Idle : GlassesConnectionState
    data object Scanning : GlassesConnectionState
    data class Connecting(val device: GlassesDevice) : GlassesConnectionState
    data class Connected(val device: GlassesDevice) : GlassesConnectionState
    data object Disconnecting : GlassesConnectionState
    data class Disconnected(val reason: String? = null) : GlassesConnectionState
    data class Failed(val error: GlassesProtocolError) : GlassesConnectionState
}
