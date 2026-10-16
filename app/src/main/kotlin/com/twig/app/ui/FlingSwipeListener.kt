package com.twig.app.ui

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

/** A side-switch fling owns the list gesture, including a release that first reveals the drag. */
internal class FlingSwipeListener(
    context: Context,
    private val enabled: () -> Boolean,
    private val onSwipe: (Float) -> Unit,
) : RecyclerView.SimpleOnItemTouchListener() {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var swiping = false
    private var delivered = false
    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (!swiping || !enabled() || abs(vx) <= abs(vy) || abs(vx) < MIN_VELOCITY) return false
            deliver(vx)
            return true
        }
    })

    private fun deliver(direction: Float) {
        if (!delivered) {
            delivered = true
            onSwipe(direction)
        }
    }

    override fun onInterceptTouchEvent(rv: RecyclerView, event: MotionEvent): Boolean {
        val wasSwiping = swiping
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y; downTime = event.eventTime
                swiping = false; delivered = false
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> if (!swiping && enabled()) {
                swiping = SwipeGesture.axis(event.x - downX, event.y - downY, slop, 1.5f) == SwipeGesture.HORIZONTAL
            }
        }
        if (event.actionMasked == MotionEvent.ACTION_UP && swiping && !wasSwiping) {
            // GestureDetector still considers this a tap when no MOVE crossed touch slop.
            SwipeGesture.cancel(event) { detector.onTouchEvent(it) }
            val velocity = (event.x - downX) * 1000 / (event.eventTime - downTime).coerceAtLeast(1)
            if (abs(velocity) >= MIN_VELOCITY) deliver(velocity)
        } else detector.onTouchEvent(event)
        return swiping
    }

    override fun onTouchEvent(rv: RecyclerView, event: MotionEvent) {
        detector.onTouchEvent(event)
    }

    private companion object { const val MIN_VELOCITY = 600f }
}
