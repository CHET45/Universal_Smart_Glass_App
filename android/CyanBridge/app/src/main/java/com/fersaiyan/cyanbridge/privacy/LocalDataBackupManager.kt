package com.fersaiyan.cyanbridge.privacy

import android.content.Context
import android.net.Uri
import com.fersaiyan.cyanbridge.chat.ChatRole
import com.fersaiyan.cyanbridge.chat.ChatStore
import com.fersaiyan.cyanbridge.memoryvault.MemoryVaultService
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object LocalDataBackupManager {
    private const val ROOT = "cyanbridge_backup_v1/"
    private const val MANIFEST_ENTRY = ROOT + "manifest.json"
    private const val CHATS_ENTRY = ROOT + "chats.json"
    private const val PREFS_ENTRY = ROOT + "prefs.json"
    private const val MEMORY_PREFIX = ROOT + "files/local_agent_memory/"
    private const val RECORDINGS_PREFIX = ROOT + "external/recordings/"
    private const val VAULT_ENTRY = ROOT + "vault/vault_snapshot.json"

    data class ExportResult(
        val threadCount: Int,
        val messageCount: Int,
        val prefsFileCount: Int,
        val memoryFileCount: Int,
        val recordingFileCount: Int,
        val vaultItemCount: Int,
    )

    data class ImportResult(
        val threadCount: Int,
        val messageCount: Int,
        val prefsFileCount: Int,
        val memoryFileCount: Int,
        val recordingFileCount: Int,
        val vaultItemCount: Int,
    )

    fun exportToZip(context: Context, uri: Uri): ExportResult {
        val appCtx = context.applicationContext
        val resolver = appCtx.contentResolver

        resolver.openOutputStream(uri)?.use { stream ->
            ZipOutputStream(stream.buffered()).use { zip ->
                val chats = buildChatsJson()
                val prefs = buildPrefsJson(appCtx)

                putText(zip, MANIFEST_ENTRY, buildManifestJson().toString(2))
                putText(zip, CHATS_ENTRY, chats.toString(2))
                putText(zip, PREFS_ENTRY, prefs.toString(2))
                val vaultSnapshot = MemoryVaultService.exportSnapshotJsonBlocking()
                putText(zip, VAULT_ENTRY, vaultSnapshot.toString(2))

                val memoryRoot = File(appCtx.filesDir, "local_agent_memory")
                val memoryFiles = addDirectoryRecursively(zip, memoryRoot, MEMORY_PREFIX)

                val recordingsRoot = File(appCtx.getExternalFilesDir(null), "recordings")
                val recordingFiles = addDirectoryRecursively(zip, recordingsRoot, RECORDINGS_PREFIX)

                return ExportResult(
                    threadCount = chats.optJSONArray("threads")?.length() ?: 0,
                    messageCount = chats.optInt("message_count", 0),
                    prefsFileCount = prefs.optJSONArray("files")?.length() ?: 0,
                    memoryFileCount = memoryFiles,
                    recordingFileCount = recordingFiles,
                    vaultItemCount = vaultSnapshot.optJSONArray("vault_items")?.length() ?: 0,
                )
            }
        }

        error("Unable to open output stream for backup destination")
    }

    fun importFromZip(context: Context, uri: Uri): ImportResult {
        val appCtx = context.applicationContext
        val resolver = appCtx.contentResolver

        val localAgentMemoryRoot = File(appCtx.filesDir, "local_agent_memory")
        val recordingsRoot = File(appCtx.getExternalFilesDir(null), "recordings")

        runCatching { localAgentMemoryRoot.deleteRecursively() }
        runCatching { recordingsRoot.deleteRecursively() }

        val clearResult = LocalDataClearer.clearAll(appCtx)
        if (clearResult.errors.isNotEmpty()) {
            throw IllegalStateException("Cannot import because existing data could not be cleared: ${clearResult.errors.joinToString()}")
        }

        var chatsJson: JSONObject? = null
        var prefsJson: JSONObject? = null
        var vaultJson: JSONObject? = null
        var memoryFiles = 0
        var recordingFiles = 0

        resolver.openInputStream(uri)?.use { stream ->
            ZipInputStream(stream.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name

                    if (!name.startsWith(ROOT)) {
                        zip.closeEntry()
                        continue
                    }

                    if (entry.isDirectory) {
                        zip.closeEntry()
                        continue
                    }

                    when {
                        name == CHATS_ENTRY -> {
                            chatsJson = JSONObject(readZipEntryText(zip))
                        }

                        name == PREFS_ENTRY -> {
                            prefsJson = JSONObject(readZipEntryText(zip))
                        }

                        name == VAULT_ENTRY -> {
                            vaultJson = JSONObject(readZipEntryText(zip))
                        }

                        name.startsWith(MEMORY_PREFIX) -> {
                            val rel = name.removePrefix(MEMORY_PREFIX)
                            if (isSafeRelativePath(rel)) {
                                val target = File(localAgentMemoryRoot, rel)
                                target.parentFile?.mkdirs()
                                target.outputStream().use { out -> zip.copyTo(out) }
                                memoryFiles += 1
                            }
                        }

                        name.startsWith(RECORDINGS_PREFIX) -> {
                            val rel = name.removePrefix(RECORDINGS_PREFIX)
                            if (isSafeRelativePath(rel)) {
                                val target = File(recordingsRoot, rel)
                                target.parentFile?.mkdirs()
                                target.outputStream().use { out -> zip.copyTo(out) }
                                recordingFiles += 1
                            }
                        }
                    }

                    zip.closeEntry()
                }
            }
        } ?: error("Unable to open selected backup file")

        val chatsResult = restoreChats(chatsJson)
        val prefsFileCount = restorePrefs(appCtx, prefsJson)
        if (vaultJson != null) {
            MemoryVaultService.importSnapshotJsonBlocking(vaultJson!!)
        }

        return ImportResult(
            threadCount = chatsResult.first,
            messageCount = chatsResult.second,
            prefsFileCount = prefsFileCount,
            memoryFileCount = memoryFiles,
            recordingFileCount = recordingFiles,
            vaultItemCount = vaultJson?.optJSONArray("vault_items")?.length() ?: 0,
        )
    }

    private fun buildManifestJson(): JSONObject {
        return JSONObject()
            .put("format", "cyanbridge_backup")
            .put("version", 1)
            .put("created_at_ms", System.currentTimeMillis())
    }

    private fun buildChatsJson(): JSONObject {
        val threads = ChatStore.listThreads().sortedBy { it.createdAt }
        val threadsArray = JSONArray()
        val messagesByThread = JSONObject()
        var messageCount = 0

        for (thread in threads) {
            threadsArray.put(
                JSONObject()
                    .put("id", thread.id)
                    .put("title", thread.title)
                    .put("created_at", thread.createdAt)
                    .put("updated_at", thread.updatedAt)
            )

            val msgs = ChatStore.listMessages(thread.id).sortedBy { it.createdAt }
            val arr = JSONArray()
            for (m in msgs) {
                arr.put(
                    JSONObject()
                        .put("role", m.role.name)
                        .put("content", m.content)
                        .put("created_at", m.createdAt)
                )
                messageCount += 1
            }
            messagesByThread.put(thread.id, arr)
        }

        return JSONObject()
            .put("threads", threadsArray)
            .put("messages", messagesByThread)
            .put("message_count", messageCount)
    }

    private fun buildPrefsJson(context: Context): JSONObject {
        val filesArray = JSONArray()
        val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
        val xmlFiles = prefsDir.listFiles { f -> f.isFile && f.name.endsWith(".xml") }
            ?.sortedBy { it.name }
            .orEmpty()

        for (xml in xmlFiles) {
            val name = xml.name.removeSuffix(".xml")
            val all = context.getSharedPreferences(name, Context.MODE_PRIVATE).all

            val entries = JSONArray()
            for ((key, value) in all) {
                val encoded = encodePrefValue(value) ?: continue
                entries.put(
                    JSONObject()
                        .put("key", key)
                        .put("type", encoded.first)
                        .put("value", encoded.second)
                )
            }

            filesArray.put(
                JSONObject()
                    .put("name", name)
                    .put("entries", entries)
            )
        }

        return JSONObject().put("files", filesArray)
    }

    private fun restoreChats(chatsJson: JSONObject?): Pair<Int, Int> {
        if (chatsJson == null) return 0 to 0

        val threads = chatsJson.optJSONArray("threads") ?: return 0 to 0
        val messagesByThread = chatsJson.optJSONObject("messages") ?: JSONObject()

        var threadCount = 0
        var messageCount = 0

        for (i in 0 until threads.length()) {
            val t = threads.optJSONObject(i) ?: continue
            val oldId = t.optString("id")
            val title = t.optString("title").ifBlank { "New chat" }
            val createdAt = t.optLong("created_at", System.currentTimeMillis())
            val updatedAt = t.optLong("updated_at", createdAt)

            val newThread = ChatStore.createThread(title = title, nowMs = createdAt)
            val messages = messagesByThread.optJSONArray(oldId) ?: JSONArray()

            for (mIdx in 0 until messages.length()) {
                val m = messages.optJSONObject(mIdx) ?: continue
                val role = runCatching {
                    ChatRole.valueOf(m.optString("role").trim().uppercase())
                }.getOrElse { ChatRole.USER }
                val content = m.optString("content").trim()
                if (content.isBlank()) continue
                val msgAt = m.optLong("created_at", updatedAt)

                ChatStore.addMessage(
                    chatId = newThread.id,
                    role = role,
                    content = content,
                    nowMs = msgAt,
                )
                messageCount += 1
            }

            ChatStore.updateThreadTitle(newThread.id, title, nowMs = updatedAt)
            threadCount += 1
        }

        return threadCount to messageCount
    }

    private fun restorePrefs(context: Context, prefsJson: JSONObject?): Int {
        if (prefsJson == null) return 0
        val files = prefsJson.optJSONArray("files") ?: return 0

        var count = 0
        for (i in 0 until files.length()) {
            val fileObj = files.optJSONObject(i) ?: continue
            val name = fileObj.optString("name").trim()
            if (name.isBlank()) continue

            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val editor = prefs.edit().clear()

            val entries = fileObj.optJSONArray("entries") ?: JSONArray()
            for (eIdx in 0 until entries.length()) {
                val entry = entries.optJSONObject(eIdx) ?: continue
                val key = entry.optString("key").trim()
                val type = entry.optString("type").trim()
                if (key.isBlank() || type.isBlank()) continue

                when (type) {
                    "string" -> editor.putString(key, entry.optString("value"))
                    "int" -> editor.putInt(key, entry.optInt("value"))
                    "long" -> editor.putLong(key, entry.optLong("value"))
                    "float" -> editor.putFloat(key, entry.optDouble("value", 0.0).toFloat())
                    "boolean" -> editor.putBoolean(key, entry.optBoolean("value"))
                    "string_set" -> {
                        val arr = entry.optJSONArray("value") ?: JSONArray()
                        val set = mutableSetOf<String>()
                        for (sIdx in 0 until arr.length()) {
                            set += arr.optString(sIdx)
                        }
                        editor.putStringSet(key, set)
                    }
                }
            }

            editor.apply()
            count += 1
        }

        return count
    }

    private fun encodePrefValue(value: Any?): Pair<String, Any?>? {
        return when (value) {
            is String -> "string" to value
            is Int -> "int" to value
            is Long -> "long" to value
            is Float -> "float" to value.toDouble()
            is Boolean -> "boolean" to value
            is Set<*> -> {
                val arr = JSONArray()
                value.filterIsInstance<String>().forEach { arr.put(it) }
                "string_set" to arr
            }
            else -> null
        }
    }

    private fun putText(zip: ZipOutputStream, entryName: String, text: String) {
        val e = ZipEntry(entryName)
        zip.putNextEntry(e)
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun addDirectoryRecursively(zip: ZipOutputStream, root: File, entryPrefix: String): Int {
        if (!root.exists() || !root.isDirectory) return 0
        var count = 0

        root.walkTopDown()
            .filter { it.isFile }
            .forEach { file ->
                val rel = file.relativeTo(root).invariantSeparatorsPath
                val entryName = entryPrefix + rel
                val entry = ZipEntry(entryName)
                zip.putNextEntry(entry)
                file.inputStream().use { input -> input.copyTo(zip) }
                zip.closeEntry()
                count += 1
            }

        return count
    }

    private fun readZipEntryText(zip: ZipInputStream): String {
        val out = ByteArrayOutputStream()
        zip.copyTo(out)
        return out.toString(Charsets.UTF_8.name())
    }

    private fun isSafeRelativePath(path: String): Boolean {
        if (path.isBlank()) return false
        if (path.startsWith("/")) return false
        return !path.split('/').any { it == ".." || it.isBlank() }
    }
}
