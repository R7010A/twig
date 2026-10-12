package com.twig.app.ui

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.twig.core.RandomSource
import com.twig.core.XFile
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Full-track RMS analysis with shared jobs, independent cache reads and one final result.
 * Foreground and next-track prefetch share a decode when they want the same track.
 * Only complete results are persisted; playback shares downloaded bytes through AudioCache.
 */
object Waveform {

    const val BUCKETS = 240

    // Completeness threshold: when decoding is interrupted by network issues / early
    // EOS, only the first few buckets can be filled; such partial results can be
    // displayed but must never be cached — the key only contains "name:size:mtime",
    // so once cached it stays partial forever and won't expire naturally.
    private const val COMPLETE_RATIO = 0.9

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "twig-waveform").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())
    private val cacheExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "twig-wave-cache").apply { isDaemon = true }
    }
    private val loader = WaveformLoader(BUCKETS, cacheExecutor, executor,
        { action -> main.post { action() } }, ::compute)

    /** The callback runs once, after analysis finishes (or the completed cache is read). */
    fun request(ctx: Context, file: XFile, prefetch: Boolean = false, onReady: (ByteArray) -> Unit) {
        AudioCache.init(ctx)
        val requestedAt = System.nanoTime()
        loader.request(keyOf(file), File(ctx.applicationContext.cacheDir, "waves2"), file, prefetch) { data ->
            Log.d("twig", "waveform ready: ${file.name} prefetch=$prefetch elapsedMs=${(System.nanoTime() - requestedAt) / 1_000_000}")
            onReady(data)
        }
    }

    fun cancel() = loader.cancel()

    private fun keyOf(f: XFile): String =
        MessageDigest.getInstance("MD5").digest("${f.name}:${f.size}:${f.lastModified}".toByteArray())
            .joinToString("") { "%02x".format(it) }

    // ---- Decoding ----

    private fun compute(file: XFile, alive: () -> Boolean): WaveformResult? {
        val startedAt = System.nanoTime()
        val local = com.twig.app.OpenFiles.directlyReadable(file)
        var setupNs = 0L
        var inputNs = 0L
        var extractNs = 0L
        var outputNs = 0L
        var rmsNs = 0L
        var finishNs = 0L
        var samples = 0L
        var buffers = 0L
        var durationUs = 0L
        var codecName = "unknown"
        var status = "unfinished"
        val extractor = MediaExtractor()
        var netSource: RandomSource? = null
        var decoder: MediaCodec? = null
        try {
            if (local) {
                extractor.setDataSource(file.path)
            } else {
                // Network source: uses AudioCache's per-track shared disk cache — while the
                // waveform decode reads the entire track sequentially, the bytes are dropped
                // into the same cache shared with the player, so playback/seek don't have to
                // re-download (close is a no-op; connections are managed by AudioCache). When
                // the total length is unknown, AudioCache internally falls back to a direct
                // BufferedRandomSource read.
                val src = AudioCache.source(file)
                netSource = src
                extractor.setDataSource(NetSource(src))
            }
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; format = f; break }
            }
            if (track < 0 || format == null) return null
            extractor.selectTrack(track)
            durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            if (durationUs <= 0) return null
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            var rms = WaveformRms(BUCKETS)
            val pcm16 = !format.containsKey(MediaFormat.KEY_PCM_ENCODING) ||
                format.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_16BIT
            val useFfmpeg = mime != "audio/raw" && FfmpegWaveform.available(mime)
            val software = if (useFfmpeg) runCatching {
                FfmpegWaveform.compute(extractor, format, durationUs, alive)
            }.onFailure { Log.w("twig", "waveform: ffmpeg failed, retrying platform: ${file.name}: $it") }
                .getOrNull() else null
            if (!alive()) return null
            if (software != null) {
                codecName = "ffmpeg-$mime"
                rms = software.rms
                setupNs = software.setupNs
                inputNs = software.inputNs
                extractNs = software.extractNs
                outputNs = software.outputNs
                rmsNs = software.rmsNs
                samples = software.samples
                buffers = software.buffers
            } else if (mime == "audio/raw" && pcm16) {
                // The extractor may already decode FLAC to PCM. Passing that
                // through a raw MediaCodec adds buffer/IPC work without decoding anything.
                codecName = "extractor-pcm16"
                val maxInput = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))
                    format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
                var buffer = ByteBuffer.allocateDirect(maxOf(maxInput, 1 shl 20))
                setupNs = System.nanoTime() - startedAt
                while (alive()) {
                    val inputStartedAt = System.nanoTime()
                    // API 24-27 use the generous initial buffer; newer extractors can
                    // tell us the exact next packet size before the read.
                    if (android.os.Build.VERSION.SDK_INT >= 28 && extractor.sampleSize > buffer.capacity()) {
                        val size = extractor.sampleSize
                        require(size <= Int.MAX_VALUE) { "PCM sample too large" }
                        buffer = ByteBuffer.allocateDirect(size.toInt())
                    }
                    buffer.clear()
                    val timestampUs = extractor.sampleTime
                    val size = extractor.readSampleData(buffer, 0)
                    inputNs += System.nanoTime() - inputStartedAt
                    if (size < 0) break
                    val rmsStartedAt = System.nanoTime()
                    rms.add(buffer, 0, size, timestampUs, durationUs)
                    rmsNs += System.nanoTime() - rmsStartedAt
                    samples += (size / 2).toLong()
                    buffers++
                    val advanceStartedAt = System.nanoTime()
                    extractor.advance()
                    inputNs += System.nanoTime() - advanceStartedAt
                }
            } else {
                // A failed software attempt may have consumed packets; start over.
                if (useFfmpeg) extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val codec = MediaCodec.createDecoderByType(mime)
                decoder = codec
                codecName = codec.name
                codec.configure(format, null, null, 0)
                codec.start()

                setupNs = System.nanoTime() - startedAt
                val info = MediaCodec.BufferInfo()
                var inEos = false
                var outEos = false
                while (!outEos) {
                    if (!alive()) return null
                    // Batch available buffers with nonblocking polls. Wait only when no
                    // input was queued, so local decoding runs at decoder throughput.
                    val inputStartedAt = System.nanoTime()
                    var worked = false
                    var inputs = 0
                    while (!inEos && inputs < 8 && alive()) {
                        val inIdx = codec.dequeueInputBuffer(0)
                        if (inIdx < 0) break
                        worked = true
                        inputs++
                        val buf = codec.getInputBuffer(inIdx)!!
                        val extractStartedAt = System.nanoTime()
                        val n = extractor.readSampleData(buf, 0)
                        extractNs += System.nanoTime() - extractStartedAt
                        if (n < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inEos = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                    inputNs += System.nanoTime() - inputStartedAt
                    val outputStartedAt = System.nanoTime()
                    val rmsBefore = rmsNs
                    var outputWaitUs = if (worked) 0L else 10_000L
                    var outputs = 0
                    while (!outEos && outputs < 32 && alive()) {
                        val outIdx = codec.dequeueOutputBuffer(info, outputWaitUs)
                        outputWaitUs = 0L
                        if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) break
                        if (outIdx < 0) continue
                        outputs++
                        if (info.size > 0) {
                            val out = codec.getOutputBuffer(outIdx)!!
                            val rmsStartedAt = System.nanoTime()
                            rms.add(out, info.offset, info.size, info.presentationTimeUs, durationUs)
                            rmsNs += System.nanoTime() - rmsStartedAt
                            samples += (info.size / 2).toLong()
                            buffers++
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outEos = true
                    }
                    outputNs += System.nanoTime() - outputStartedAt - (rmsNs - rmsBefore)
                }
            }
            if (!alive()) return null
            // No samples decoded at all = failure, not a "flat waveform": the old version
            // let normalize emit a flat array of 40s here and treated it as a successful
            // result, writing it to cache — so failures were permanently baked in.
            val filled = rms.filled
            if (filled == 0) { Log.w("twig", "waveform: no samples decoded: ${file.name}"); return null }
            val finishStartedAt = System.nanoTime()
            val result = WaveformResult(rms.snapshot(), filled >= BUCKETS * COMPLETE_RATIO)
            finishNs = System.nanoTime() - finishStartedAt
            status = if (result.complete) "complete" else "incomplete"
            return result
        } catch (e: Exception) {
            Log.w("twig", "waveform: failed ${file.name}: $e")
            return null
        } finally {
            val releaseStartedAt = System.nanoTime()
            decoder?.let { codec ->
                runCatching { codec.stop() }
                runCatching { codec.release() }
            }
            runCatching { extractor.release() }
            netSource?.let { s -> runCatching { s.close() } }
            val releaseNs = System.nanoTime() - releaseStartedAt
            // These are wall-time phases: output includes decoder waits/IPC, not codec CPU time.
            Log.d("twig", "waveform profile: ${file.name} local=$local status=$status codec=$codecName " +
                "durationMs=${durationUs / 1000} samples=$samples buffers=$buffers " +
                "totalMs=${(System.nanoTime() - startedAt) / 1_000_000} setupMs=${setupNs / 1_000_000} " +
                "inputMs=${inputNs / 1_000_000} extractMs=${extractNs / 1_000_000} outputMs=${outputNs / 1_000_000} " +
                "rmsMs=${rmsNs / 1_000_000} normalizeMs=${finishNs / 1_000_000} releaseMs=${releaseNs / 1_000_000}")
        }
    }

    /** RandomSource → android.media.MediaDataSource (for full decoding of network sources). */
    private class NetSource(private val src: RandomSource) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (size == 0) return 0
            val n = src.readAt(position, buffer, offset, size)
            return if (n <= 0) -1 else n
        }
        override fun getSize(): Long = src.length()
        override fun close() { runCatching { src.close() } }
    }
}
