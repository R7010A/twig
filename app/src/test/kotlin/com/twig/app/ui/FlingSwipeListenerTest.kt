package com.twig.app.ui

import android.view.MotionEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FlingSwipeListenerTest {
    private fun screen(enabled: Boolean = true) = PaneSwipeListenerTest.Screen(listenerFactory = { context, onSwipe ->
        FlingSwipeListener(context, { enabled }, onSwipe)
    })

    @Test fun `fast release without move switches sides and cancels the row click`() {
        val s = screen()
        s.touch(MotionEvent.ACTION_DOWN, 200f, 0)
        s.touch(MotionEvent.ACTION_UP, 100f, 20)
        s.finish()
        assertEquals(1, s.swipes.size)
        assertTrue(s.swipes.single() < 0)
        assertEquals(1, s.cancels)
        assertEquals(0, s.clicks)
    }

    @Test fun `release crossing slop after a small move also cancels row clicks`() {
        val s = screen()
        s.touch(MotionEvent.ACTION_DOWN, 200f, 0)
        s.touch(MotionEvent.ACTION_MOVE, 199f, 5)
        s.touch(MotionEvent.ACTION_UP, 100f, 20)
        s.finish()
        assertEquals(1, s.swipes.size)
        assertEquals(0, s.clicks)
    }

    @Test fun `a normal fling switches once without opening the row`() {
        val s = screen()
        s.touch(MotionEvent.ACTION_DOWN, 200f, 0)
        s.touch(MotionEvent.ACTION_MOVE, 170f, 10)
        s.touch(MotionEvent.ACTION_MOVE, 120f, 20)
        s.touch(MotionEvent.ACTION_UP, 100f, 30)
        s.finish()
        assertEquals(1, s.swipes.size)
        assertEquals(0, s.clicks)
    }

    @Test fun `slow drags and vertical gestures do not switch sides or open rows`() {
        val slow = screen()
        slow.touch(MotionEvent.ACTION_DOWN, 200f, 0)
        slow.touch(MotionEvent.ACTION_UP, 100f, 1000)
        slow.finish()
        assertTrue(slow.swipes.isEmpty())
        assertEquals(0, slow.clicks)
        val vertical = screen()
        vertical.touch(MotionEvent.ACTION_DOWN, 100f, 0, y = 120f)
        vertical.touch(MotionEvent.ACTION_MOVE, 110f, 20, y = 30f)
        vertical.touch(MotionEvent.ACTION_UP, 115f, 30, y = 20f)
        vertical.finish()
        assertTrue(vertical.swipes.isEmpty())
        assertEquals(0, vertical.clicks)
    }

    @Test fun `cancelled swipes do not switch and taps still work when switching is disabled`() {
        val cancelled = screen()
        cancelled.touch(MotionEvent.ACTION_DOWN, 200f, 0)
        cancelled.touch(MotionEvent.ACTION_MOVE, 100f, 10)
        cancelled.touch(MotionEvent.ACTION_CANCEL, 100f, 20)
        cancelled.finish()
        assertTrue(cancelled.swipes.isEmpty())
        assertEquals(0, cancelled.clicks)
        val disabled = screen(enabled = false)
        disabled.touch(MotionEvent.ACTION_DOWN, 100f, 0)
        disabled.touch(MotionEvent.ACTION_UP, 100f, 20)
        disabled.finish()
        assertEquals(1, disabled.clicks)
        assertTrue(disabled.swipes.isEmpty())
    }
}
