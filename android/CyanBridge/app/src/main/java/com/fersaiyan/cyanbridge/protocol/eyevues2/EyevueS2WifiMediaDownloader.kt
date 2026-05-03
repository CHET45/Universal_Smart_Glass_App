package com.fersaiyan.cyanbridge.protocol.eyevues2

import android.content.Context
import android.os.Environment
import com.fersaiyan.cyanbridge.protocol.GlassesTransferEvent
import com.fersaiyan.cyanbridge.protocol.MediaDownloadOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Locale

internal class EyevueS2WifiMediaDownloader(
    private val context: Context,
    private val host: String = P2P_HOST,
    private val port: Int = HTTP_PORT,
) {
    suspend fun waitUntilReachable(
        attempts: Int = 8,
        delayMillis: Long = 1_000L,
    ) {
        var lastError: Throwable? = null
        repeat(attempts) { attempt ->
            val reachable = runCatching {
                httpBytes(fileListUrl(), connectTimeoutMillis = 2_500, readTimeoutMillis = 2_500)
            }.onFailure { lastError = it }.isSuccess
            if (reachable) return
            if (attempt < attempts - 1) delay(delayMillis)
        }
        throw IOException("Eyevue S2 HTTP endpoint $host:$port is not reachable", lastError)
    }

    suspend fun fetchFileList(): List<RemoteMediaFile> {
        val body = httpText(fileListUrl())
        return parseFileList(body)
            .distinctBy { it.fullPath }
            .sortedByDescending { it.timeCode ?: 0L }
    }

    suspend fun downloadFile(item: RemoteMediaFile): String = withContext(Dispatchers.IO) {
        val target = localFileFor(item)
        target.parentFile?.mkdirs()

        val connection = openConnection(downloadUrl(item), connectTimeoutMillis = 30_000, readTimeoutMillis = 60_000)
        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IOException("HTTP $responseCode while downloading ${item.fullPath}")
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

    suspend fun deleteRemote(item: RemoteMediaFile) {
        httpText(deleteUrl(item))
    }

    fun filter(files: List<RemoteMediaFile>, options: MediaDownloadOptions): List<RemoteMediaFile> =
        files.filter { file ->
            when (file.mediaType) {
                GlassesTransferEvent.MediaType.PHOTO -> options.includePhotos
                GlassesTransferEvent.MediaType.VIDEO -> options.includeVideos
                GlassesTransferEvent.MediaType.AUDIO -> options.includeAudio
                GlassesTransferEvent.MediaType.UNKNOWN -> true
            }
        }

    private fun fileListUrl(): URL = URL("http", host, port, "/?custom=1&cmd=3015")

    private fun downloadUrl(item: RemoteMediaFile): URL = URL(
        "http",
        host,
        port,
        encodePath(item.fullPath)
    )

    private fun deleteUrl(item: RemoteMediaFile): URL = URL(
        "http",
        host,
        port,
        "/?custom=1&cmd=4003&str=${encodeDevicePathForQuery(item.fullPath)}"
    )

    private suspend fun httpText(url: URL): String = withContext(Dispatchers.IO) {
        val bytes = httpBytes(url)
        val charset = bytes.detectCharset() ?: StandardCharsets.UTF_8
        String(bytes, charset)
    }

    private suspend fun httpBytes(
        url: URL,
        connectTimeoutMillis: Int = 10_000,
        readTimeoutMillis: Int = 30_000,
    ): ByteArray = withContext(Dispatchers.IO) {
        val connection = openConnection(url, connectTimeoutMillis, readTimeoutMillis)
        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IOException("HTTP $responseCode from $url")
            }
            connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(
        url: URL,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
    ): HttpURLConnection = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = connectTimeoutMillis
        readTimeout = readTimeoutMillis
        useCaches = false
        setRequestProperty("Accept-Encoding", "identity")
        setRequestProperty("Connection", "close")
    }

    private fun parseFileList(raw: String): List<RemoteMediaFile> {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return emptyList()

        parseJsonFileList(trimmed).takeIf { it.isNotEmpty() }?.let { return it }
        parseXmlFileList(trimmed).takeIf { it.isNotEmpty() }?.let { return it }

        return parseLooseFileList(trimmed)
    }

    private fun parseJsonFileList(raw: String): List<RemoteMediaFile> = runCatching {
        val root = JSONObject(raw)
        val out = mutableListOf<RemoteMediaFile>()
        val info = root.optJSONArray("info") ?: JSONArray()
        for (i in 0 until info.length()) {
            val section = info.optJSONObject(i) ?: continue
            val folder = section.optString("folder", DEFAULT_FOLDER).ifBlank { DEFAULT_FOLDER }
            val files = section.optJSONArray("files") ?: JSONArray()
            for (j in 0 until files.length()) {
                val file = files.optJSONObject(j) ?: continue
                val name = file.optString("name")
                if (name.isBlank()) continue
                val path = normalizePath(folder, name)
                out += RemoteMediaFile(
                    name = name,
                    fullPath = path,
                    size = file.optLong("size", -1L).takeIf { it >= 0L },
                    timeText = file.optString("createtimestr").takeIf { it.isNotBlank() },
                    attr = file.optInt("type", -1).takeIf { it >= 0 },
                )
            }
        }
        out
    }.getOrDefault(emptyList())

    private fun parseXmlFileList(raw: String): List<RemoteMediaFile> = runCatching {
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
            setInput(raw.byteInputStream(), "UTF-8")
        }

        val out = mutableListOf<RemoteMediaFile>()
        val current = mutableMapOf<String, String>()
        var currentTag: String? = null
        var insideFile = false
        var event = parser.eventType

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name.orEmpty().lowercase(Locale.US)
                    currentTag = name
                    if (name in FILE_ITEM_TAGS) {
                        insideFile = true
                        current.clear()
                    }
                }

                XmlPullParser.TEXT -> {
                    val tag = currentTag ?: ""
                    if (insideFile && tag in FILE_FIELD_TAGS) {
                        current[tag] = parser.text.orEmpty().trim()
                    }
                }

                XmlPullParser.END_TAG -> {
                    val name = parser.name.orEmpty().lowercase(Locale.US)
                    if (name in FILE_ITEM_TAGS && insideFile) {
                        current.toRemoteMediaFile()?.let(out::add)
                        current.clear()
                        insideFile = false
                    }
                    currentTag = null
                }
            }
            event = parser.next()
        }
        out
    }.getOrDefault(emptyList())

    private fun parseLooseFileList(raw: String): List<RemoteMediaFile> {
        val pathRegex = Regex("/?(?:DCIM|PHOTO|VIDEO|AUDIO|RECORD)[^\\s<>'\\\"]+\\.(?:jpg|jpeg|png|mp4|mov|avi|wav|aac|amr|opus|m4a)", RegexOption.IGNORE_CASE)
        return pathRegex.findAll(raw)
            .map { it.value }
            .map { path ->
                RemoteMediaFile(
                    name = path.substringAfterLast('/'),
                    fullPath = path.ensureLeadingSlash(),
                )
            }
            .toList()
    }

    private fun Map<String, String>.toRemoteMediaFile(): RemoteMediaFile? {
        val name = firstValue("name", "filename", "file") ?: return null
        val explicitPath = firstValue("fpath", "path", "filepath", "url")
        val folder = firstValue("folder", "dir", "directory") ?: DEFAULT_FOLDER
        val fullPath = explicitPath?.takeIf { it.isNotBlank() }?.ensureLeadingSlash()
            ?: normalizePath(folder, name)
        return RemoteMediaFile(
            name = name.substringAfterLast('/'),
            fullPath = fullPath,
            size = firstValue("size", "length")?.toLongOrNull(),
            timeCode = firstValue("timecode", "ctime", "mtime")?.toLongOrNull(),
            timeText = firstValue("time", "createtime", "createtimestr"),
            attr = firstValue("attr", "type")?.toIntOrNull(),
        )
    }

    private fun Map<String, String>.firstValue(vararg keys: String): String? {
        for (key in keys) {
            val value = this[key]?.trim()
            if (!value.isNullOrBlank()) return value
        }
        return null
    }

    private fun localFileFor(item: RemoteMediaFile): File {
        val root = context.getExternalFilesDir(Environment.DIRECTORY_DCIM)
            ?: context.filesDir
        val typeDir = when (item.mediaType) {
            GlassesTransferEvent.MediaType.PHOTO -> "photos"
            GlassesTransferEvent.MediaType.VIDEO -> "videos"
            GlassesTransferEvent.MediaType.AUDIO -> "audio"
            GlassesTransferEvent.MediaType.UNKNOWN -> "other"
        }
        return uniqueFile(File(File(root, "EyevueS2"), typeDir), item.name)
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

    private fun normalizePath(folder: String, name: String): String {
        if (name.startsWith("/")) return name
        val cleanFolder = folder.ifBlank { DEFAULT_FOLDER }.trimEnd('/')
        return "$cleanFolder/$name".ensureLeadingSlash()
    }

    private fun encodePath(path: String): String = path
        .ensureLeadingSlash()
        .split('/')
        .joinToString("/") { segment ->
            if (segment.isEmpty()) "" else URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
        }

    private fun encodeDevicePathForQuery(path: String): String = encodePath(path)

    private fun String.ensureLeadingSlash(): String = if (startsWith("/")) this else "/$this"

    private fun ByteArray.detectCharset(): Charset? {
        return when {
            size >= 3 && this[0] == 0xEF.toByte() && this[1] == 0xBB.toByte() && this[2] == 0xBF.toByte() -> StandardCharsets.UTF_8
            else -> null
        }
    }

    data class RemoteMediaFile(
        val name: String,
        val fullPath: String,
        val size: Long? = null,
        val timeCode: Long? = null,
        val timeText: String? = null,
        val attr: Int? = null,
    ) {
        val mediaType: GlassesTransferEvent.MediaType = inferMediaType(name, attr)
    }

    companion object {
        const val P2P_HOST = "192.168.49.207"
        const val AP_HOST = "192.168.1.254"
        const val HTTP_PORT = 80
        private const val DEFAULT_FOLDER = "/DCIM/100HUNTI"
        private val FILE_ITEM_TAGS = setOf("file", "item", "files")
        private val FILE_FIELD_TAGS = setOf(
            "name",
            "filename",
            "file",
            "fpath",
            "path",
            "filepath",
            "url",
            "folder",
            "dir",
            "directory",
            "size",
            "length",
            "timecode",
            "ctime",
            "mtime",
            "time",
            "createtime",
            "createtimestr",
            "attr",
            "type",
        )

        private fun inferMediaType(name: String, attr: Int?): GlassesTransferEvent.MediaType {
            val lower = name.lowercase(Locale.US)
            return when {
                attr == 1 || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") -> GlassesTransferEvent.MediaType.PHOTO
                attr == 3 || lower.endsWith(".mp4") || lower.endsWith(".mov") || lower.endsWith(".avi") -> GlassesTransferEvent.MediaType.VIDEO
                lower.endsWith(".wav") || lower.endsWith(".aac") || lower.endsWith(".amr") || lower.endsWith(".opus") || lower.endsWith(".m4a") -> GlassesTransferEvent.MediaType.AUDIO
                else -> GlassesTransferEvent.MediaType.UNKNOWN
            }
        }
    }
}
