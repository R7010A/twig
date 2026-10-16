package com.twig.app.ui

import android.app.Application
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.twig.core.XFile
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TreemapSwipeTest {
    private class Screen {
        var taps = 0
        val swipes = mutableListOf<Float>()
        val view = TreemapView(ApplicationProvider.getApplicationContext<Application>()).apply {
            onTapTile = { taps++ }
            onSwipe = { swipes += it }
            measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY))
            layout(0, 0, 400, 300)
            val root = TreemapEntry(XFile("file", "/root", true), true, 100, mutableListOf(), null)
            root.children!!.add(TreemapEntry(XFile("file", "/root/file", false), false, 100, null, root))
            show(root)
        }
        fun touch(action: Int, x: Float, y: Float = 150f) {
            MotionEvent.obtain(0, 20, action, x, y, 0).also {
                view.dispatchTouchEvent(it)
                it.recycle()
            }
        }
    }

    @Test fun `fast pane swipes do not open the tile under release`() {
        for (withMove in listOf(false, true)) {
            val s = Screen()
            s.touch(MotionEvent.ACTION_DOWN, 350f)
            if (withMove) s.touch(MotionEvent.ACTION_MOVE, 150f)
            s.touch(MotionEvent.ACTION_UP, 100f)
            assertEquals(1, s.swipes.size)
            assertEquals(0, s.taps)
        }
    }

    @Test fun `vertical releases and cancellations do not open tiles`() {
        val s = Screen()
        s.touch(MotionEvent.ACTION_DOWN, 200f, 30f)
        s.touch(MotionEvent.ACTION_UP, 200f, 150f)
        s.touch(MotionEvent.ACTION_DOWN, 350f)
        s.touch(MotionEvent.ACTION_CANCEL, 100f)
        assertEquals(0, s.taps)
        assertTrue(s.swipes.isEmpty())
    }

    @Test fun `normal taps still open tiles`() {
        val s = Screen()
        s.touch(MotionEvent.ACTION_DOWN, 200f)
        s.touch(MotionEvent.ACTION_UP, 200f)
        assertEquals(1, s.taps)
        assertTrue(s.swipes.isEmpty())
    }

    @Test fun `zoomed map drags pan instead of switching panes`() {
        val s = Screen()
        ReflectionHelpers.setField(s.view, "scale", 2f)
        s.touch(MotionEvent.ACTION_DOWN, 350f)
        s.touch(MotionEvent.ACTION_MOVE, 150f)
        s.touch(MotionEvent.ACTION_UP, 100f)
        assertTrue(s.swipes.isEmpty())
        assertEquals(0, s.taps)
        assertTrue(ReflectionHelpers.getField<Float>(s.view, "tx") < 0f)
    }
}
