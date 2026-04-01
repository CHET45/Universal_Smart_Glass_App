package com.fersaiyan.cyanbridge.localmodels.download

import android.content.Context
import com.fersaiyan.cyanbridge.localmodels.catalog.LocalModelCatalogEntry
import com.fersaiyan.cyanbridge.localmodels.device.DeviceCapabilityService
import com.fersaiyan.cyanbridge.localmodels.storage.InstalledLocalModel
import com.fersaiyan.cyanbridge.localmodels.storage.LocalModelFileUtils
import com.fersaiyan.cyanbridge.localmodels.storage.LocalModelStorageRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

data class LocalModelDownloadProgress(
    val modelId: String,
    val downloadedBytes: Long,
    val totalBytes: Long,
) {
    val percent: Int
        get() = if (totalBytes <= 0L) 0 else ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
}

class LocalModelDownloadManager(
    private val client: OkHttpClient = OkHttpClient(),
) {
    suspend fun downloadCatalogModel(
        context: Context,
        entry: LocalModelCatalogEntry,
        authToken: String?,
        cancelled: AtomicBoolean,
        onProgress: (LocalModelDownloadProgress) -> Unit,
    ): InstalledLocalModel = withContext(Dispatchers.IO) {
        require(entry.enabled) { "Model is disabled in catalog" }
        val source = entry.sourceUrl ?: throw IllegalStateException("No direct source URL for this model")

        LocalModelStorageRepository.ensureDirs(context)
        LocalModelStorageRepository.findByCatalogId(context, entry.id)?.let { existing ->
            val f = File(existing.absolutePath)
            if (f.exists() && LocalModelFileUtils.isFileCompatibleWithFormat(f, entry.format)) {
                return@withContext existing
            }
        }

        val assessment = DeviceCapabilityService.assess(
            snapshot = DeviceCapabilityService.snapshot(context),
            entry = entry,
            requireDownloadHeadroom = true,
        )
        if (!assessment.supported) {
            throw IllegalStateException(assessment.blockers.joinToString(" "))
        }

        val tmpFile = File(
            LocalModelStorageRepository.tempDir(context),
            "${LocalModelFileUtils.sanitizeFileName(entry.expectedFilename)}.part",
        )
        if (tmpFile.exists()) tmpFile.delete()

        val requestBuilder = Request.Builder().url(source).get()
        if (!authToken.isNullOrBlank() && source.contains("huggingface.co", ignoreCase = true)) {
            requestBuilder.addHeader("Authorization", "Bearer ${authToken.trim()}")
        }
        val request = requestBuilder.build()
        val call = client.newCall(request)
        coroutineContext.ensureActive()
        if (cancelled.get()) {
            throw IllegalStateException("Download cancelled")
        }

        call.execute().use { response ->
            if (!response.isSuccessful) {
                val reason = when (response.code) {
                    401, 403 -> {
                        if (entry.gatedDownload) {
                            "gated model access denied. Accept terms on Hugging Face and set a valid token"
                        } else {
                            "authorization failed"
                        }
                    }
                    else -> "HTTP ${response.code}"
                }
                throw IllegalStateException("Download failed: $reason")
            }
            val body = response.body ?: throw IllegalStateException("Download failed: empty body")
            val serverContentLength = body.contentLength()
            val total = when {
                serverContentLength > 0L -> serverContentLength
                entry.sizeBytes > 0L -> entry.sizeBytes
                else -> 0L
            }

            val free = LocalModelStorageRepository.availableStorageBytes(context)
            if (total > 0 && free <= total + 250L * 1024L * 1024L) {
                throw IllegalStateException("Not enough free space to download this model")
            }

            body.byteStream().use { input ->
                FileOutputStream(tmpFile).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var downloaded = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        if (cancelled.get()) {
                            call.cancel()
                            throw IllegalStateException("Download cancelled")
                        }

                        val n = input.read(buffer)
                        if (n <= 0) break
                        output.write(buffer, 0, n)
                        downloaded += n
                        onProgress(
                            LocalModelDownloadProgress(
                                modelId = entry.id,
                                downloadedBytes = downloaded,
                                totalBytes = total,
                            ),
                        )
                    }
                    output.flush()

                    if (serverContentLength > 0L && downloaded != serverContentLength) {
                        throw IllegalStateException(
                            "Download incomplete: expected ${serverContentLength} bytes, got $downloaded bytes",
                        )
                    }
                }
            }
        }

        if (!LocalModelFileUtils.isFileCompatibleWithFormat(tmpFile, entry.format)) {
            tmpFile.delete()
            throw IllegalStateException(
                "Downloaded file is not a valid ${entry.format} model package. " +
                    "For gated models, verify Hugging Face token + accepted terms.",
            )
        }

        val fileSha = LocalModelFileUtils.sha256Hex(tmpFile)
        if (!entry.sha256.isNullOrBlank() && !entry.sha256.equals(fileSha, ignoreCase = true)) {
            tmpFile.delete()
            throw IllegalStateException("Checksum mismatch for downloaded model")
        }

        val finalName = LocalModelFileUtils.sanitizeFileName(entry.expectedFilename)
        val finalFile = File(LocalModelStorageRepository.modelsDir(context), finalName)
        if (finalFile.exists()) {
            finalFile.delete()
        }
        if (!tmpFile.renameTo(finalFile)) {
            tmpFile.copyTo(finalFile, overwrite = true)
            tmpFile.delete()
        }

        LocalModelStorageRepository.registerCatalogModel(context, entry, finalFile)
    }
}
