package com.fersaiyan.cyanbridge.ai.transcription.moonshine

import ai.moonshine.voice.TranscriptEvent
import ai.moonshine.voice.TranscriptEventListener
import ai.moonshine.voice.Transcriber
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import com.fersaiyan.cyanbridge.ai.transcription.SilenceCompactor
import com.fersaiyan.cyanbridge.ai.transcription.TranscriptionProvider
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference

/**
 * Offline, on-device transcription using Moonshine Voice.
 *
 * This decodes recorded M4A/AAC into PCM, downmixes to mono, resamples to 16kHz,
 * converts to float[-1,1], and feeds audio into a Moonshine streaming transcriber.
 */
class MoonshineTranscriptionProvider(
    private val context: Context,
    private val modelDir: File,
    private val modelArch: Int,
) : TranscriptionProvider {

    override val name: String = "moonshine"

    override suspend fun transcribe(audioFile: File, mimeType: String, language: String?): String {
        if (!audioFile.exists()) return ""
        if (!modelDir.exists()) throw IllegalStateException("Moonshine model directory not found at $modelDir")

        Log.i(TAG, "Moonshine transcribe: ${audioFile.name} size=${audioFile.length()} model=${modelDir.name} arch=$modelArch")

        val transcriptSb = StringBuilder()
        val errorRef = AtomicReference<Throwable?>(null)

        val transcriber = Transcriber()
        transcriber.loadFromFiles(modelDir.absolutePath, modelArch)

        transcriber.addListener { event: TranscriptEvent ->
            event.accept(object : TranscriptEventListener() {
                override fun onLineCompleted(event: TranscriptEvent.LineCompleted) {
                    val t = event.line?.text?.trim().orEmpty()
                    if (t.isBlank()) return
                    synchronized(transcriptSb) {
                        if (transcriptSb.isNotEmpty()) transcriptSb.append('\n')
                        transcriptSb.append(t)
                    }
                }

                override fun onError(event: TranscriptEvent.Error) {
                    errorRef.compareAndSet(null, event.cause)
                }
            })
        }

        val feeder = MoonshineFeeder(transcriber)

        try {
            feeder.start()

            // Streaming decode + feed.
            decodeToPcm16(audioFile) { chunk ->
                val mono = downmixToMono(chunk.samples, chunk.channels)
                val compacted = SilenceCompactor.compactMonoPcm(
                    samples = mono,
                    sampleRateHz = chunk.sampleRate,
                    preserveProbeOnNoSpeech = false,
                )
                if (compacted.isEmpty()) return@decodeToPcm16
                val resampler = feeder.getOrCreateResampler(chunk.sampleRate)
                resampler.process(compacted) { outShorts ->
                    val floats = shortsToFloats(outShorts)
                    feeder.feedFloats(floats)
                }
            }

            feeder.stop()

            errorRef.get()?.let { throw it }

            return transcriptSb.toString().trim()
        } finally {
            runCatching { feeder.stop() }
        }
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

    private fun shortsToFloats(samples: ShortArray): FloatArray {
        val out = FloatArray(samples.size)
        for (i in samples.indices) {
            out[i] = samples[i].toFloat() / 32768.0f
        }
        return out
    }

    private class MoonshineFeeder(private val transcriber: Transcriber) {
        private var resampler: StreamingLinearResampler? = null

        fun start() {
            transcriber.start()
        }

        fun stop() {
            runCatching { transcriber.stop() }
        }

        fun feedFloats(samples: FloatArray) {
            if (samples.isEmpty()) return
            // Transcriber expects 16kHz PCM float
            transcriber.addAudio(samples, TARGET_SAMPLE_RATE)
        }

        fun getOrCreateResampler(inputSampleRate: Int): StreamingLinearResampler {
            val existing = resampler
            if (existing != null && existing.fromRate == inputSampleRate) return existing
            val r = StreamingLinearResampler(fromRate = inputSampleRate, toRate = TARGET_SAMPLE_RATE)
            resampler = r
            return r
        }
    }

    /** Streaming linear resampler from any rate -> 16kHz (mono). */
    private class StreamingLinearResampler(
        val fromRate: Int,
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

    companion object {
        private const val TAG = "MoonshineProvider"
        private const val TARGET_SAMPLE_RATE = 16_000
    }
}
