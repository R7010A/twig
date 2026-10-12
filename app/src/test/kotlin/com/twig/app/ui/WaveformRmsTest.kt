package com.twig.app.ui

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow
import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Test

class WaveformRmsTest {
    private fun pcm(values: ShortArray, prefix: Int = 0): ByteBuffer =
        ByteBuffer.allocateDirect(prefix + values.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            position(prefix)
            asShortBuffer().put(values)
        }

    @Test fun `bulk integer RMS matches original per sample formula including extremes and offset`() {
        val buckets = 4
        val accumulator = WaveformRms(buckets)
        val arrays = listOf(
            shortArrayOf(Short.MIN_VALUE, Short.MAX_VALUE, 0),
            ShortArray(12000) { (it * 31).toShort() },
            shortArrayOf(100, -100, 200),
            shortArrayOf(0, 0, 0),
        )
        val reference = DoubleArray(buckets)
        arrays.forEachIndexed { i, samples ->
            accumulator.add(pcm(samples, 6), 6, samples.size * 2, i * 1000L, 4000L)
            reference[i] = sqrt(samples.sumOf { it.toDouble() * it } / samples.size)
        }
        val max = reference.max()
        val expected = ByteArray(buckets) { (8 + (reference[it] / max).pow(1.6) * 247).toInt().toByte() }
        assertArrayEquals(expected, accumulator.snapshot())
        assertEquals(4, accumulator.filled)
    }

    @Test fun `partial silence distinguishes analysed and unanalysed buckets without mutating snapshots`() {
        val accumulator = WaveformRms(4)
        val quiet = shortArrayOf(0, 0)
        accumulator.add(pcm(quiet), 0, 4, 0, 4000)
        val first = accumulator.snapshot(partial = true)
        assertArrayEquals(byteArrayOf(40, 0, 0, 0), first)
        val loud = shortArrayOf(1000, -1000)
        accumulator.add(pcm(loud), 0, 4, 1000, 4000)
        assertArrayEquals(byteArrayOf(8, -1, 0, 0), accumulator.snapshot(partial = true))
        assertArrayEquals(byteArrayOf(40, 0, 0, 0), first)
        assertArrayEquals(byteArrayOf(8, -1, 8, 8), accumulator.snapshot())
    }

    @Test fun `long buffers do not overflow square sum and boundary timestamps clamp`() {
        val accumulator = WaveformRms(4)
        val samples = ShortArray(20000) { Short.MIN_VALUE }
        accumulator.add(pcm(samples), 0, samples.size * 2, -100, 4000)
        accumulator.add(pcm(samples), 0, samples.size * 2, 5000, 4000)
        assertArrayEquals(byteArrayOf(-1, 0, 0, -1), accumulator.snapshot(partial = true))
        assertEquals(2, accumulator.filled)
    }
}
