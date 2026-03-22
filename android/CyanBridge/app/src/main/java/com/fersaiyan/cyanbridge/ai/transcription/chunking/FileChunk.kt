package com.fersaiyan.cyanbridge.ai.transcription.chunking

import java.io.File

/**
 * Represents a byte-range view into a file. This avoids loading large audio files into memory.
 */
data class FileChunk(
    val file: File,
    val index: Int,
    val offsetBytes: Long,
    val lengthBytes: Long,
    val totalBytes: Long,
) {
    init {
        require(index >= 0) { "index must be >= 0" }
        require(offsetBytes >= 0) { "offsetBytes must be >= 0" }
        require(lengthBytes > 0) { "lengthBytes must be > 0" }
        require(totalBytes >= 0) { "totalBytes must be >= 0" }
    }
}
