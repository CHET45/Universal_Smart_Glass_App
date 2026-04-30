package com.fersaiyan.cyanbridge.protocol

data class GlassesProtocolError(
    val code: String,
    val message: String,
    val cause: Throwable? = null,
)
