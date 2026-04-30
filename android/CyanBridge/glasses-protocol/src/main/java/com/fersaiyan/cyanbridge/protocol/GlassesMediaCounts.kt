package com.fersaiyan.cyanbridge.protocol

data class GlassesMediaCounts(
    val photos: Int = 0,
    val videos: Int = 0,
    val audios: Int = 0,
) {
    val total: Int get() = photos + videos + audios
}
