package com.fersaiyan.cyanbridge.protocol.heycyan

import android.app.Activity
import android.content.Context
import android.media.MediaScannerConnection
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.os.Environment
import android.util.Log
import com.fersaiyan.cyanbridge.protocol.GlassesProtocolError
import com.fersaiyan.cyanbridge.protocol.GlassesTransferEvent
import com.fersaiyan.cyanbridge.protocol.MediaDownloadOptions
import com.fersaiyan.cyanbridge.ui.bleIpBridge
import com.fersaiyan.cyanbridge.ui.wifi.p2p.WifiP2pManagerSingleton
import com.oudmon.ble.base.communication.LargeDataHandler
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyListener
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyRsp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

internal class HeyCyanWifiMediaDownloader(
    private val activity: Activity,
    private val options: MediaDownloadOptions,
    private val emit: (GlassesTransferEvent) -> Boolean,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val p2pManager = WifiP2pManagerSingleton.getInstance(activity.applicationContext)
    private val startedHttpImport = AtomicBoolean(false)
    private val finished = AtomicBoolean(false)
    private val hostCandidates = linkedSetOf<String>()

    private var boundNetwork: Network? = null
    private var p2pInfo: WifiP2pInfo? = null
    private var p2pCallback: WifiP2pManagerSingleton.WifiP2pCallback? = null

    private val notifyListener = object : GlassesDeviceNotifyListener() {
        override fun parseData(cmdType: Int, response: GlassesDeviceNotifyRsp) {
            val payload = runCatching { response.loadData }.getOrNull() ?: return
            parseIpAddresses(payload).forEach { ip ->
                Log.i(TAG, "Detected HeyCyan Wi-Fi host from BLE notify: $ip")
                hostCandidates.add(ip)
            }

            parseHttpUrls(payload).forEach { url ->
                scope.launch { downloadExplicitUrl(url) }
            }

            if (hostCandidates.isNotEmpty()) {
                beginHttpImportOnce("ble_notify")
            }
        }
    }

    fun start() {
        emit(GlassesTransferEvent.Started)

        p2pCallback = buildCallback().also { callback ->
            p2pManager.addCallback(callback)
        }

        runCatching {
            LargeDataHandler.getInstance().addOutDeviceListener(HEY_CYAN_ALBUM_NOTIFY_CMD_TYPE, notifyListener)
        }.onFailure { error ->
            fail("NOTIFY_LISTENER_FAILED", "Failed to register HeyCyan album notify listener", error)
        }

        runCatching {
            p2pManager.registerReceiver()
            p2pManager.resetFailCount()
            p2pManager.resetDeviceP2p()
            p2pManager.startPeerDiscovery()
        }.onFailure { error ->
            fail("P2P_START_FAILED", "Failed to start HeyCyan Wi-Fi Direct discovery", error)
        }

        scope.launch {
            delay(OUTER_TIMEOUT_MS)
            if (!finished.get()) {
                fail("P2P_DOWNLOAD_TIMEOUT", "Timed out waiting for HeyCyan Wi-Fi media transfer")
            }
        }
    }

    fun close() {
        finished.set(true)
        p2pCallback?.let { p2pManager.removeCallback(it) }
        p2pCallback = null

        runCatching { removeNotifyListener() }
        runCatching { p2pManager.unregisterReceiver() }
        runCatching { p2pManager.cancelP2pConnection() }
        runCatching { bindProcessToNetwork(null) }
        scope.cancel()
    }

    private fun buildCallback(): WifiP2pManagerSingleton.WifiP2pCallback =
        object : WifiP2pManagerSingleton.WifiP2pCallback {
            override fun onWifiP2pEnabled() = Unit

            override fun onWifiP2pDisabled() {
                fail("P2P_DISABLED", "Wi-Fi Direct is disabled")
            }

            override fun onPeerDiscoveryStarted() = Unit

            override fun onPeerDiscoveryFailed(reason: Int) {
                Log.w(TAG, "HeyCyan P2P peer discovery failed: $reason")
            }

            override fun onPeersChanged(peers: Collection<WifiP2pDevice>) {
                val candidate = peers.firstOrNull { it.isLikelyHeyCyanPeer() } ?: peers.firstOrNull()
                if (candidate == null) {
                    Log.d(TAG, "HeyCyan P2P peers changed, but no peer is available")
                    return
                }
                Log.i(TAG, "Connecting to HeyCyan P2P peer: ${candidate.deviceName}/${candidate.deviceAddress}")
                p2pManager.connectToDevice(candidate)
            }

            override fun onThisDeviceChanged(device: WifiP2pDevice) = Unit

            override fun onConnectRequestSent() = Unit

            override fun onConnectRequestFailed(reason: Int) {
                Log.w(TAG, "HeyCyan P2P connect request failed: $reason")
            }

            override fun onConnected(info: WifiP2pInfo) {
                if (!info.groupFormed) {
                    Log.w(TAG, "HeyCyan P2P callback received without formed group")
                    return
                }

                p2pInfo = info
                info.groupOwnerAddress?.hostAddress?.let { groupOwnerIp ->
                    hostCandidates.add(groupOwnerIp)
                }

                bleIpBridge.ip.value?.let { hostCandidates.add(it) }
                bindBestP2pNetwork()
                beginHttpImportOnce("p2p_connected")
            }

            override fun onDisconnected() {
                if (!finished.get()) {
                    Log.w(TAG, "HeyCyan P2P disconnected before media import finished")
                }
            }

            override fun connecting() = Unit
            override fun cancelConnect() = Unit
            override fun cancelConnectFail(reason: Int) = Unit
            override fun retryAlsoFailed() {
                fail("P2P_RETRY_FAILED", "HeyCyan Wi-Fi Direct retry failed")
            }
        }

    private fun beginHttpImportOnce(reason: String) {
        if (!startedHttpImport.compareAndSet(false, true)) return

        scope.launch {
            delay(400)
            val result = withContext(Dispatchers.IO) {
                importFromResolvedHosts(reason)
            }

            if (result > 0) {
                finished.set(true)
                emit(GlassesTransferEvent.Finished)
                close()
            } else if (!finished.get()) {
                startedHttpImport.set(false)
                delay(1_000)
                if (hostCandidates.isNotEmpty()) {
                    beginHttpImportOnce("retry_after_empty_result")
                }
            }
        }
    }

    private suspend fun downloadExplicitUrl(url: URL) {
        if (!startedHttpImport.compareAndSet(false, true)) return

        val count = withContext(Dispatchers.IO) {
            runCatching { saveRemoteFiles(listOf(url), totalHint = 1) }.getOrElse { error ->
                fail("HTTP_DOWNLOAD_FAILED", "Failed to download HeyCyan media URL $url", error)
                0
            }
        }

        if (count > 0) {
            finished.set(true)
            emit(GlassesTransferEvent.Finished)
            close()
        }
    }

    private fun importFromResolvedHosts(reason: String): Int {
        val candidates = collectHostCandidates()
        Log.i(TAG, "Trying HeyCyan HTTP media import after $reason. candidates=$candidates")

        for (host in candidates) {
            if (!probe(host, 80) && !probe(host, 8080)) {
                continue
            }

            val urls = discoverMediaUrls(host)
            if (urls.isNotEmpty()) {
                val saved = saveRemoteFiles(urls, totalHint = urls.size)
                if (saved > 0) return saved
            }

            val direct = tryDownloadDirectBinary(host)
            if (direct > 0) return direct
        }

        return 0
    }

    private fun collectHostCandidates(): List<String> {
        bleIpBridge.ip.value?.let { hostCandidates.add(it) }
        p2pInfo?.groupOwnerAddress?.hostAddress?.let { groupOwnerIp -> hostCandidates.add(groupOwnerIp) }

        if (p2pInfo?.isGroupOwner == true) {
            for (i in 2..20) hostCandidates.add("192.168.49.$i")
            hostCandidates.add("192.168.49.1")
        } else {
            hostCandidates.add("192.168.49.1")
            for (i in 2..20) hostCandidates.add("192.168.49.$i")
        }

        return hostCandidates.distinct()
    }

    private fun bindBestP2pNetwork() {
        val connectivityManager = activity.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return

        val network = connectivityManager.allNetworks.firstOrNull { network ->
            val linkProperties = runCatching { connectivityManager.getLinkProperties(network) }.getOrNull()
            linkProperties?.linkAddresses?.any { address ->
                address.address.hostAddress?.startsWith("192.168.49.") == true
            } == true
        } ?: return

        boundNetwork = network
        bindProcessToNetwork(network)
    }

    private fun bindProcessToNetwork(network: Network?) {
        val connectivityManager = activity.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        runCatching {
            connectivityManager.bindProcessToNetwork(network)
        }.onFailure { error ->
            Log.w(TAG, "bindProcessToNetwork failed", error)
        }
    }

    private fun probe(host: String, port: Int): Boolean {
        val socketFactory = boundNetwork?.socketFactory
        return runCatching {
            val socket = socketFactory?.createSocket() ?: Socket()
            socket.use {
                it.connect(InetSocketAddress(host, port), PROBE_TIMEOUT_MS)
            }
            true
        }.getOrDefault(false)
    }

    private fun discoverMediaUrls(host: String): List<URL> {
        val discovered = linkedSetOf<URL>()
        val roots = listOf("/", "/files", "/filelist", "/list", "/media", "/download", "/DCIM", "/sdcard/DCIM")

        for (port in listOf(80, 8080)) {
            for (root in roots) {
                val url = URL("http", host, port, root)
                val body = runCatching { readSmallText(url) }.getOrNull() ?: continue
                parseMediaLinks(body, url).forEach { discovered.add(it) }
            }
        }

        return discovered.filter { it.isAllowedByOptions() }
    }

    private fun readSmallText(url: URL): String? {
        val connection = openConnection(url)
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = HTTP_CONNECT_TIMEOUT_MS
            connection.readTimeout = HTTP_READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            val contentType = connection.contentType.orEmpty().lowercase(Locale.US)
            if (connection.responseCode !in 200..299) return null
            if (!contentType.contains("text") && !contentType.contains("json") && !contentType.contains("html")) return null

            connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                reader.readText().take(MAX_INDEX_BYTES)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun parseMediaLinks(body: String, baseUrl: URL): List<URL> {
        val regex = Regex("""(?i)(?:href=[\"'])?([^\"'\s<>]+\.(?:jpg|jpeg|mp4|opus|wav|aac|amr|pcm))(?:[\"'])?""")
        return regex.findAll(body)
            .mapNotNull { match ->
                val raw = match.groupValues[1].trim()
                runCatching { URL(baseUrl, raw) }.getOrNull()
            }
            .distinctBy { it.toExternalForm() }
            .toList()
    }

    private fun tryDownloadDirectBinary(host: String): Int {
        for (port in listOf(80, 8080)) {
            for (path in listOf("/", "/download", "/media")) {
                val url = URL("http", host, port, path)
                val connection = openConnection(url)
                try {
                    connection.requestMethod = "GET"
                    connection.connectTimeout = HTTP_CONNECT_TIMEOUT_MS
                    connection.readTimeout = HTTP_READ_TIMEOUT_MS
                    if (connection.responseCode !in 200..299) continue

                    val contentType = connection.contentType.orEmpty().lowercase(Locale.US)
                    if (!contentType.startsWith("image/") && !contentType.startsWith("video/") && !contentType.startsWith("audio/")) {
                        continue
                    }

                    val fileName = inferFileName(url, connection)
                    saveStream(fileName, connection.inputStream, totalHint = 1, index = 1)
                    return 1
                } finally {
                    connection.disconnect()
                }
            }
        }
        return 0
    }

    private fun saveRemoteFiles(urls: List<URL>, totalHint: Int): Int {
        var saved = 0
        for ((index, url) in urls.withIndex()) {
            val connection = openConnection(url)
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = HTTP_CONNECT_TIMEOUT_MS
                connection.readTimeout = HTTP_READ_TIMEOUT_MS
                if (connection.responseCode !in 200..299) continue

                val fileName = inferFileName(url, connection)
                emit(GlassesTransferEvent.Progress(saved, totalHint, fileName))
                val localFile = saveStream(fileName, connection.inputStream, totalHint, index + 1)
                saved += 1
                emit(
                    GlassesTransferEvent.FileReady(
                        localPath = localFile.absolutePath,
                        mediaType = fileName.mediaType(),
                    ),
                )
                emit(GlassesTransferEvent.Progress(saved, totalHint, fileName))

                if (options.deleteRemoteAfterDownload) {
                    deleteRemoteFile(url)
                }
            } catch (error: Throwable) {
                Log.w(TAG, "Failed to download HeyCyan media file: $url", error)
            } finally {
                connection.disconnect()
            }
        }
        return saved
    }

    private fun saveStream(fileName: String, input: java.io.InputStream, totalHint: Int, index: Int): File {
        val targetDir = File(activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "HeyCyan").apply {
            mkdirs()
        }
        val target = uniqueFile(targetDir, fileName)
        input.use { source ->
            FileOutputStream(target).use { sink ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    sink.write(buffer, 0, read)
                }
            }
        }

        MediaScannerConnection.scanFile(activity, arrayOf(target.absolutePath), null, null)
        emit(GlassesTransferEvent.Progress(index, totalHint, target.name))
        return target
    }

    private fun openConnection(url: URL): HttpURLConnection {
        val connection = if (boundNetwork != null) {
            boundNetwork!!.openConnection(url)
        } else {
            url.openConnection()
        }
        return connection as HttpURLConnection
    }

    private fun deleteRemoteFile(url: URL) {
        val connection = openConnection(url)
        try {
            connection.requestMethod = "DELETE"
            connection.connectTimeout = HTTP_CONNECT_TIMEOUT_MS
            connection.readTimeout = HTTP_READ_TIMEOUT_MS
            connection.responseCode
        } catch (error: Throwable) {
            Log.w(TAG, "Failed to delete remote HeyCyan file: $url", error)
        } finally {
            connection.disconnect()
        }
    }

    private fun inferFileName(url: URL, connection: HttpURLConnection): String {
        val disposition = connection.getHeaderField("Content-Disposition").orEmpty()
        val dispositionName = Regex("""(?i)filename\*?=(?:UTF-8'')?[\"']?([^\"';]+)""")
            .find(disposition)
            ?.groupValues
            ?.getOrNull(1)
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }

        val pathName = url.path.substringAfterLast('/').takeIf { it.contains('.') }
        val extension = when {
            connection.contentType.orEmpty().startsWith("image/", ignoreCase = true) -> ".jpg"
            connection.contentType.orEmpty().startsWith("video/", ignoreCase = true) -> ".mp4"
            connection.contentType.orEmpty().startsWith("audio/", ignoreCase = true) -> ".opus"
            else -> ".bin"
        }

        return sanitizeFileName(
            dispositionName ?: pathName ?: "heycyan_${System.currentTimeMillis()}$extension"
        )
    }

    private fun uniqueFile(dir: File, fileName: String): File {
        val baseName = fileName.substringBeforeLast('.', fileName)
        val ext = fileName.substringAfterLast('.', missingDelimiterValue = "")
        var candidate = File(dir, fileName)
        var counter = 1
        while (candidate.exists()) {
            val suffix = if (ext.isBlank()) "_$counter" else "_$counter.$ext"
            candidate = File(dir, "$baseName$suffix")
            counter += 1
        }
        return candidate
    }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).ifBlank { "heycyan_${System.currentTimeMillis()}.bin" }

    private fun URL.isAllowedByOptions(): Boolean = path.substringAfterLast('/').mediaType().isAllowedByOptions()

    private fun String.mediaType(): GlassesTransferEvent.MediaType {
        val lower = lowercase(Locale.US)
        return when {
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> GlassesTransferEvent.MediaType.PHOTO
            lower.endsWith(".mp4") -> GlassesTransferEvent.MediaType.VIDEO
            lower.endsWith(".opus") || lower.endsWith(".wav") || lower.endsWith(".aac") || lower.endsWith(".amr") || lower.endsWith(".pcm") -> GlassesTransferEvent.MediaType.AUDIO
            else -> GlassesTransferEvent.MediaType.UNKNOWN
        }
    }

    private fun GlassesTransferEvent.MediaType.isAllowedByOptions(): Boolean = when (this) {
        GlassesTransferEvent.MediaType.PHOTO -> options.includePhotos
        GlassesTransferEvent.MediaType.VIDEO -> options.includeVideos
        GlassesTransferEvent.MediaType.AUDIO -> options.includeAudio
        GlassesTransferEvent.MediaType.UNKNOWN -> true
    }

    private fun parseIpAddresses(payload: ByteArray): List<String> {
        val text = payload.toString(StandardCharsets.UTF_8)
        return IPV4_REGEX.findAll(text).map { it.value }.toList()
    }

    private fun parseHttpUrls(payload: ByteArray): List<URL> {
        val text = payload.toString(StandardCharsets.UTF_8)
        return HTTP_URL_REGEX.findAll(text)
            .mapNotNull { match -> runCatching { URL(match.value) }.getOrNull() }
            .filter { it.path.substringAfterLast('/').mediaType().isAllowedByOptions() }
            .toList()
    }

    private fun removeNotifyListener() {
        val handler = LargeDataHandler.getInstance()
        val method = handler.javaClass.methods.firstOrNull { method ->
            method.name == "removeOutDeviceListener" && method.parameterTypes.size == 1
        } ?: return
        method.invoke(handler, HEY_CYAN_ALBUM_NOTIFY_CMD_TYPE)
    }

    private fun fail(code: String, message: String, cause: Throwable? = null) {
        if (finished.compareAndSet(false, true)) {
            emit(
                GlassesTransferEvent.Failed(
                    GlassesProtocolError(
                        code = code,
                        message = message,
                        cause = cause,
                    ),
                ),
            )
            close()
        }
    }

    private fun WifiP2pDevice.isLikelyHeyCyanPeer(): Boolean {
        val name = runCatching { deviceName.orEmpty().lowercase(Locale.US) }.getOrDefault("")
        return name.contains("cyan") || name.contains("hey") || name.contains("glass") || name.contains("direct")
    }

    companion object {
        private const val TAG = "HeyCyanWifiDownload"
        private const val HEY_CYAN_ALBUM_NOTIFY_CMD_TYPE = 2
        private const val OUTER_TIMEOUT_MS = 45_000L
        private const val PROBE_TIMEOUT_MS = 450
        private const val HTTP_CONNECT_TIMEOUT_MS = 1_500
        private const val HTTP_READ_TIMEOUT_MS = 8_000
        private const val MAX_INDEX_BYTES = 512_000
        private val IPV4_REGEX = Regex("""\b(?:(?:25[0-5]|2[0-4]\d|[01]?\d\d?)\.){3}(?:25[0-5]|2[0-4]\d|[01]?\d\d?)\b""")
        private val HTTP_URL_REGEX = Regex("""https?://[^\s\"'<>]+""")
    }
}
