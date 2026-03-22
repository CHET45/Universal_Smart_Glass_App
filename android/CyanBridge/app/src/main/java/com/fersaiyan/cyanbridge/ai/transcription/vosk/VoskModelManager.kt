package com.fersaiyan.cyanbridge.ai.transcription.vosk

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
// (ZipInputStream no longer used; extraction uses ZipFile for robustness)

/**
 * Downloads + installs Vosk models at runtime.
 *
 * Models are large (tens of MB), so we do NOT commit them into the repo.
 */
object VoskModelManager {
    private const val TAG = "VoskModel"

    enum class ModelKind(
        val id: String,
        val url: String,
    ) {
        SMALL_PT("vosk-model-small-pt-0.3", "https://alphacephei.com/vosk/models/vosk-model-small-pt-0.3.zip"),
        SMALL_EN_US("vosk-model-small-en-us-0.15", "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"),
    }

    fun chooseDefault(languageHint: String? = null): ModelKind {
        val lang = languageHint?.lowercase(Locale.US)
            ?: Locale.getDefault().language.lowercase(Locale.US)

        return if (lang.startsWith("pt")) ModelKind.SMALL_PT else ModelKind.SMALL_EN_US
    }

    fun modelDir(context: Context, kind: ModelKind): File {
        return File(context.filesDir, "vosk/${kind.id}")
    }

    /**
     * "Installed" means "looks like a complete/valid Vosk model".
     *
     * Previously we only checked for model.conf, but partial/corrupt installs can still
     * contain model.conf while missing other required files, which then crashes at runtime
     * with "Failed to create a model".
     */
    fun isInstalled(context: Context, kind: ModelKind): Boolean {
        val dir = modelDir(context, kind)
        return validateDir(dir).ok
    }

    data class Validation(
        val ok: Boolean,
        val problems: List<String>,
        val topLevel: List<String>,
    )

    fun validationReport(dir: File): String {
        val v = validateDir(dir)
        val parts = mutableListOf<String>()
        parts += "path=${dir.absolutePath}"
        parts += "exists=${dir.exists()}"
        parts += "topLevel=${v.topLevel}"
        if (!v.ok) parts += "problems=${v.problems.joinToString("; ")}"
        return parts.joinToString(" | ")
    }

    private fun validateDir(dir: File): Validation {
        val problems = mutableListOf<String>()

        val topLevel = dir.listFiles()?.map { it.name }?.sorted()?.take(20) ?: emptyList()

        if (!dir.exists() || !dir.isDirectory) {
            problems += "modelDir missing"
            return Validation(ok = false, problems = problems, topLevel = topLevel)
        }

        fun fileOk(rel: String, minBytes: Long): Boolean {
            val f = File(dir, rel)
            return f.exists() && f.length() >= minBytes
        }

        fun requireAny(vararg rels: String, minBytes: Long = 1L) {
            if (!rels.any { fileOk(it, minBytes) }) {
                problems += "missingAny:${rels.joinToString(",")}"
            }
        }

        // Vosk model zips come in at least two common layouts:
        //  - "new" layout: am/, conf/, graph/
        //  - "old" layout: files at the top-level (final.mdl, mfcc.conf, Gr.fst, HCL*.fst, ...)
        // So validation must be flexible.

        // Optional (some models don't ship it, and Vosk can still load).
        val hasModelConf = File(dir, "model.conf").exists() || File(dir, "conf/model.conf").exists()
        if (!hasModelConf) {
            Log.w(TAG, "Vosk model has no model.conf (ok for some models): ${dir.absolutePath}")
        }

        requireAny("am/final.mdl", "final.mdl", minBytes = 1024)
        requireAny("conf/mfcc.conf", "mfcc.conf", minBytes = 64)
        requireAny("graph/Gr.fst", "Gr.fst", minBytes = 1024)
        requireAny(
            "graph/HCLr.fst",
            "graph/HCLG.fst",
            "HCLr.fst",
            "HCLG.fst",
            minBytes = 1024
        )
        requireAny("graph/disambig_tid.int", "disambig_tid.int", minBytes = 8)
        requireAny(
            "graph/phones/word_boundary.int",
            "graph/word_boundary.int",
            "word_boundary.int",
            minBytes = 8
        )

        return Validation(ok = problems.isEmpty(), problems = problems, topLevel = topLevel)
    }

