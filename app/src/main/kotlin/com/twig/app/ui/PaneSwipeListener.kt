package com.twig.app.ui

import android.view.MotionEvent
import androidx.recyclerview.widget.RecyclerView

/** Claim a pane swipe so RecyclerView cancels the row's pending click. */
internal class PaneSwipeListener(
    private val threshold: Float,
    private val onDown: () -> Unit,
    private val onSwipe: (Float) -> Unit,
) : RecyclerView.SimpleOnItemTouchListener() {
    private var downX = 0f
    private var downY = 0f
    private var swiped = false
    private var propertiesTouch = false

    override fun onInterceptTouchEvent(rv: RecyclerView, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y; swiped = false
                propertiesTouch = rv.findChildViewUnder(event.x, event.y) is InfoCardLayout
                onDown()
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> if (!swiped && !propertiesTouch) {
                val dx = event.x - downX
                val dy = event.y - downY
                if (SwipeGesture.axis(dx, dy, threshold) == SwipeGesture.HORIZONTAL) {
                    swiped = true
                    onSwipe(dx)
                }
            }
        }
        return swiped
    }
}
