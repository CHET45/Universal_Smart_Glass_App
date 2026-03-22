package com.fersaiyan.cyanbridge.localmodels

import com.fersaiyan.cyanbridge.localmodels.storage.LocalModelFileUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LocalModelFileUtilsTest {
    @Test
    fun sanitize_filename_normalizes_extension_and_chars() {
        val clean = LocalModelFileUtils.sanitizeFileName(" qwen 2.5@mobile ")
        assertTrue(clean.endsWith(".gguf"))
        assertFalse(clean.contains(" "))
        assertFalse(clean.contains("@"))
    }

    @Test
    fun gguf_header_detection_works() {
        val tmp = File.createTempFile("local-model", ".gguf")
        tmp.writeBytes(byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte(), 1, 2))
        assertTrue(LocalModelFileUtils.isGgufFile(tmp))
        tmp.delete()
    }

    @Test
    fun sha256_is_stable() {
        val tmp = File.createTempFile("local-model", ".gguf")
        tmp.writeText("abc")
        val first = LocalModelFileUtils.sha256Hex(tmp)
        val second = LocalModelFileUtils.sha256Hex(tmp)
        assertEquals(first, second)
        tmp.delete()
    }
}
