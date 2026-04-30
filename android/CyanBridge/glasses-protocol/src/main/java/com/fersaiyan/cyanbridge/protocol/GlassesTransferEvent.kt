package com.fersaiyan.cyanbridge.protocol

sealed interface GlassesTransferEvent {
    data object Started : GlassesTransferEvent
    data class Progress(
        val completed: Int,
        val total: Int,
        val currentFileName: String? = null,
    ) : GlassesTransferEvent

    data class FileReady(
        val localPath: String,
        val mediaType: MediaType,
    ) : GlassesTransferEvent

    data object Finished : GlassesTransferEvent
    data class Failed(val error: GlassesProtocolError) : GlassesTransferEvent

    enum class MediaType {
        PHOTO,
        VIDEO,
        AUDIO,
        UNKNOWN,
    }
}
