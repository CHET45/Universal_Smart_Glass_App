package com.fersaiyan.cyanbridge.ai.transcription.moonshine

import ai.moonshine.voice.JNI
import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

/**
 * Downloads + installs Moonshine Voice models at runtime.
 *
 * NOTE: Moonshine's published Maven artifact currently declares minSdk=35.
 * In this app we vendor Moonshine and build JNI locally (see :moonshine-voice module),
 * so we can keep app minSdk=24.
 */
object MoonshineModelManager {
    private const val TAG = "MoonshineModel"

    data class Progress(
        val percent: Int,
        val message: String,
    )

    enum class ModelKind(
        val id: String,
        val baseUrl: String,
        val modelArch: Int,
        val components: List<String>,
    ) {
        // Requested default: Small Streaming English
        SMALL_STREAMING_EN(
            id = "small-streaming-en",
            baseUrl = "https://download.moonshine.ai/model/small-streaming-en/quantized",
            modelArch = JNI.MOONSHINE_MODEL_ARCH_SMALL_STREAMING,
            components = listOf(
                "adapter.ort",
                "cross_kv.ort",
                "decoder_kv.ort",
                "encoder.ort",
                "frontend.ort",
                "streaming_config.json",
                "tokenizer.bin",
            ),
        ),
    }

    fun chooseDefault(languageHint: String? = null): ModelKind {
        // For now we only ship EN model. (Moonshine notes non-English license restrictions.)
        return ModelKind.SMALL_STREAMING_EN
    }

    fun modelDir(context: Context, kind: ModelKind): File {
        return File(context.filesDir, "moonshine/${kind.id}")
    }

    data class Validation(
        val ok: Boolean,
        val problems: List<String>,
        val topLevel: List<String>,
    )

    fun isInstalled(context: Context, kind: ModelKind): Boolean {
        return validateDir(modelDir(context, kind), kind).ok
    }

    fun validationReport(dir: File, kind: ModelKind): String {
        val v = validateDir(dir, kind)
        val parts = mutableListOf<String>()
        parts += "path=${dir.absolutePath}"
        parts += "exists=${dir.exists()}"
        parts += "topLevel=${v.topLevel}"
        if (!v.ok) parts += "problems=${v.problems.joinToString("; ")}" 
        return parts.joinToString(" | ")
    }

    private fun validateDir(dir: File, kind: ModelKind): Validation {
        val problems = mutableListOf<String>()
        val topLevel = dir.listFiles()?.map { it.name }?.sorted()?.take(40) ?: emptyList()

        if (!dir.exists() || !dir.isDirectory) {
            problems += "modelDir missing"
            return Validation(ok = false, problems = problems, topLevel = topLevel)
        }

        for (c in kind.components) {
            val f = File(dir, c)
            if (!f.exists() || f.length() <= 0L) {
                problems += "missing:$c"
            }
        }

        return Validation(ok = problems.isEmpty(), problems = problems, topLevel = topLevel)
    }

    suspend fun installIfNeeded(
        context: Context,
        kind: ModelKind,
        onProgress: (Progress) -> Unit = {},
    ): File {
        val dir = modelDir(context, kind)
        if (isInstalled(context, kind)) return dir

        dir.mkdirs()

        val total = kind.components.size.coerceAtLeast(1)
        val client = OkHttpClient.Builder().build()

        for ((idx, component) in kind.components.withIndex()) {
            val url = "${kind.baseUrl}/$component"
            val out = File(dir, component)

            val basePct = (idx * 100) / total
            val maxSpan = (100 / total).coerceAtLeast(1)

            onProgress(Progress(basePct.coerceIn(0, 99), "Downloading $component…"))

            downloadToFile(client, url, out) { bytesRead, contentLen ->
                val filePct = if (contentLen > 0L) ((bytesRead * 100L) / contentLen).toInt().coerceIn(0, 100) else 0
                val pct = (basePct + (filePct * maxSpan / 100)).coerceIn(0, 99)
                onProgress(Progress(pct, "Downloading $component… ${filePct}%"))
            }
        }

        val validation = validateDir(dir, kind)
        if (!validation.ok) {
            val msg = "Moonshine model install failed: ${validation.problems.joinToString()} | ${validationReport(dir, kind)}"
            Log.e(TAG, msg)
            runCatching { dir.deleteRecursively() }
            throw IllegalStateException(msg)
        }

        Log.i(TAG, "Installed Moonshine model ${kind.id} to ${dir.absolutePath}")
        onProgress(Progress(100, "Model installed"))
        return dir
    }

    private fun downloadToFile(
        client: OkHttpClient,
        url: String,
        out: File,
        onProgress: (bytesRead: Long, contentLength: Long) -> Unit,
    ) {
        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("Failed to download: HTTP ${resp.code} url=$url")
            }
            val body = resp.body ?: throw IllegalStateException("Empty response body: $url")
            val contentLen = body.contentLength()

            val parent = out.parentFile ?: throw IllegalStateException("Invalid output path: ${out.absolutePath}")
            parent.mkdirs()

            val tmp = File(parent, out.name + ".part")
            if (tmp.exists()) tmp.delete()

            FileOutputStream(tmp).use { fos ->
                val buf = ByteArray(256 * 1024)
                var readTotal = 0L
                body.byteStream().use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        fos.write(buf, 0, n)
                        readTotal += n
                        onProgress(readTotal, contentLen)
                    }
                }
                fos.flush()

                if (contentLen > 0L && readTotal != contentLen) {
                    runCatching { tmp.delete() }
                    throw IllegalStateException("Download incomplete: got=${readTotal}B expected=${contentLen}B url=$url")
                }
            }

            if (out.exists()) out.delete()
            if (!tmp.renameTo(out)) {
                tmp.copyTo(out, overwrite = true)
                runCatching { tmp.delete() }
            }
        }
    }
}
