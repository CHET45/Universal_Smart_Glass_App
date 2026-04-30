package com.fersaiyan.cyanbridge.protocol

sealed interface GlassesCommandResult {
    data object Accepted : GlassesCommandResult
    data class Rejected(val error: GlassesProtocolError) : GlassesCommandResult
}
