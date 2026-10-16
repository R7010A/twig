package com.twig.app.ui

import android.app.Application
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LyricsSwipeTest {
    private class Screen {
        var taps = 0
        var seeks = 0
        val tracks = mutableListOf<Boolean>()
        val view = LyricsView(ApplicationProvider.getApplicationContext<Application>()).apply {
            onTap = { taps++ }
            onSeekTo = { seeks++ }
            onSwipe = { tracks += it }
            measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY))
            layout(0, 0, 400, 300)
        }
        fun touch(action: Int, x: Float, y: Float = 150f) {
            MotionEvent.obtain(0, 20, action, x, y, 0).also {
                view.dispatchTouchEvent(it)
                it.recycle()
            }
        }
    }

    @Test fun `fast track swipes without move never seek or toggle lyrics`() {
        for (synced in listOf(false, true)) {
            val s = Screen()
            s.view.setLyrics(Lyrics(listOf(LyricLine(0, listOf("A lyric line"))), synced))
            s.touch(MotionEvent.ACTION_DOWN, 350f)
            s.touch(MotionEvent.ACTION_UP, 150f)
            assertEquals(listOf(true), s.tracks)
            assertEquals(0, s.taps)
            assertEquals(0, s.seeks)
        }
    }

    @Test fun `vertical release without move is not a lyric tap`() {
        val s = Screen()
        s.touch(MotionEvent.ACTION_DOWN, 200f, 30f)
        s.touch(MotionEvent.ACTION_UP, 200f, 150f)
        assertEquals(0, s.taps)
        assertTrue(s.tracks.isEmpty())
    }

    @Test fun `tap and cancelled swipe keep their distinct actions`() {
        val s = Screen()
        s.touch(MotionEvent.ACTION_DOWN, 200f)
        s.touch(MotionEvent.ACTION_UP, 200f)
        assertEquals(1, s.taps)
        s.touch(MotionEvent.ACTION_DOWN, 350f)
        s.touch(MotionEvent.ACTION_MOVE, 150f)
        s.touch(MotionEvent.ACTION_CANCEL, 150f)
        assertEquals(1, s.taps)
        assertTrue(s.tracks.isEmpty())
    }
}
