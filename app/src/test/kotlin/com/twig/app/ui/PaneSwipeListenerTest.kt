package com.twig.app.ui

import android.app.Activity
import android.content.Context
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PaneSwipeListenerTest {
    internal class Screen(
        properties: Boolean = false,
        listenerFactory: ((Context, (Float) -> Unit) -> RecyclerView.OnItemTouchListener)? = null,
    ) {
        var clicks = 0
        var cancels = 0
        val swipes = mutableListOf<Float>()
        val tabs = mutableListOf<Int>()
        private val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val list = RecyclerView(activity)
        val row = if (properties) InfoCardLayout(activity).apply {
            swipeEnabled = true
            onSwipe = { tabs += it }
        } else View(activity)

        init {
            row.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 180)
            row.setOnClickListener { clicks++ }
            row.setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_CANCEL) cancels++
                false
            }
            list.layoutManager = LinearLayoutManager(activity)
            list.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun getItemCount() = 1
                override fun onCreateViewHolder(parent: ViewGroup, type: Int) =
                    object : RecyclerView.ViewHolder(row) {}
                override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {}
            }
            val onSwipe: (Float) -> Unit = { swipes += it }
            list.addOnItemTouchListener(listenerFactory?.invoke(activity, onSwipe)
                ?: PaneSwipeListener(48f, {}, onSwipe))
            activity.setContentView(FrameLayout(activity).apply { addView(list) })
            shadowOf(Looper.getMainLooper()).idle()
            list.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY))
            list.layout(0, 0, 600, 300)
        }

        fun touch(action: Int, x: Float, time: Long, y: Float = 60f) {
            MotionEvent.obtain(0, time, action, x, y, 0).also {
                list.dispatchTouchEvent(it)
                it.recycle()
            }
        }
        fun finish() = shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun `a quick pane swipe cancels the node click and switches only once`() {
        val s = Screen()
        s.touch(MotionEvent.ACTION_DOWN, 100f, 0)
        s.touch(MotionEvent.ACTION_MOVE, 170f, 2)
        s.touch(MotionEvent.ACTION_MOVE, 190f, 3)
        s.touch(MotionEvent.ACTION_UP, 200f, 4)
        s.finish()
        assertEquals(listOf(70f), s.swipes)
        assertEquals(1, s.cancels)
        assertEquals(0, s.clicks)
    }

    @Test fun `a swipe delivered without a move event still cancels the node click`() {
        val s = Screen()
        s.touch(MotionEvent.ACTION_DOWN, 200f, 0)
        s.touch(MotionEvent.ACTION_UP, 100f, 2)
        s.finish()
        assertEquals(listOf(-100f), s.swipes)
        assertEquals(1, s.cancels)
        assertEquals(0, s.clicks)
    }

    @Test fun `a normal tap still clicks the node`() {
        val s = Screen()
        s.touch(MotionEvent.ACTION_DOWN, 100f, 0)
        s.touch(MotionEvent.ACTION_UP, 101f, 2)
        s.finish()
        assertTrue(s.swipes.isEmpty())
        assertEquals(1, s.clicks)
    }

    @Test fun `a vertical drag stays with the list`() {
        val s = Screen()
        s.touch(MotionEvent.ACTION_DOWN, 100f, 0, y = 120f)
        s.touch(MotionEvent.ACTION_MOVE, 110f, 2, y = 30f)
        s.touch(MotionEvent.ACTION_UP, 115f, 4, y = 20f)
        s.finish()
        assertTrue(s.swipes.isEmpty())
        assertEquals(0, s.clicks)
    }

    @Test fun `property swipes stay in the card`() {
        val s = Screen(properties = true)
        s.touch(MotionEvent.ACTION_DOWN, 200f, 0)
        s.touch(MotionEvent.ACTION_MOVE, 120f, 2)
        s.touch(MotionEvent.ACTION_UP, 100f, 4)
        s.finish()
        assertTrue(s.swipes.isEmpty())
        assertEquals(listOf(1), s.tabs)
        assertEquals(0, s.clicks)
    }
}
