package com.twig.app.ui

import android.app.Application
import android.content.Context
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WebsiteGesturesTest {
    private data class Touch(val action: Int, val x: Float, val y: Float, val pointers: Int)
    private class RecordingWebView(ctx: Context) : WebsiteWebView(ctx) {
        val delivered = mutableListOf<Touch>()
        var atTop = true
        var innerAtTop = true
        var deferProbe = false
        val probes = mutableListOf<(Boolean) -> Unit>()
        override fun requestPageTop(x: Float, y: Float, result: (Boolean) -> Unit) {
            if (deferProbe) probes += result else result(innerAtTop)
        }
        override fun canScrollVertically(direction: Int) = direction < 0 && !atTop
        override fun onTouchEvent(event: MotionEvent): Boolean {
            delivered += Touch(event.actionMasked, event.x, event.y, event.pointerCount)
            return true
        }
    }
    private fun web() = RecordingWebView(ApplicationProvider.getApplicationContext()).apply {
        layout(0, 0, 1000, 1600)
    }
    private fun RecordingWebView.send(action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(0, 100, action, x, y, 0)
        dispatchTouchEvent(event)
        event.recycle()
    }
    private fun RecordingWebView.twoPointers(action: Int, x: Float, y: Float) {
        val properties = Array(2) { index -> MotionEvent.PointerProperties().apply { id = index } }
        val coordinates = Array(2) { index -> MotionEvent.PointerCoords().apply {
            this.x = x + index * 80; this.y = y + index * 80
        } }
        val event = MotionEvent.obtain(0, 100, action, 2, properties, coordinates, 0, 0, 1f, 1f, 0, 0, 0, 0)
        dispatchTouchEvent(event)
        event.recycle()
    }

    @Test fun `vertical gesture cannot drift horizontally including on release`() {
        val web = web().apply { lockScrollAxis = true }
        web.send(MotionEvent.ACTION_DOWN, 20f, 20f)
        web.send(MotionEvent.ACTION_MOVE, 25f, 200f)
        web.send(MotionEvent.ACTION_MOVE, 500f, 300f)
        web.send(MotionEvent.ACTION_UP, 600f, 350f)
        assertEquals(listOf(20f, 20f, 20f, 20f), web.delivered.map { it.x })
        assertEquals(listOf(20f, 200f, 300f, 350f), web.delivered.map { it.y })
    }

    @Test fun `horizontal gesture cannot drift vertically and next gesture chooses its own axis`() {
        val web = web().apply { lockScrollAxis = true }
        web.send(MotionEvent.ACTION_DOWN, 20f, 20f)
        web.send(MotionEvent.ACTION_MOVE, 200f, 25f)
        web.send(MotionEvent.ACTION_MOVE, 300f, 500f)
        web.send(MotionEvent.ACTION_UP, 350f, 600f)
        assertEquals(listOf(20f, 20f, 20f, 20f), web.delivered.map { it.y })
        assertEquals(listOf(20f, 200f, 300f, 350f), web.delivered.map { it.x })
        web.send(MotionEvent.ACTION_DOWN, 40f, 40f)
        web.send(MotionEvent.ACTION_MOVE, 45f, 300f)
        assertEquals(40f, web.delivered.last().x)
        assertEquals(300f, web.delivered.last().y)
    }

    @Test fun `disabled locking forwards both axes unmodified`() {
        val web = web()
        web.send(MotionEvent.ACTION_DOWN, 20f, 20f)
        web.send(MotionEvent.ACTION_MOVE, 200f, 30f)
        web.send(MotionEvent.ACTION_MOVE, 300f, 500f)
        assertEquals(Touch(MotionEvent.ACTION_MOVE, 300f, 500f, 1), web.delivered.last())
    }

    @Test fun `pinch releases the axis lock for the rest of the gesture`() {
        val web = web().apply { lockScrollAxis = true; pullToRefresh = true }
        web.send(MotionEvent.ACTION_DOWN, 20f, 20f)
        web.send(MotionEvent.ACTION_MOVE, 200f, 25f)
        web.twoPointers(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 250f, 100f)
        web.twoPointers(MotionEvent.ACTION_MOVE, 300f, 400f)
        assertEquals(Touch(MotionEvent.ACTION_MOVE, 300f, 400f, 2), web.delivered.last())
        web.twoPointers(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 300f, 400f)
        web.send(MotionEvent.ACTION_MOVE, 400f, 600f)
        assertEquals(Touch(MotionEvent.ACTION_MOVE, 400f, 600f, 1), web.delivered.last())
    }

    @Test fun `pull at top cancels page touch and refreshes once only after release`() {
        val web = web().apply { pullToRefresh = true; lockScrollAxis = true }
        var refreshes = 0
        val progress = mutableListOf<Float>()
        web.onRefresh = { refreshes++ }
        web.onPullProgress = { progress += it }
        web.send(MotionEvent.ACTION_DOWN, 20f, 20f)
        web.send(MotionEvent.ACTION_MOVE, 25f, 600f)
        assertEquals(0, refreshes)
        web.send(MotionEvent.ACTION_UP, 30f, 600f)
        assertEquals(1, refreshes)
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), web.delivered.map { it.action })
        assertEquals(1f, progress.first())
        assertEquals(0f, progress.last())
    }

    @Test fun `refresh uses release distance including a missing move or a final reversal`() {
        for (reversed in listOf(false, true)) {
            val web = web().apply { pullToRefresh = true }
            var refreshes = 0
            web.onRefresh = { refreshes++ }
            web.send(MotionEvent.ACTION_DOWN, 20f, 20f)
            if (reversed) web.send(MotionEvent.ACTION_MOVE, 25f, 600f)
            web.send(MotionEvent.ACTION_UP, 25f, if (reversed) 20f else 600f)
            assertEquals(if (reversed) 0 else 1, refreshes)
            assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), web.delivered.map { it.action })
        }
    }

    @Test fun `disabled pull scrolled content and horizontal gestures never refresh`() {
        for (mode in 0..2) {
            val web = web().apply { pullToRefresh = mode != 0; atTop = mode != 1 }
            var refreshes = 0
            web.onRefresh = { refreshes++ }
            web.send(MotionEvent.ACTION_DOWN, 20f, 20f)
            if (mode == 2) web.send(MotionEvent.ACTION_MOVE, 300f, 25f)
            web.send(MotionEvent.ACTION_MOVE, 30f, 600f)
            web.send(MotionEvent.ACTION_UP, 30f, 600f)
            assertEquals("mode $mode", 0, refreshes)
            assertFalse(web.delivered.any { it.action == MotionEvent.ACTION_CANCEL })
        }
    }

    @Test fun `short cancelled reversed and multitouch pulls never refresh`() {
        for (mode in 0..3) {
            val web = web().apply { pullToRefresh = true }
            var refreshes = 0
            web.onRefresh = { refreshes++ }
            web.send(MotionEvent.ACTION_DOWN, 20f, 20f)
            web.send(MotionEvent.ACTION_MOVE, 25f, if (mode == 0) 60f else 600f)
            if (mode == 2) web.send(MotionEvent.ACTION_MOVE, 25f, 20f)
            if (mode == 3) web.twoPointers(MotionEvent.ACTION_POINTER_DOWN or
                (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 25f, 600f)
            val endY = when (mode) { 0 -> 60f; 2 -> 20f; else -> 600f }
            web.send(if (mode == 1) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, 25f, endY)
            assertEquals("mode $mode", 0, refreshes)
        }
    }

    @Test fun `a scrolling div below a fixed header must be at top when the gesture starts`() {
        val web = web().apply { pullToRefresh = true; innerAtTop = false }
        var refreshes = 0
        web.onRefresh = { refreshes++ }
        web.send(MotionEvent.ACTION_DOWN, 20f, 100f)
        // Reaching the top during this scroll must not turn it into a refresh.
        web.innerAtTop = true
        web.send(MotionEvent.ACTION_MOVE, 25f, 600f)
        web.send(MotionEvent.ACTION_UP, 25f, 600f)
        assertEquals(0, refreshes)
        assertFalse(web.delivered.any { it.action == MotionEvent.ACTION_CANCEL })
        web.send(MotionEvent.ACTION_DOWN, 20f, 100f)
        web.send(MotionEvent.ACTION_MOVE, 25f, 600f)
        web.send(MotionEvent.ACTION_UP, 25f, 600f)
        assertEquals(1, refreshes)
    }

    @Test fun `late and stale page probes never capture a running or newer scroll`() {
        val web = web().apply { pullToRefresh = true; deferProbe = true }
        var refreshes = 0
        web.onRefresh = { refreshes++ }
        web.send(MotionEvent.ACTION_DOWN, 20f, 100f)
        web.send(MotionEvent.ACTION_MOVE, 25f, 300f)
        web.probes[0](true)
        web.send(MotionEvent.ACTION_MOVE, 25f, 600f)
        web.send(MotionEvent.ACTION_UP, 25f, 600f)
        assertEquals(0, refreshes)
        web.send(MotionEvent.ACTION_DOWN, 20f, 100f)
        web.probes[0](true) // Previous gesture's answer is not permission for this one.
        web.send(MotionEvent.ACTION_MOVE, 25f, 600f)
        web.send(MotionEvent.ACTION_UP, 25f, 600f)
        assertEquals(0, refreshes)
        web.send(MotionEvent.ACTION_DOWN, 20f, 100f)
        web.probes[2](true)
        web.send(MotionEvent.ACTION_MOVE, 25f, 600f)
        web.send(MotionEvent.ACTION_UP, 25f, 600f)
        assertEquals(1, refreshes)
    }

}
