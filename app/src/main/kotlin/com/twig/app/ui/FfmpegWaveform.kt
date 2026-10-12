package com.twig.app.ui

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.Decoder
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.SimpleDecoderOutputBuffer
import androidx.media3.decoder.ffmpeg.FfmpegDecoderException
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.decoder.ffmpeg.WaveformDecoder
import java.util.concurrent.locks.LockSupport

/** Reuses the bundled software decoder for local MP3 analysis. Encoded packets
 * still keep their extractor timestamps; every decoded PCM sample contributes. */
@androidx.annotation.OptIn(UnstableApi::class)
internal object FfmpegWaveform {
    class Result(
        val rms: WaveformRms,
        val setupNs: Long,
        val inputNs: Long,
        val extractNs: Long,
        val outputNs: Long,
        val rmsNs: Long,
        val samples: Long,
        val buffers: Long,
    )

    fun available(): Boolean = runCatching {
        FfmpegLibrary.isAvailable() && FfmpegLibrary.supportsFormat("audio/mpeg")
    }.getOrDefault(false)

    /** Throws on failure so the caller can rewind and retry using MediaCodec.
     * Cancellation returns null and must never trigger a retry. */
    fun compute(
        extractor: MediaExtractor, format: MediaFormat, durationUs: Long, alive: () -> Boolean,
        createDecoder: (Int, Int, Int) -> Decoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, FfmpegDecoderException> =
            WaveformDecoder::createMp3,
    ): Result? {
        val startedAt = System.nanoTime()
        val initialSize = maxOf(4096, if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))
            format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0)
        val decoder = createDecoder(
            format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT), initialSize)
        try {
            val setupNs = System.nanoTime() - startedAt
            val rms = WaveformRms(Waveform.BUCKETS)
            var inputNs = 0L
            var extractNs = 0L
            var outputNs = 0L
            var rmsNs = 0L
            var samples = 0L
            var buffers = 0L
            var inputEos = false
            var outputEos = false
            while (!outputEos) {
                if (!alive()) return null
                var worked = false
                val inputStartedAt = System.nanoTime()
                var queued = 0
                while (!inputEos && queued < 16 && alive()) {
                    val input = decoder.dequeueInputBuffer() ?: break
                    worked = true
                    input.clear()
                    val size = if (android.os.Build.VERSION.SDK_INT >= 28)
                        maxOf(initialSize.toLong(), extractor.sampleSize) else initialSize.toLong()
                    require(size <= Int.MAX_VALUE) { "MP3 packet too large" }
                    input.ensureSpaceForWrite(size.toInt())
                    val buffer = input.data!!
                    val extractStartedAt = System.nanoTime()
                    val timestampUs = extractor.sampleTime
                    val count = extractor.readSampleData(buffer, 0)
                    extractNs += System.nanoTime() - extractStartedAt
                    if (count < 0) {
                        input.addFlag(C.BUFFER_FLAG_END_OF_STREAM)
                        inputEos = true
                    } else {
                        input.timeUs = timestampUs
                        buffer.position(count)
                        input.flip()
                        extractor.advance()
                    }
                    decoder.queueInputBuffer(input)
                    queued++
                }
                inputNs += System.nanoTime() - inputStartedAt
                val outputStartedAt = System.nanoTime()
                val previousRmsNs = rmsNs
                var drained = 0
                while (!outputEos && drained < 32 && alive()) {
                    val output = decoder.dequeueOutputBuffer() ?: break
                    worked = true
                    try {
                        outputEos = output.isEndOfStream
                        if (!outputEos) {
                            val buffer = output.data
                            if (buffer != null && buffer.hasRemaining()) {
                                val bytes = buffer.remaining()
                                val rmsStartedAt = System.nanoTime()
                                rms.add(buffer, buffer.position(), bytes, output.timeUs, durationUs)
                                rmsNs += System.nanoTime() - rmsStartedAt
                                samples += (bytes / 2).toLong()
                                buffers++
                            }
                        }
                    } finally { output.release() }
                    drained++
                }
                outputNs += System.nanoTime() - outputStartedAt - (rmsNs - previousRmsNs)
                // The extension decodes on its own thread. Avoid a hot spin while
                // waiting, without adding MediaCodec's per-packet timeout/IPC.
                if (!worked) {
                    val waitStartedAt = System.nanoTime()
                    LockSupport.parkNanos(100_000)
                    outputNs += System.nanoTime() - waitStartedAt
                }
            }
            if (!alive()) return null
            check(rms.filled >= Waveform.BUCKETS * 0.9) { "Incomplete MP3 waveform" }
            return Result(rms, setupNs, inputNs, extractNs, outputNs, rmsNs, samples, buffers)
        } finally { decoder.release() }
    }
}
