package com.twig.app.ui

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.media3.common.C
import androidx.media3.decoder.Decoder
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.SimpleDecoderOutputBuffer
import androidx.media3.decoder.ffmpeg.FfmpegDecoderException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [FfmpegWaveformTest.Packets::class])
class FfmpegWaveformTest {
    @Implements(MediaExtractor::class)
    class Packets {
        companion object { var count = 240 }
        private var index = 0
        @Implementation fun getSampleTime(): Long = index * 1000L + 500
        @Implementation fun getSampleSize(): Long = if (index < count) 2 else -1
        @Implementation fun readSampleData(buffer: ByteBuffer, offset: Int): Int {
            if (index >= count) return -1
            buffer.order(ByteOrder.LITTLE_ENDIAN).putShort(offset, 1000)
            return 2
        }
        @Implementation fun advance(): Boolean { index++; return index < count }
    }
    private class FakeDecoder : Decoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, FfmpegDecoderException> {
        private val outputs = ArrayDeque<SimpleDecoderOutputBuffer>()
        var released = false
        var outputReleases = 0
        var queued = 0
        var fail = false
        override fun getName() = "fake"
        override fun setOutputStartTimeUs(timeUs: Long) {}
        override fun dequeueInputBuffer() = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT)
        override fun queueInputBuffer(input: DecoderInputBuffer) {
            if (fail) throw FfmpegDecoderException::class.java.getDeclaredConstructor(String::class.java)
                .apply { isAccessible = true }.newInstance("decode failed")
            queued++
            val output = SimpleDecoderOutputBuffer { outputReleases++ }
            // Deliberately put bytes on EOS too: the pump must ignore them.
            output.init(input.timeUs, 2).order(ByteOrder.LITTLE_ENDIAN).putShort(0, 1000)
            if (input.isEndOfStream) output.addFlag(C.BUFFER_FLAG_END_OF_STREAM)
            outputs.add(output)
        }
        override fun dequeueOutputBuffer(): SimpleDecoderOutputBuffer? = outputs.poll()
        override fun flush() { outputs.clear() }
        override fun release() {
            released = true
            while (outputs.isNotEmpty()) outputs.removeFirst().release()
        }
    }
    private lateinit var decoder: FakeDecoder
    private lateinit var format: MediaFormat
    @Before fun setup() {
        Packets.count = 240
        decoder = FakeDecoder()
        format = MediaFormat.createAudioFormat("audio/mpeg", 44100, 2)
    }
    private fun analyse(alive: () -> Boolean = { true }) = FfmpegWaveform.compute(
        MediaExtractor(), format, 240000, alive) { _, _ -> decoder }

    @Test fun `AAC and ALAC configuration is copied without changing fallback buffers`() {
        for (mime in listOf("audio/mp4a-latm", "audio/alac")) {
            val original = MediaFormat.createAudioFormat(mime, 48000, 2)
            val csd = ByteBuffer.wrap(byteArrayOf(9, 1, 2, 3, 8)).apply {
                position(1)
                limit(4)
            }
            original.setByteBuffer("csd-0", csd)
            original.setByteBuffer("csd-1", ByteBuffer.wrap(byteArrayOf(4, 5)))
            val converted = FfmpegWaveform.decoderFormat(original)
            assertEquals(mime, converted.sampleMimeType)
            assertEquals(48000, converted.sampleRate)
            assertEquals(2, converted.channelCount)
            assertEquals(2, converted.initializationData.size)
            assertArrayEquals(byteArrayOf(1, 2, 3), converted.initializationData[0])
            assertArrayEquals(byteArrayOf(4, 5), converted.initializationData[1])
            assertEquals(1, csd.position())
            assertEquals(4, csd.limit())
            csd.put(1, 7)
            assertArrayEquals(byteArrayOf(1, 2, 3), converted.initializationData[0])
        }
    }

    @Test fun `decoder receives the actual codec instead of a hardcoded MP3 format`() {
        format = MediaFormat.createAudioFormat("audio/alac", 44100, 2).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf(1, 2, 3)))
        }
        var received = false
        val result = FfmpegWaveform.compute(MediaExtractor(), format, 240000, { true }) { converted, _ ->
            assertEquals("audio/alac", converted.sampleMimeType)
            assertArrayEquals(byteArrayOf(1, 2, 3), converted.initializationData.single())
            received = true
            decoder
        }
        assertTrue(received)
        assertNotNull(result)
    }

    @Test fun `all packets contribute and EOS is drained without counting its bytes`() {
        val result = analyse()!!
        assertEquals(240L, result.samples)
        assertEquals(240L, result.buffers)
        assertEquals(240, result.rms.filled)
        assertArrayEquals(ByteArray(240) { -1 }, result.rms.snapshot())
        assertEquals(241, decoder.outputReleases)
        assertTrue(decoder.released)
    }

    @Test fun `cancellation releases decoder and returns no partial result`() {
        assertNull(analyse { decoder.queued < 16 })
        assertEquals(16, decoder.queued)
        assertTrue(decoder.released)
        assertEquals(16, decoder.outputReleases)
    }

    @Test fun `decode failure is propagated for platform fallback and releases resources`() {
        decoder.fail = true
        try {
            analyse()
            fail("Expected decoder failure")
        } catch (e: FfmpegDecoderException) { assertEquals("decode failed", e.message) }
        assertTrue(decoder.released)
    }

    @Test fun `early EOS is rejected for fallback instead of caching half a waveform`() {
        Packets.count = 120
        try {
            analyse()
            fail("Expected incomplete waveform")
        } catch (e: IllegalStateException) { assertEquals("Incomplete audio waveform", e.message) }
        assertTrue(decoder.released)
        assertEquals(121, decoder.outputReleases)
    }
}
