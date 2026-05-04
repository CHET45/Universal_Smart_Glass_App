package com.fersaiyan.cyanbridge.protocol.hsc

import android.content.Context
import android.os.Environment
import com.fersaiyan.cyanbridge.protocol.GlassesTransferEvent
import com.fersaiyan.cyanbridge.protocol.MediaDownloadOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * HTTP media import layer used by HSC/HY15 after the BLE REPORT_WIFI_API command.
 * The vendor app consumes the URL reported by the glasses directly; test HY-15
 * builds sometimes report a portless URL even though the HTTP server is on 8080,
 * so this downloader tries the reported URL first and then the same host on 8080.
 */
class HscH515HttpMediaDownloader(
    private val context: Context,
) {
    fun downloadMedia(
        apiUrl: String,
        options: MediaDownloadOptions,
    ): Flow<GlassesTransferEvent> = flow {
        val fileList = fetchFileList(apiUrl)
        val selected = fileList.files.filter { it.mediaType.isAllowedBy(options) }
        val total = selected.size

        if (total == 0) {
            emit(GlassesTransferEvent.Progress(0, 0, null))
            emit(GlassesTransferEvent.Finished)
            return@flow
        }

        var completed = 0
        for (file in selected) {
            emit(GlassesTransferEvent.Progress(completed, total, file.name))
            val localPath = downloadFile(file, fileList.effectiveApiUrl)
            if (options.deleteRemoteAfterDownload) {
                runCatching { deleteRemote(file, fileList.effectiveApiUrl) }
            }
            completed += 1
            emit(GlassesTransferEvent.FileReady(localPath, file.mediaType))
            emit(GlassesTransferEvent.Progress(completed, total, file.name))
        }

        emit(GlassesTransferEvent.Finished)
    }

    private suspend fun fetchFileList(rawApiUrl: String): FileListResponse {
        var lastError: Throwable? = null
        for (candidate in apiUrlCandidates(rawApiUrl)) {
            try {
                val text = httpText(candidate)
                val files = parseFileList(text)
                return FileListResponse(candidate, files)
            } catch (error: Throwable) {
                lastError = error
            }
        }
        throw IOException("HY15 file-list request failed for $rawApiUrl", lastError)
    }

    private suspend fun downloadFile(
        file: RemoteMediaFile,
        effectiveApiUrl: URL,
    ): String = withContext(Dispatchers.IO) {
        val url = resolveFileUrl(file, effectiveApiUrl)
        val target = localFileFor(file)
        val connection = openConnection(url, connectTimeoutMillis = 10_000, readTimeoutMillis = 60_000)
        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IOException("HTTP $responseCode from $url")
            }
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                }
            }
            target.absolutePath
        } catch (error: Throwable) {
            if (target.exists() && target.length() == 0L) target.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun deleteRemote(
        file: RemoteMediaFile,
        effectiveApiUrl: URL,
    ) = withContext(Dispatchers.IO) {
        val path = file.remotePath ?: file.url ?: return@withContext
        val deleteUrl = URL(
            effectiveApiUrl.protocol,
            effectiveApiUrl.host,
            normalizedPort(effectiveApiUrl),
            "/api/glass/file-delete"
        )
        val connection = (deleteUrl.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        try {
            val body = JSONObject().put("files", JSONArray().put(path)).toString()
            connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            if (connection.responseCode !in 200..299) {
                throw IOException("HTTP ${connection.responseCode} from $deleteUrl")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun parseFileList(text: String): List<RemoteMediaFile> {
        val trimmed = text.trim()
        if (trimmed.startsWith("[")) {
            return parseFilesArray(JSONArray(trimmed))
        }

        val root = JSONObject(trimmed)
        val payload = when {
            root.has("files") -> root
            root.optJSONObject("data") != null -> root.optJSONObject("data")!!
            root.optJSONObject("payload") != null -> root.optJSONObject("payload")!!
            root.optString("data").trim().startsWith("{") -> JSONObject(root.optString("data"))
            root.optString("payload").trim().startsWith("{") -> JSONObject(root.optString("payload"))
            else -> root
        }

        return parseFilesArray(payload.optJSONArray("files") ?: JSONArray())
    }

    private fun parseFilesArray(files: JSONArray): List<RemoteMediaFile> {
        val result = mutableListOf<RemoteMediaFile>()
        for (index in 0 until files.length()) {
            val value = files.opt(index)
            val item = when (value) {
                is JSONObject -> value.toRemoteMediaFile()
                is String -> RemoteMediaFile(
                    name = value.substringAfterLast('/'),
                    url = value,
                    remotePath = if (value.startsWith("/")) value else null,
                    size = null,
                )
                else -> null
            }
            if (item != null) result += item
        }
        return result
    }

    private fun JSONObject.toRemoteMediaFile(): RemoteMediaFile? {
        val name = firstString("name", "filename", "file", "fileName")
            ?: firstString("url", "path")?.substringAfterLast('/')
            ?: return null
        val url = firstString("url", "downloadUrl", "path", "relativeUrl")
        return RemoteMediaFile(
            name = name.substringAfterLast('/'),
            url = url,
            remotePath = url?.takeIf { it.startsWith("/") },
            size = firstLong("size", "length", "fileSize"),
        )
    }

    private fun JSONObject.firstString(vararg keys: String): String? {
        for (key in keys) {
            val value = optString(key, "").trim()
            if (value.isNotBlank()) return value
        }
        return null
    }

    private fun JSONObject.firstLong(vararg keys: String): Long? {
        for (key in keys) {
            if (has(key)) {
                val value = optLong(key, -1L)
                if (value >= 0L) return value
            }
        }
        return null
    }

    private fun apiUrlCandidates(rawApiUrl: String): List<URL> {
        val raw = rawApiUrl.trim()
        val base = URL(raw)
        val primary = when {
            base.path.endsWith("/file-list") -> base
            base.path.endsWith("/api/glass") || base.path.endsWith("/api/glass/") -> URL(
                base.protocol,
                base.host,
                normalizedPort(base),
                base.path.trimEnd('/') + "/file-list"
            )
            else -> base
        }

        val candidates = linkedSetOf<URL>()
        candidates += primary
        if (base.port == -1 && base.protocol.equals("http", ignoreCase = true)) {
            candidates += URL(primary.protocol, primary.host, FALLBACK_HTTP_PORT, primary.file)
        }
        return candidates.toList()
    }

    private suspend fun httpText(url: URL): String = withContext(Dispatchers.IO) {
        val connection = openConnection(url, connectTimeoutMillis = 10_000, readTimeoutMillis = 20_000)
        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IOException("HTTP $responseCode from $url")
            }
            val bytes = connection.inputStream.use { it.readBytes() }
            String(bytes, StandardCharsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(
        url: URL,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
    ): HttpURLConnection = (url.openConnection() as HttpURLConnection).apply {
        connectTimeout = connectTimeoutMillis
        readTimeout = readTimeoutMillis
        requestMethod = "GET"
        instanceFollowRedirects = true
    }

    private fun resolveFileUrl(file: RemoteMediaFile, effectiveApiUrl: URL): URL {
        val raw = file.url
        if (!raw.isNullOrBlank()) {
            if (raw.startsWith("http://") || raw.startsWith("https://")) return URL(raw)
            if (raw.startsWith("/")) {
                return URL(effectiveApiUrl.protocol, effectiveApiUrl.host, normalizedPort(effectiveApiUrl), raw)
            }
        }

        val encodedName = URLEncoder.encode(file.name, "UTF-8").replace("+", "%20")
        return URL(
            effectiveApiUrl.protocol,
            effectiveApiUrl.host,
            normalizedPort(effectiveApiUrl),
            "/api/glass/files/$encodedName"
        )
    }

    private fun localFileFor(file: RemoteMediaFile): File {
        val root = context.getExternalFilesDir(Environment.DIRECTORY_DCIM) ?: context.filesDir
        val typeDir = when (file.mediaType) {
            GlassesTransferEvent.MediaType.PHOTO -> "photos"
            GlassesTransferEvent.MediaType.VIDEO -> "videos"
            GlassesTransferEvent.MediaType.AUDIO -> "audio"
            GlassesTransferEvent.MediaType.UNKNOWN -> "other"
        }
        return uniqueFile(File(File(root, "HscH515"), typeDir), file.name)
    }

    private fun uniqueFile(directory: File, fileName: String): File {
        directory.mkdirs()
        val safeName = fileName.substringAfterLast('/').ifBlank { "media.bin" }
        val candidate = File(directory, safeName)
        if (!candidate.exists()) return candidate
        val base = safeName.substringBeforeLast('.', safeName)
        val ext = safeName.substringAfterLast('.', "")
        var index = 1
        while (true) {
            val next = if (ext.isBlank()) "$base-$index" else "$base-$index.$ext"
            val file = File(directory, next)
            if (!file.exists()) return file
            index += 1
        }
    }

    private fun normalizedPort(url: URL): Int = if (url.port == -1) url.defaultPort else url.port

    private fun GlassesTransferEvent.MediaType.isAllowedBy(options: MediaDownloadOptions): Boolean = when (this) {
        GlassesTransferEvent.MediaType.PHOTO -> options.includePhotos
        GlassesTransferEvent.MediaType.VIDEO -> options.includeVideos
        GlassesTransferEvent.MediaType.AUDIO -> options.includeAudio
        GlassesTransferEvent.MediaType.UNKNOWN -> true
    }

    data class FileListResponse(
        val effectiveApiUrl: URL,
        val files: List<RemoteMediaFile>,
    )

    data class RemoteMediaFile(
        val name: String,
        val url: String?,
        val remotePath: String?,
        val size: Long?,
    ) {
        val mediaType: GlassesTransferEvent.MediaType = inferMediaType(name)
    }

    companion object {
        private const val FALLBACK_HTTP_PORT = 8080

        private fun inferMediaType(name: String): GlassesTransferEvent.MediaType {
            val lower = name.lowercase()
            return when {
                lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") -> GlassesTransferEvent.MediaType.PHOTO
                lower.endsWith(".mp4") || lower.endsWith(".mov") || lower.endsWith(".avi") -> GlassesTransferEvent.MediaType.VIDEO
                lower.endsWith(".opus") || lower.endsWith(".ogg") || lower.endsWith(".wav") || lower.endsWith(".aac") || lower.endsWith(".amr") || lower.endsWith(".m4a") -> GlassesTransferEvent.MediaType.AUDIO
                else -> GlassesTransferEvent.MediaType.UNKNOWN
            }
        }
    }
}
