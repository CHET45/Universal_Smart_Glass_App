package com.fersaiyan.cyanbridge.ai.transcription.chunking

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class FileChunkerTest {

    @Test
    fun chunk_smallFile_singleChunk() {
        val f = tempFileWithBytes(ByteArray(10) { it.toByte() })
        val chunks = FileChunker.chunk(f, maxChunkBytes = 1024)
        assertEquals(1, chunks.size)
        assertEquals(0, chunks[0].index)
        assertEquals(0L, chunks[0].offsetBytes)
        assertEquals(10L, chunks[0].lengthBytes)
        assertEquals(10L, chunks[0].totalBytes)

        assertArrayEquals(ByteArray(10) { it.toByte() }, FileChunker.readBytes(chunks[0]))
    }

    @Test
    fun chunk_largeFile_multipleChunks() {
        val data = ByteArray(10) { (100 + it).toByte() }
        val f = tempFileWithBytes(data)

        val chunks = FileChunker.chunk(f, maxChunkBytes = 4)
        assertEquals(3, chunks.size)

        assertEquals(0L, chunks[0].offsetBytes)
        assertEquals(4L, chunks[0].lengthBytes)
        assertEquals(4L, chunks[1].offsetBytes)
        assertEquals(4L, chunks[1].lengthBytes)
        assertEquals(8L, chunks[2].offsetBytes)
        assertEquals(2L, chunks[2].lengthBytes)

        assertArrayEquals(data.copyOfRange(0, 4), FileChunker.readBytes(chunks[0]))
        assertArrayEquals(data.copyOfRange(4, 8), FileChunker.readBytes(chunks[1]))
        assertArrayEquals(data.copyOfRange(8, 10), FileChunker.readBytes(chunks[2]))
    }

    private fun tempFileWithBytes(bytes: ByteArray): File {
        val f = File.createTempFile("chunk", ".bin")
        f.deleteOnExit()
        f.writeBytes(bytes)
        return f
    }
}