    private fun looksLikeModelRoot(dir: File): Boolean {
        if (!dir.exists() || !dir.isDirectory) return false

        val hasAcoustic = File(dir, "am/final.mdl").exists() || File(dir, "final.mdl").exists()
        val hasMfcc = File(dir, "conf/mfcc.conf").exists() || File(dir, "mfcc.conf").exists()
        val hasGr = File(dir, "graph/Gr.fst").exists() || File(dir, "Gr.fst").exists()
        val hasHcl = File(dir, "graph/HCLr.fst").exists() ||
            File(dir, "graph/HCLG.fst").exists() ||
            File(dir, "HCLr.fst").exists() ||
            File(dir, "HCLG.fst").exists()

        return hasAcoustic && hasMfcc && hasGr && hasHcl
    }

    /**
     * Figure out the "real" model root inside a zip extraction staging directory.
     *
     * Vosk models come in multiple layouts; this picks the directory that contains
     * the expected core files (final.mdl, mfcc.conf, Gr.fst, HCL*.fst), either at
     * top-level or under am/conf/graph.
     */
    private fun findExtractedModelRoot(staging: File): File {
        val candidates = mutableListOf<File>()

        // New layout: <root>/am/final.mdl
        staging.walkTopDown()
            .firstOrNull { it.isFile && it.name == "final.mdl" && it.parentFile?.name == "am" }
            ?.parentFile?.parentFile
            ?.let { candidates += it }

        // Old layout: <root>/final.mdl
        staging.walkTopDown()
            .firstOrNull { it.isFile && it.name == "final.mdl" && it.parentFile?.name != "am" }
            ?.parentFile
            ?.let { candidates += it }

        // mfcc.conf can be either <root>/mfcc.conf or <root>/conf/mfcc.conf
        staging.walkTopDown()
            .firstOrNull { it.isFile && it.name == "mfcc.conf" }
            ?.parentFile
            ?.let { p -> candidates += if (p.name == "conf") (p.parentFile ?: p) else p }

        // model.conf sometimes exists at <root>/model.conf or <root>/conf/model.conf
        staging.walkTopDown()
            .firstOrNull { it.isFile && it.name == "model.conf" }
            ?.parentFile
            ?.let { p -> candidates += if (p.name == "conf") (p.parentFile ?: p) else p }

        candidates += staging

        candidates.distinct().forEach { c ->
            if (looksLikeModelRoot(c)) return c
        }

        // Fallback: scan for a directory that matches our heuristic.
        val roots = staging.walkTopDown()
            .filter { it.isDirectory }
            .filter { looksLikeModelRoot(it) }
            .toList()

        // Prefer the shallowest (shortest path) match.
        return roots.minByOrNull { it.absolutePath.length } ?: staging
    }

    data class Progress(
        val percent: Int,
        val message: String,
    )

