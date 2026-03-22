package com.fersaiyan.cyanbridge.ai.transcription

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

/**
 * Minimal OpenAI Whisper API client.
 *
 * - Uses streaming request body (OkHttp) to avoid loading the entire audio in memory.
 * - Requires apiKey supplied at runtime (do not commit secrets).
 */
class OpenAIWhisperTranscriptionProvider(
    private val apiKey: String,
    private val okHttpClient: OkHttpClient = OkHttpClient(),
    private val endpointUrl: String = "https://api.openai.com/v1/audio/transcriptions",
    private val model: String = "whisper-1",
) : TranscriptionProvider {

    override val name: String = "openai_whisper"

    override suspend fun transcribe(audioFile: File, mimeType: String, language: String?): String {
        if (apiKey.isBlank()) throw TranscriptionHttpException(401, "Missing API key")

        val mediaType = mimeType.toMediaType()
        val fileBody = audioFile.asRequestBody(mediaType)

        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart("response_format", "text")
            .apply {
                if (!language.isNullOrBlank()) addFormDataPart("language", language)
            }
            .addFormDataPart("file", audioFile.name, fileBody)
            .build()

        val req = Request.Builder()
            .url(endpointUrl)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(multipart)
            .build()

        val resp = okHttpClient.newCall(req).execute()
        resp.use {
            val body = it.body?.string()
            if (!it.isSuccessful) {
                throw TranscriptionHttpException(it.code, body)
            }
            return body?.trim().orEmpty()
        }
    }
}
