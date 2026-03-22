package com.fersaiyan.cyanbridge.memoryvault

import android.content.Context
import java.io.File

object MemoryRefMapper {
    fun forFile(context: Context, file: File): String {
        val base = File(context.filesDir, "local_agent_memory")
        val rel = runCatching {
            file.relativeTo(base).invariantSeparatorsPath
        }.getOrElse {
            file.absolutePath
        }
        return "file:$rel"
    }

    fun forScreenCaptureDay(date: String): String = "file:screen_captures/${date.trim()}.jsonl"

    fun forMemoryChunk(id: Long): String = "memory_chunk:$id"

    fun isScreenCaptureRef(memoryRef: String): Boolean {
        return memoryRef.startsWith("file:screen_captures/") || memoryRef.startsWith("memory_chunk:")
    }
}
