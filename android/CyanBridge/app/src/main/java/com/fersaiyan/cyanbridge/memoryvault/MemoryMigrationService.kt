package com.fersaiyan.cyanbridge.memoryvault

import android.content.Context
import com.fersaiyan.cyanbridge.data.local.entity.MigrationStateEntity
import com.fersaiyan.cyanbridge.data.local.entity.MemoryChunkSources
import com.fersaiyan.cyanbridge.ui.MyApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object MemoryMigrationService {
    private const val MIGRATION_KEY = "memory_vault_v1"
    private const val STATUS_COMPLETED = "completed"

    suspend fun ensureMigrated(context: Context) = withContext(Dispatchers.IO) {
        val dao = MyApplication.database.memoryVaultDao()
        val current = dao.getMigrationState(MIGRATION_KEY)
        if (current?.status == STATUS_COMPLETED) return@withContext

        val baseDir = File(context.filesDir, "local_agent_memory")
        if (baseDir.exists() && baseDir.isDirectory) {
            baseDir.walkTopDown()
                .filter { it.isFile }
                .forEach { file ->
                    val ref = MemoryRefMapper.forFile(context, file)
                    if (MyApplication.database.memoryVaultDao().getVaultItem(ref) != null) {
                        updateMigrationState("in_progress", ref)
                        return@forEach
                    }

                    val raw = runCatching { file.readText(Charsets.UTF_8) }.getOrDefault("")
                    if (raw.isBlank()) {
                        updateMigrationState("in_progress", ref)
                        return@forEach
                    }

                    val policy = MemoryPolicyService.classifyForMemoryRef(
                        context = context,
                        memoryRef = ref,
                        text = raw,
                        sourceTimestampMs = file.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis(),
                        provenance = "legacy_file_migration",
                    )
                    MemoryVaultService.putText(context, ref, raw, policy)

                    if (policy.sourceType == MemorySourceType.SCREEN_OCR || policy.sourceType == MemorySourceType.DERIVED_SUMMARY) {
                        runCatching { file.writeText("", Charsets.UTF_8) }
                    }

                    updateMigrationState("in_progress", ref)
                }
        }

        runCatching {
            val chunks = MyApplication.database.memoryChunkDao().listChunkRefs()
            for (c in chunks) {
                if (c.source != MemoryChunkSources.SCREEN_CAPTURE) continue
                val ref = MemoryRefMapper.forMemoryChunk(c.id)
                val existing = dao.getPolicy(ref)
                if (existing != null) continue
                val policy = MemoryPolicyService.classifyForMemoryRef(
                    context = context,
                    memoryRef = ref,
                    text = "",
                    sourceTimestampMs = c.tsMs,
                    provenance = "legacy_chunk_migration",
                )
                MemoryPolicyService.upsertPolicy(policy)
            }
        }

        updateMigrationState(STATUS_COMPLETED, null)
    }

    private suspend fun updateMigrationState(status: String, lastRef: String?) {
        MyApplication.database.memoryVaultDao().upsertMigrationState(
            MigrationStateEntity(
                migrationKey = MIGRATION_KEY,
                status = status,
                lastProcessedRef = lastRef,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }
}
