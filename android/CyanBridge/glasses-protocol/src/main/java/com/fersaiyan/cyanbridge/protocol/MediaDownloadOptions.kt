package com.fersaiyan.cyanbridge.protocol

data class MediaDownloadOptions(
    val includePhotos: Boolean = true,
    val includeVideos: Boolean = true,
    val includeAudio: Boolean = true,
    val deleteRemoteAfterDownload: Boolean = false,
)
