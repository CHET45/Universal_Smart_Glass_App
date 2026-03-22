package com.fersaiyan.cyanbridge.ai.transcription

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

/**
 * Best-effort MP4/M4A chunker using MediaExtractor + MediaMuxer.
 *
 * If anything fails, callers should fall back to a single chunk upload.
 */
class Mp4AudioChunker(
    private val context: Context,
) : AudioChunker {

    override suspend fun chunk(inputAudio: File, sessionId: Long, chunkDurationSec: Long): List<File> {
        return withContext(Dispatchers.IO) {
            if (chunkDurationSec <= 0) return@withContext listOf(inputAudio)
            if (!inputAudio.exists()) throw IllegalArgumentException("Audio file not found: ${inputAudio.absolutePath}")

            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(inputAudio.absolutePath)

                val trackIndex = (0 until extractor.trackCount)
                    .firstOrNull { idx ->
                        val fmt = extractor.getTrackFormat(idx)
                        val mime = fmt.getString(MediaFormat.KEY_MIME)
                        mime?.startsWith("audio/") == true
                    } ?: return@withContext listOf(inputAudio)

                extractor.selectTrack(trackIndex)
                val format = extractor.getTrackFormat(trackIndex)

                val durationUs = runCatching { format.getLong(MediaFormat.KEY_DURATION) }.getOrNull()
                val durationMs = durationUs?.div(1000L)

                val ranges = ChunkPlanner.plan(
                    totalDurationMs = durationMs,
                    chunkDurationMs = chunkDurationSec * 1000L
                )
                if (ranges.isEmpty() || ranges.size == 1) return@withContext listOf(inputAudio)

                val outDir = File(context.cacheDir, "transcription_chunks/$sessionId").apply {
                    if (exists()) deleteRecursively()
                    mkdirs()
                }

                val maxInputSize = runCatching { format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) }.getOrNull()
                val bufferSize = (maxInputSize ?: (512 * 1024)).coerceIn(64 * 1024, 2 * 1024 * 1024)
                val buffer = ByteBuffer.allocateDirect(bufferSize)
                val info = MediaCodec.BufferInfo()

                val outFiles = mutableListOf<File>()
                for (r in ranges) {
                    val outFile = File(outDir, "chunk_${(r.index + 1).toString().padStart(2, '0')}_of_${r.total}.m4a")

                    val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                    try {
                        val outTrack = muxer.addTrack(format)
                        muxer.start()

                        val startUs = r.startMs * 1000L
                        val chunkDurUs = (r.endMsExclusive - r.startMs) * 1000L

                        extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                        val baseUs = extractor.sampleTime.takeIf { it >= 0 } ?: startUs

                        while (true) {
                            val timeUs = extractor.sampleTime
                            if (timeUs < 0) break
                            if (timeUs - baseUs >= chunkDurUs) break

                            info.offset = 0
                            info.size = extractor.readSampleData(buffer, 0)
                            if (info.size < 0) break

                            info.presentationTimeUs = (timeUs - baseUs).coerceAtLeast(0L)
                            info.flags = extractor.sampleFlags

                            muxer.writeSampleData(outTrack, buffer, info)
                            extractor.advance()
                        }

                        outFiles.add(outFile)
                    } finally {
                        runCatching { muxer.stop() }
                        runCatching { muxer.release() }
                    }
                }

                // Basic sanity: if chunking produced nothing, fall back to original.
                if (outFiles.isEmpty()) listOf(inputAudio) else outFiles
            } catch (t: Throwable) {
                Log.w(TAG, "Chunking failed; falling back to single file", t)
                listOf(inputAudio)
            } finally {
                runCatching { extractor.release() }
            }
        }
    }

    companion object {
        private const val TAG = "Mp4AudioChunker"
    }
}
