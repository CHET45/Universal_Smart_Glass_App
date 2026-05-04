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
    private val transferMode: WifiTransferMode = if (host == AP_HOST) {
        WifiTransferMode.AP
    } else {
        WifiTransferMode.P2P
    }

    private var activePort: Int = port

    suspend fun waitUntilReachable(
        attempts: Int = 8,
        delayMillis: Long = 1_000L,
    ) {
        var lastError: Throwable? = null
        repeat(attempts) { attempt ->
            for (candidatePort in candidatePorts()) {
                val reachable = runCatching {
                    httpBytes(
                        fileListUrl(candidatePort),
                        connectTimeoutMillis = 2_500,
                        readTimeoutMillis = 2_500
                    )
                }.onFailure { lastError = it }.isSuccess
                if (reachable) {
                    activePort = candidatePort
                    return
                }
            }
            if (attempt < attempts - 1) delay(delayMillis)
        }
        throw IOException("Eyevue S2 HTTP endpoint $host:${candidatePorts().joinToString("/")} is not reachable", lastError)
    }

    suspend fun fetchFileList(): List<RemoteMediaFile> {
        if (activePort == port) {
            runCatching { waitUntilReachable(attempts = 1, delayMillis = 0L) }
        }
        val body = httpText(fileListUrl(activePort))
        return parseFileList(body)
            .distinctBy { it.devicePath }
            .sortedWith(compareByDescending<RemoteMediaFile> { it.timeCode ?: 0L }.thenBy { it.name })
    }

    suspend fun downloadFile(item: RemoteMediaFile): String = withContext(Dispatchers.IO) {
        val target = localFileFor(item)
        target.parentFile?.mkdirs()

        val connection = openConnection(downloadUrl(item), connectTimeoutMillis = 30_000, readTimeoutMillis = 60_000)
        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IOException("HTTP $responseCode while downloading ${item.devicePath}")
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
            if (target.exists()) target.delete()
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

    private fun fileListUrl(targetPort: Int = activePort): URL = when (transferMode) {
        WifiTransferMode.P2P -> URL("http", host, targetPort, P2P_FILE_LIST_ENDPOINT)
        WifiTransferMode.AP -> URL("http", host, targetPort, AP_FILE_LIST_ENDPOINT)
    }

    private fun downloadUrl(item: RemoteMediaFile): URL = URL(
        "http",
        host,
        activePort,
        encodePath(item.httpPath)
    )

    private fun deleteUrl(item: RemoteMediaFile): URL = when (transferMode) {
        WifiTransferMode.P2P -> URL(
            "http",
            host,
            activePort,
            "$P2P_DELETE_ENDPOINT${encodeQueryValue(p2pDeletePath(item))}"
        )

        WifiTransferMode.AP -> URL(
            "http",
            host,
            activePort,
            "$AP_DELETE_ENDPOINT${encodeQueryValue(item.devicePath)}"
        )
    }

    private fun candidatePorts(): List<Int> = listOf(
        port,
        LEGACY_HTTP_PORT, // TODO поменять на 80 только для прошивок, где HTTP-сервер очков остался на дефолтном порту.
    ).distinct()

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
                val rawName = file.optString("name").trim()
                if (rawName.isBlank()) continue

                val devicePath = buildDevicePath(folder, rawName)
                val fileName = fileNameFromDevicePath(rawName).ifBlank { fileNameFromDevicePath(devicePath) }
                out += RemoteMediaFile(
                    name = fileName,
                    devicePath = devicePath,
                    httpPath = devicePath.toHttpPath(),
                    size = file.optLong("size", -1L).takeIf { it >= 0L },
                    timeCode = file.optString("timecode").toLongOrNull()
                        ?: file.optString("createtimestr").toLongOrNull(),
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
        val pathRegex = Regex(
            "(?:[A-Za-z]:)?[/\\\\]?(?:DCIM|PHOTO|VIDEO|AUDIO|RECORD)[^\\s<>'\\\"]+\\.(?:jpg|jpeg|png|mp4|mov|avi|wav|aac|amr|opus|m4a)",
            RegexOption.IGNORE_CASE
        )
        return pathRegex.findAll(raw)
            .map { it.value.trim() }
            .map { devicePath ->
                RemoteMediaFile(
                    name = fileNameFromDevicePath(devicePath),
                    devicePath = devicePath,
                    httpPath = devicePath.toHttpPath(),
                )
            }
            .toList()
    }

    private fun Map<String, String>.toRemoteMediaFile(): RemoteMediaFile? {
        val rawName = firstValue("name", "filename", "file") ?: return null
        val explicitPath = firstValue("fpath", "path", "filepath", "url")
        val folder = firstValue("folder", "dir", "directory") ?: DEFAULT_FOLDER
        val devicePath = explicitPath?.takeIf { it.isNotBlank() }
            ?: buildDevicePath(folder, rawName)

        return RemoteMediaFile(
            name = fileNameFromDevicePath(rawName).ifBlank { fileNameFromDevicePath(devicePath) },
            devicePath = devicePath,
            httpPath = devicePath.toHttpPath(),
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
        val safeName = fileName.substringAfterLast('/').substringAfterLast('\\').ifBlank { "media.bin" }
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

    private fun buildDevicePath(folder: String, name: String): String {
        val cleanName = name.trim()
        if (cleanName.looksLikeDevicePath()) return cleanName

        val cleanFolder = folder.ifBlank { DEFAULT_FOLDER }.trimEnd('/', '\\')
        return "$cleanFolder/$cleanName"
    }

    private fun p2pDeletePath(item: RemoteMediaFile): String {
        val normalized = item.devicePath.ifBlank { item.httpPath }.trim()
        if (DRIVE_PREFIX_REGEX.containsMatchIn(normalized)) {
            return normalized.replace('/', '\\')
        }

        val withoutLeadingSlash = normalized
            .ifBlank { DEFAULT_FOLDER + "/" + item.name }
            .replace('\\', '/')
            .trimStart('/')

        return "A:\\${withoutLeadingSlash.replace('/', '\\')}"
    }

    private fun String.toHttpPath(): String {
        var path = trim().replace('\\', '/')
        path = DRIVE_PREFIX_REGEX.replace(path, "")
        return path.ensureLeadingSlash()
    }

    private fun String.looksLikeDevicePath(): Boolean =
        contains('/') || contains('\\') || DRIVE_PREFIX_REGEX.containsMatchIn(this)

    private fun fileNameFromDevicePath(path: String): String = path
        .trim()
        .replace('\\', '/')
        .removePrefix("A:")
        .removePrefix("a:")
        .substringAfterLast('/')

    private fun encodePath(path: String): String = path
        .ensureLeadingSlash()
        .split('/')
        .joinToString("/") { segment ->
            if (segment.isEmpty()) "" else encodeQueryValue(segment)
        }

    private fun encodeQueryValue(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    private fun String.ensureLeadingSlash(): String = if (startsWith("/")) this else "/$this"

    private fun ByteArray.detectCharset(): Charset? {
        return when {
            size >= 3 && this[0] == 0xEF.toByte() && this[1] == 0xBB.toByte() && this[2] == 0xBF.toByte() -> StandardCharsets.UTF_8
            else -> null
        }
    }

    data class RemoteMediaFile(
        val name: String,
        val devicePath: String,
        val httpPath: String,
        val size: Long? = null,
        val timeCode: Long? = null,
        val timeText: String? = null,
        val attr: Int? = null,
    ) {
        val mediaType: GlassesTransferEvent.MediaType = inferMediaType(name, attr)
    }

    private enum class WifiTransferMode {
        P2P,
        AP,
    }

    companion object {
        const val P2P_HOST = "192.168.49.207"
        const val AP_HOST = "192.168.169.1"
        const val HTTP_PORT = 80
        private const val LEGACY_HTTP_PORT = 80 // TODO поменять на 80 для архивной P2P/AP реализации без явного порта.
        private const val DEFAULT_FOLDER = "/DCIM/100HUNTI"
        private const val P2P_FILE_LIST_ENDPOINT = "/?custom=1&cmd=3015"
        private const val AP_FILE_LIST_ENDPOINT = "/app/getfilelist"
        private const val P2P_DELETE_ENDPOINT = "/?custom=1&cmd=4003&str="
        private const val AP_DELETE_ENDPOINT = "/app/deletefile?file="
        private val DRIVE_PREFIX_REGEX = Regex("^[A-Za-z]:")
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
                attr == 2 || lower.endsWith(".mp4") || lower.endsWith(".mov") || lower.endsWith(".avi") -> GlassesTransferEvent.MediaType.VIDEO
                attr == 3 || lower.endsWith(".wav") || lower.endsWith(".aac") || lower.endsWith(".amr") || lower.endsWith(".opus") || lower.endsWith(".m4a") -> GlassesTransferEvent.MediaType.AUDIO
                else -> GlassesTransferEvent.MediaType.UNKNOWN
            }
        }
    }
}