    suspend fun installIfNeeded(
        context: Context,
        kind: ModelKind,
        onProgress: (Progress) -> Unit = {},
    ): File {
        val dir = modelDir(context, kind)
        if (isInstalled(context, kind)) return dir

        onProgress(Progress(0, "Downloading model…"))

        val tmpZip = File(context.cacheDir, "${kind.id}.zip")
        downloadToFile(kind.url, tmpZip) { bytesRead, contentLen ->
            val pct = if (contentLen > 0L) ((bytesRead * 100L) / contentLen).toInt().coerceIn(0, 99) else 0
            onProgress(Progress(pct, "Downloading… ${pct}%"))
        }

        onProgress(Progress(0, "Extracting model…"))

        // Extract to a temp dir then atomically replace.
        val staging = File(context.cacheDir, "${kind.id}_staging")
        if (staging.exists()) staging.deleteRecursively()
        staging.mkdirs()

        unzipInto(tmpZip, staging) { pct, msg -> onProgress(Progress(pct, msg)) }

        val extractedRoot = findExtractedModelRoot(staging)

        if (dir.exists()) dir.deleteRecursively()
        dir.parentFile?.mkdirs()
        extractedRoot.copyRecursively(target = dir, overwrite = true)

        runCatching { tmpZip.delete() }
        runCatching { staging.deleteRecursively() }

        val validation = validateDir(dir)
        if (!validation.ok) {
            val msg = "Model installation verification failed for ${kind.id}: ${validation.problems.joinToString()}. ${validationReport(dir)}"
            Log.e(TAG, msg)
            // If it doesn't validate, keep it out of the user's way and force a clean retry next time.
            runCatching { dir.deleteRecursively() }
            throw IllegalStateException(msg)
        } else {
            Log.i(TAG, "Installed model ${kind.id} to ${dir.absolutePath}")
        }

        onProgress(Progress(100, "Model installed"))
        return dir
    }

    private fun downloadToFile(
        url: String,
        out: File,
        onProgress: (bytesRead: Long, contentLength: Long) -> Unit,
    ) {
        val client = OkHttpClient.Builder().build()
        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("Failed to download model: HTTP ${resp.code}")
            }
            val body = resp.body ?: throw IllegalStateException("Empty response body")
            val contentLen = body.contentLength() // may be -1 if unknown

            // Download to a temp file then atomically replace.
            val parent = out.parentFile ?: throw IllegalStateException("Invalid output path (no parent): ${out.absolutePath}")
            val tmp = File(parent, out.name + ".part")
            tmp.parentFile?.mkdirs()

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

                // If server provided a length, enforce it. This prevents "partial zip" installs
                // that later fail with missing model files.
                if (contentLen > 0L && readTotal != contentLen) {
                    runCatching { tmp.delete() }
                    throw IllegalStateException(
                        "Model download incomplete: got=${readTotal}B expected=${contentLen}B"
                    )
                }
            }

            // Replace destination
            if (out.exists()) out.delete()
            if (!tmp.renameTo(out)) {
                // Fallback copy if rename fails
                tmp.copyTo(out, overwrite = true)
                runCatching { tmp.delete() }
            }
        }
    }

    private fun unzipInto(
        zipFile: File,
        targetDir: File,
        onProgress: (percent: Int, message: String) -> Unit,
    ) {
        // Use ZipFile (random-access) instead of ZipInputStream.
        // This is more robust and also tends to fail loudly on corrupt/truncated zips.
        val zip = java.util.zip.ZipFile(zipFile)
        zip.use { zf ->
            val entriesEnum = zf.entries()
            val entries = mutableListOf<java.util.zip.ZipEntry>()
            while (entriesEnum.hasMoreElements()) {
                entries += entriesEnum.nextElement()
            }
            val total = entries.size.coerceAtLeast(1)
            val targetRoot = targetDir.canonicalFile

            for ((idx, entry) in entries.withIndex()) {
                val name = entry.name
                val outFile = File(targetDir, name)

                // Basic zip-slip protection
                val outCanon = outFile.canonicalFile
                if (!outCanon.path.startsWith(targetRoot.path)) {
                    throw IllegalStateException("Bad zip entry path: $name")
                }

                if (entry.isDirectory) {
                    outCanon.mkdirs()
                } else {
                    outCanon.parentFile?.mkdirs()
                    zf.getInputStream(entry).use { input ->
                        FileOutputStream(outCanon).use { fos ->
                            val buf = ByteArray(256 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                fos.write(buf, 0, n)
                            }
                            fos.flush()
                        }
                    }
                }

                if (idx % 10 == 0 || idx == total - 1) {
                    val pct = ((idx + 1) * 100 / total).coerceIn(0, 99)
                    onProgress(pct, "Extracting… ${pct}%")
                }
            }
        }
    }
}
