package com.fersaiyan.cyanbridge.localmodels.storage

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

object LocalModelFileUtils {
    private val unsafeChars = Regex("[^A-Za-z0-9._-]+")

    fun sanitizeFileName(fileName: String): String {
        val trimmed = fileName.trim().ifBlank { "model.gguf" }
        val replaced = trimmed.replace(unsafeChars, "_")
        return if (replaced.endsWith(".gguf", ignoreCase = true)) {
            replaced
        } else {
            "$replaced.gguf"
        }
    }

    fun isGgufFile(file: File): Boolean {
        if (!file.exists() || !file.isFile || file.length() < 4) return false
        return runCatching {
            FileInputStream(file).use { input ->
                val header = ByteArray(4)
                if (input.read(header) != 4) return false
                header[0] == 'G'.code.toByte() &&
                    header[1] == 'G'.code.toByte() &&
                    header[2] == 'U'.code.toByte() &&
                    header[3] == 'F'.code.toByte()
            }
        }.getOrDefault(false)
    }

    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString(separator = "") { b -> "%02x".format(b) }
    }
}
