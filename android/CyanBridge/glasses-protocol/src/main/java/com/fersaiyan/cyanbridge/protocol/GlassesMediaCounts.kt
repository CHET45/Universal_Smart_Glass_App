package com.fersaiyan.cyanbridge.protocol

data class GlassesMediaCounts(
    val photos: Int = 0,
    val videos: Int = 0,
    val audios: Int = 0,
    /**
     * HSC/H5-15 v2.0.15 reports a single "not yet imported file count" through 0x0916.
     * It is not a photo/video/audio breakdown, so keep it separate instead of pretending
     * that every pending file is a photo.
     */
    val unimportedFiles: Int? = null,
) {
    val total: Int get() = unimportedFiles ?: (photos + videos + audios)
}
