package com.twig.app.ui

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow
import kotlin.math.sqrt

/** Bulk-copy PCM16 once per output buffer, then sum integer squares in a reusable
 * array. This avoids a ByteBuffer access and floating-point conversion per sample. */
internal class WaveformRms(private val buckets: Int) {
    private val sumsq = DoubleArray(buckets)
    private val counts = LongArray(buckets)
    private var samples = ShortArray(0)
    val filled: Int get() = counts.count { it > 0 }

    fun add(buffer: ByteBuffer, offset: Int, size: Int, timestampUs: Long, durationUs: Long) {
        val count = size / 2
        if (count == 0) return
        if (samples.size < count) samples = ShortArray(count)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(offset)
        buffer.limit(offset + size)
        buffer.asShortBuffer().get(samples, 0, count)
        var squares = 0L
        for (i in 0 until count) {
            val value = samples[i].toInt()
            squares += value.toLong() * value
        }
        val bucket = (timestampUs.toDouble() / durationUs * buckets).toInt().coerceIn(0, buckets - 1)
        sumsq[bucket] += squares.toDouble()
        counts[bucket] += count.toLong()
    }

    /** Only progressive snapshots use zero as the unanalysed-bucket sentinel.
     * Final normalization stays identical to the existing waves2 disk cache. */
    fun snapshot(partial: Boolean = false): ByteArray {
        val rms = DoubleArray(buckets) { if (counts[it] > 0) sqrt(sumsq[it] / counts[it]) else 0.0 }
        val max = rms.max().takeIf { it > 0 }
        return ByteArray(buckets) {
            when {
                partial && counts[it] == 0L -> 0
                max == null -> 40
                else -> (8 + (rms[it] / max).pow(1.6) * 247).toInt().coerceIn(0, 255).toByte()
            }
        }
    }
}
