package com.fersaiyan.cyanbridge.localmodels.storage

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

object LocalModelFileUtils {
    private val unsafeChars = Regex("[^A-Za-z0-9._-]+")
    private val knownExtensions = listOf(".gguf", ".litertlm", ".task")

    fun sanitizeFileName(fileName: String, defaultExtension: String = ".gguf"): String {
        val normalizedDefault = if (defaultExtension.startsWith(".")) {
            defaultExtension.lowercase()
        } else {
            ".${defaultExtension.lowercase()}"
        }
        val trimmed = fileName.trim().ifBlank { "model$normalizedDefault" }
        val replaced = trimmed.replace(unsafeChars, "_")
        return if (knownExtensions.any { replaced.endsWith(it, ignoreCase = true) }) {
            replaced
        } else {
            "$replaced$normalizedDefault"
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

    fun isLiteRtPackageFile(file: File): Boolean {
        if (!file.exists() || !file.isFile || file.length() <= 1_048_576L) return false
        val name = file.name.lowercase()
        val extensionOk = name.endsWith(".litertlm") ||
            name.endsWith(".task") ||
            name.endsWith(".litertlm.part") ||
            name.endsWith(".task.part")
        if (!extensionOk) return false
        if (looksLikeTextOrHtml(file)) return false
        return true
    }

    private fun looksLikeTextOrHtml(file: File): Boolean {
        return runCatching {
            FileInputStream(file).use { input ->
                val sample = ByteArray(512)
                val n = input.read(sample)
                if (n <= 0) return@use true
                val head = String(sample, 0, n, Charsets.UTF_8).trimStart().lowercase()
                head.startsWith("<!doctype html") ||
                    head.startsWith("<html") ||
                    head.startsWith("<xml") ||
                    head.startsWith("{\"error\"") ||
                    head.startsWith("{\"message\"")
            }
        }.getOrDefault(true)
    }

    fun isSupportedModelFile(file: File): Boolean {
        return isGgufFile(file) || isLiteRtPackageFile(file)
    }

    fun isFileCompatibleWithFormat(file: File, format: String?): Boolean {
        return when (format?.lowercase()) {
            "gguf" -> isGgufFile(file)
            "litertlm", "task", "litert" -> isLiteRtPackageFile(file)
            else -> isSupportedModelFile(file)
        }
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
