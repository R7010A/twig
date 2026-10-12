package com.twig.app.ui

import android.animation.ValueAnimator
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WaveformSeekBarTest {
    private fun view() = WaveformSeekBar(ApplicationProvider.getApplicationContext<Application>()).apply {
        layout(0, 0, 300, 96)
    }
    private fun animator(view: WaveformSeekBar) = ReflectionHelpers.getField<ValueAnimator>(view, "growth")
    private fun bounds(view: WaveformSeekBar): Pair<Int, Int> {
        val bitmap = Bitmap.createBitmap(300, 96, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val rows = (0 until 96).filter { y -> (0 until 300).any { x -> bitmap.getPixel(x, y) ushr 24 > 0 } }
        bitmap.recycle()
        return rows.first() to rows.last()
    }

    @Test fun `waveform unfolds symmetrically from horizontal centre`() {
        val view = view()
        val baseline = bounds(view)
        view.setWaveform(byteArrayOf(-1, -1, -1, -1))
        animator(view).currentPlayTime = 0
        assertEquals(baseline, bounds(view))
        animator(view).currentPlayTime = 70
        val halfway = bounds(view)
        assertTrue(halfway.first < baseline.first)
        assertTrue(halfway.second > baseline.second)
        assertTrue(abs(halfway.first + halfway.second - 95) <= 1)
        assertTrue(halfway.first > 0)
        animator(view).end()
        assertEquals(0 to 95, bounds(view))
    }

    @Test fun `progressive updates continue from current height and clearing returns to placeholder`() {
        val view = view()
        val baseline = bounds(view)
        view.setWaveform(byteArrayOf(-1, 0, 0, 0))
        animator(view).currentPlayTime = 60
        val beforeUpdate = bounds(view)
        view.setWaveform(byteArrayOf(-1, -1, 0, 0))
        animator(view).currentPlayTime = 0
        assertEquals(beforeUpdate, bounds(view))
        view.setWaveform(null)
        assertEquals(baseline, bounds(view))
        view.setWaveform(byteArrayOf(-1, -1, -1, -1))
        animator(view).currentPlayTime = 0
        assertEquals(baseline, bounds(view))
        animator(view).end()
    }

    @Test fun `cached waveform arriving before layout still animates after layout`() {
        val view = WaveformSeekBar(ApplicationProvider.getApplicationContext<Application>())
        view.setWaveform(byteArrayOf(-1, -1, -1, -1))
        view.layout(0, 0, 300, 96)
        animator(view).end()
        assertEquals(0 to 95, bounds(view))
    }
}
