package com.fersaiyan.cyanbridge.ai.transcription.vosk

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import com.fersaiyan.cyanbridge.ai.transcription.TranscriptionProvider
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Offline, on-device transcription using Vosk.
 *
 * This decodes the recorded M4A/AAC into PCM, downmixes to mono, resamples to 16kHz,
 * and feeds the PCM stream into Vosk.
 */
class VoskTranscriptionProvider(
    private val context: Context,
    private val modelDir: File,
) : TranscriptionProvider {

    override val name: String = "vosk"

    override suspend fun transcribe(audioFile: File, mimeType: String, language: String?): String {
        if (!audioFile.exists()) return ""
        if (!modelDir.exists()) throw IllegalStateException("Vosk model directory not found at $modelDir")

        // Debug: list files in model dir to verify structure
        val files = modelDir.listFiles()?.map { it.name } ?: emptyList()
        Log.i(TAG, "Vosk model dir contents: $files")

        // Note: not all Vosk models ship model.conf (e.g., some older "small" models).
        // The Vosk Model(...) constructor is the source of truth; we'll rely on that and
        // surface a richer validation report if it fails.

        Log.i(TAG, "Vosk transcribe: ${audioFile.name} size=${audioFile.length()} model=${modelDir.name}")

        val model = try {
            Model(modelDir.absolutePath)
        } catch (e: Exception) {
            val report = VoskModelManager.validationReport(modelDir)
            Log.e(TAG, "Failed to create Vosk model: ${e.message} ($report)", e)
            throw IllegalStateException("Failed to create Vosk model: ${e.message} | $report")
        }
        
        val recognizer = Recognizer(model, TARGET_SAMPLE_RATE.toFloat())

        val resampler: StreamingLinearResampler? = null // initialized once we know input sample rate

        // Streaming decode + feed.
        val feeder = VoskFeeder(recognizer)
        decodeToPcm16(audioFile) { chunk ->
            // chunk is PCM 16-bit interleaved in LITTLE_ENDIAN.
            val mono = downmixToMono(chunk.samples, chunk.channels)

            val r = feeder.getOrCreateResampler(chunk.sampleRate)
            r.process(mono) { outShorts ->
                feeder.feedShorts(outShorts)
            }
        }

        feeder.finish()

        val json = runCatching { recognizer.finalResult }.getOrNull().orEmpty()
        val text = runCatching {
            JSONObject(json).optString("text", "")
        }.getOrDefault("")

        runCatching { recognizer.close() }
        runCatching { model.close() }

        return text.trim()
    }

    private data class PcmChunk(
        val samples: ShortArray,
        val sampleRate: Int,
        val channels: Int,
    )

    private fun decodeToPcm16(
        file: File,
        onChunk: (PcmChunk) -> Unit,
    ) {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)

        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                trackIndex = i
                format = f
                break
            }
        }
        if (trackIndex < 0 || format == null) {
            extractor.release()
            throw IllegalStateException("No audio track found")
        }

        extractor.selectTrack(trackIndex)

        val mime = format.getString(MediaFormat.KEY_MIME) ?: "audio/mp4a-latm"
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        var outSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var outChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var outPcmEncoding = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            format.getInteger(MediaFormat.KEY_PCM_ENCODING)
        } else {
            // default 16-bit
            2
        }

        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false

        while (!outputDone) {
            if (!inputDone) {
                val inIndex = codec.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val inputBuf = codec.getInputBuffer(inIndex) ?: continue
                    val sampleSize = extractor.readSampleData(inputBuf, 0)
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(
                            inIndex,
                            0,
                            0,
                            0L,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        inputDone = true
                    } else {
                        val pts = extractor.sampleTime
                        codec.queueInputBuffer(inIndex, 0, sampleSize, pts, 0)
                        extractor.advance()
                    }
                }
            }

            val outIndex = codec.dequeueOutputBuffer(info, 10_000)
            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    // no-op
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val of = codec.outputFormat
                    if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) outSampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) outChannels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && of.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        outPcmEncoding = of.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    }
                    Log.i(TAG, "Decoder output format: sr=$outSampleRate ch=$outChannels pcmEnc=$outPcmEncoding")
                }
                outIndex >= 0 -> {
                    val outBuf = codec.getOutputBuffer(outIndex)
                    if (outBuf != null && info.size > 0) {
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)

                        // Most devices output PCM 16-bit. If not, we still try best-effort.
                        val shorts = when (outPcmEncoding) {
                            // ENCODING_PCM_16BIT == 2
                            2 -> bufferToShortsLE(outBuf)
                            // ENCODING_PCM_FLOAT == 4
                            4 -> floatBufferToShorts(outBuf)
                            else -> bufferToShortsLE(outBuf)
                        }

                        onChunk(PcmChunk(shorts, sampleRate = outSampleRate, channels = outChannels))
                    }

                    codec.releaseOutputBuffer(outIndex, false)

                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true
                    }
                }
            }
        }

        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { extractor.release() }
    }

    private fun bufferToShortsLE(buf: ByteBuffer): ShortArray {
        val b = ByteArray(buf.remaining())
        buf.get(b)
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        val out = ShortArray(b.size / 2)
        bb.asShortBuffer().get(out)
        return out
    }

    private fun floatBufferToShorts(buf: ByteBuffer): ShortArray {
        val b = ByteArray(buf.remaining())
        buf.get(b)
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        val floats = FloatArray(b.size / 4)
        bb.asFloatBuffer().get(floats)
        val out = ShortArray(floats.size)
        for (i in floats.indices) {
            val v = (floats[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt()
            out[i] = v.toShort()
        }
        return out
    }

    private fun downmixToMono(interleaved: ShortArray, channels: Int): ShortArray {
        if (channels <= 1) return interleaved
        val frames = interleaved.size / channels
        val out = ShortArray(frames)
        var idx = 0
        for (f in 0 until frames) {
            var sum = 0
            for (c in 0 until channels) {
                sum += interleaved[idx++].toInt()
            }
            out[f] = (sum / channels).toShort()
        }
        return out
    }

    private class VoskFeeder(private val recognizer: Recognizer) {
        private var resampler: StreamingLinearResampler? = null
        private val buf = ByteArrayOutput(16 * 1024)

        fun getOrCreateResampler(inputSampleRate: Int): StreamingLinearResampler {
            val existing = resampler
            if (existing != null) return existing
            val r = StreamingLinearResampler(fromRate = inputSampleRate, toRate = TARGET_SAMPLE_RATE)
            resampler = r
            return r
        }

        fun feedShorts(samples: ShortArray) {
            if (samples.isEmpty()) return
            // append as little-endian PCM16
            for (s in samples) {
                buf.writeShortLE(s)
                if (buf.size() >= 16 * 1024) {
                    flush()
                }
            }
        }

        private fun flush() {
            val bytes = buf.toByteArrayAndReset()
            if (bytes.isNotEmpty()) {
                recognizer.acceptWaveForm(bytes, bytes.size)
            }
        }

        fun finish() {
            flush()
        }
    }

    /** Streaming linear resampler from any rate -> 16kHz (mono). */
    private class StreamingLinearResampler(
        private val fromRate: Int,
        private val toRate: Int,
    ) {
        private val step = fromRate.toDouble() / toRate.toDouble()
        private var inputIndexBase: Long = 0L
        private var nextOutAt: Double = 0.0

        fun process(input: ShortArray, onOutput: (ShortArray) -> Unit) {
            if (input.size < 2) {
                inputIndexBase += input.size
                return
            }

            val start = inputIndexBase.toDouble()
            val endExclusive = start + input.size.toDouble()

            val out = ShortArray(((input.size / step).toInt() + 4).coerceAtLeast(0))
            var outCount = 0

            // Only emit outputs for which we have i0 and i0+1 in this chunk.
            val lastValidPos = endExclusive - 1.0
            while (nextOutAt < lastValidPos) {
                val posInChunk = nextOutAt - start
                val i0 = posInChunk.toInt()
                val frac = posInChunk - i0.toDouble()
                val s0 = input[i0].toDouble()
                val s1 = input[i0 + 1].toDouble()
                val v = s0 + (s1 - s0) * frac
                out[outCount++] = v.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                nextOutAt += step
            }

            inputIndexBase += input.size
            if (outCount > 0) {
                onOutput(out.copyOf(outCount))
            }
        }
    }

    private class ByteArrayOutput(initialCap: Int) {
        private var arr = ByteArray(initialCap)
        private var len = 0

        fun writeShortLE(v: Short) {
            ensure(2)
            val i = len
            arr[i] = (v.toInt() and 0xFF).toByte()
            arr[i + 1] = ((v.toInt() shr 8) and 0xFF).toByte()
            len += 2
        }

        fun size(): Int = len

        fun toByteArrayAndReset(): ByteArray {
            val out = arr.copyOf(len)
            len = 0
            return out
        }

        private fun ensure(extra: Int) {
            val need = len + extra
            if (need <= arr.size) return
            var n = arr.size
            while (n < need) n *= 2
            arr = arr.copyOf(n)
        }
    }

    companion object {
        private const val TAG = "VoskProvider"
        private const val TARGET_SAMPLE_RATE = 16_000
    }
}
