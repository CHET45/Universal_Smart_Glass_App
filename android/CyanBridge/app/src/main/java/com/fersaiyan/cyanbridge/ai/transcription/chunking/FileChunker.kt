package com.fersaiyan.cyanbridge.ai.transcription.chunking

import java.io.File
import java.io.RandomAccessFile

/**
 * Stateless byte-range chunker.
 *
 * Note that this is file-byte based, not codec-frame aware. Backend/provider logic must tolerate
 * arbitrary boundaries if strict media framing is required.
 */
object FileChunker {

    /**
     * Splits [file] into sequential byte chunks of at most [maxChunkBytes].
     */
    fun chunk(file: File, maxChunkBytes: Long): List<FileChunk> {
        require(maxChunkBytes > 0) { "maxChunkBytes must be > 0" }
        val total = file.length()
        if (total <= 0L) return emptyList()

        val chunks = ArrayList<FileChunk>()
        var offset = 0L
        var index = 0
        while (offset < total) {
            val remaining = total - offset
            val len = minOf(remaining, maxChunkBytes)
            chunks.add(
                FileChunk(
                    file = file,
                    index = index,
                    offsetBytes = offset,
                    lengthBytes = len,
                    totalBytes = total,
                )
            )
            offset += len
            index++
        }
        return chunks
    }

    /** Read a [chunk] into memory (for tests/small chunks). */
    fun readBytes(chunk: FileChunk): ByteArray {
        RandomAccessFile(chunk.file, "r").use { raf ->
            raf.seek(chunk.offsetBytes)
            val buf = ByteArray(chunk.lengthBytes.toInt())
            raf.readFully(buf)
            return buf
        }
    }
}
