package com.twig.app.ui

import android.view.MotionEvent
import kotlin.math.abs

/** Shared intent checks; callers keep their own paging, scrolling and zoom behavior. */
internal object SwipeGesture {
    const val UNDECIDED = 0
    const val HORIZONTAL = 1
    const val VERTICAL = 2

    fun axis(dx: Float, dy: Float, slop: Float, horizontalBias: Float = 1f): Int = when {
        abs(dx) > slop && abs(dx) > abs(dy) * horizontalBias -> HORIZONTAL
        abs(dy) > slop -> VERTICAL
        else -> UNDECIDED
    }

    fun cancel(event: MotionEvent, dispatch: (MotionEvent) -> Unit) {
        val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
        try { dispatch(cancel) } finally { cancel.recycle() }
    }
}
