package com.fersaiyan.cyanbridge.protocol

data class GlassesBattery(
    val percent: Int,
    val charging: Boolean? = null,
)
