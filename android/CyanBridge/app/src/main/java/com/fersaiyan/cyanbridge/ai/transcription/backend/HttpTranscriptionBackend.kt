package com.fersaiyan.cyanbridge.ai.transcription.backend

import com.fersaiyan.cyanbridge.ai.transcription.TranscriptionError
import com.fersaiyan.cyanbridge.ai.transcription.chunking.FileChunk
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/**
 * HTTP-based backend skeleton (Chapter 6).
 *
 * Endpoint contract (expected / suggested):
 * POST {endpointUrl}
 * multipart/form-data fields:
 *  - file: audio bytes (this chunk)
 *  - chunk_index, chunk_count (optional)
 *  - language (optional)
 * Response: either plain text transcript or JSON containing a "text" field.
 */
class HttpTranscriptionBackend(
    private val endpointUrl: String,
    private val apiKey: String? = null,
    private val client: OkHttpClient = defaultClient(),
) : TranscriptionBackend {

    override val name: String = "http"

    override suspend fun transcribeChunk(chunk: FileChunk, languageHint: String?): String {
        if (endpointUrl.isBlank()) {
            throw NotConfiguredException("endpointUrl is blank")
        }
        if (!chunk.file.exists()) {
            throw FileNotFoundException("Audio file not found: ${chunk.file.absolutePath}")
        }

        val fileName = chunk.file.name
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("chunk_index", chunk.index.toString())
            .addFormDataPart("chunk_offset_bytes", chunk.offsetBytes.toString())
            .addFormDataPart("chunk_length_bytes", chunk.lengthBytes.toString())
            .addFormDataPart("total_bytes", chunk.totalBytes.toString())
            .apply {
                if (!languageHint.isNullOrBlank()) addFormDataPart("language", languageHint)
            }
            .addFormDataPart(
                "file",
                fileName,
                FileChunkRequestBody(
                    file = chunk.file,
                    offset = chunk.offsetBytes,
                    length = chunk.lengthBytes,
                    contentType = "application/octet-stream".toMediaTypeOrNull(),
                )
            )
            .build()

        val req = Request.Builder()
            .url(endpointUrl)
            .post(body)
            .apply {
                if (!apiKey.isNullOrBlank()) {
                    header("Authorization", "Bearer $apiKey")
                }
            }
            .build()

        val resp = client.newCall(req).execute()
        resp.use {
            val code = it.code
            val respBody = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                throw HttpException(code = code, message = respBody.ifBlank { "http_error" })
            }
            return extractTranscript(respBody)
        }
    }

    private fun extractTranscript(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return ""

        // Try to pull {"text":"..."}
        val m = Regex("\\\"text\\\"\\s*:\\s*\\\"(.*?)\\\"", setOf(RegexOption.DOT_MATCHES_ALL))
            .find(trimmed)
        if (m != null) {
            return m.groupValues[1]
                .replace("\\n", "\n")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
        }
        return trimmed
    }

    private class FileChunkRequestBody(
        private val file: File,
        private val offset: Long,
        private val length: Long,
        private val contentType: okhttp3.MediaType?,
    ) : RequestBody() {

        override fun contentType(): okhttp3.MediaType? = contentType

        override fun contentLength(): Long = length

        override fun writeTo(sink: BufferedSink) {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(offset)
                val buffer = ByteArray(DEFAULT_BUF_SIZE)
                var remaining = length
                while (remaining > 0) {
                    val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                    val read = raf.read(buffer, 0, toRead)
                    if (read <= 0) break
                    sink.write(buffer, 0, read)
                    remaining -= read.toLong()
                }
            }
        }

        companion object {
            private const val DEFAULT_BUF_SIZE = 8 * 1024
        }
    }

    class HttpException(val code: Int, message: String) : IOException(message)
    class NotConfiguredException(message: String) : IOException(message)

    companion object {
        private fun defaultClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()
        }

        fun toTranscriptionError(t: Throwable): TranscriptionError {
            return when (t) {
                is NotConfiguredException -> TranscriptionError.NotConfigured(t.message ?: "not_configured")
                is FileNotFoundException -> TranscriptionError.FileNotFound(t.message ?: "file_not_found")
                is HttpException -> TranscriptionError.Http(t.message ?: "http_error", code = t.code)
                is IOException -> TranscriptionError.Network(t.message ?: "network_error", t)
                else -> TranscriptionError.Unknown(t.message ?: "unknown_error", t)
            }
        }
    }
}
