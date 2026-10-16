package com.twig.app.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.webkit.WebView
import kotlin.math.abs
import kotlin.math.max

/** Single-finger axis locking and optional refresh; pinch gestures stay with WebView. */
open class WebsiteWebView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : WebView(ctx, attrs) {
    var pullToRefresh = false
    var lockScrollAxis = false
    var onPullProgress: (Float) -> Unit = {}
    var onRefresh: () -> Unit = {}
    private val pageTopProbe by lazy(LazyThreadSafetyMode.NONE) {
        context.assets.open("website_scroll_top.js").bufferedReader().use { it.readText() }
    }
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val refreshDistance = 144 * resources.displayMetrics.density
    private var startX = 0f
    private var startY = 0f
    private var axis = 0 // 0 undecided, 1 horizontal, 2 vertical
    private var multiTouch = false
    private var canPull = false
    private var pageAtTop = false
    private var gestureSerial = 0
    private var pulling = false
    private var pullDistance = 0f

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                axis = 0
                multiTouch = false
                canPull = pullToRefresh && !canScrollVertically(-1)
                pageAtTop = false
                pulling = false
                pullDistance = 0f
                val serial = ++gestureSerial
                if (canPull) requestPageTop(startX, startY) { top ->
                    // A late answer must not steal an ongoing scroll or a newer gesture.
                    if (serial == gestureSerial && axis == 0 && canPull) pageAtTop = top
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> gestureSerial++
            MotionEvent.ACTION_POINTER_DOWN -> {
                gestureSerial++
                multiTouch = true
                axis = 0
                canPull = false
                if (pulling) {
                    pullDistance = 0f
                    onPullProgress(0f)
                }
            }
        }
        val dx = event.x - startX
        val dy = event.y - startY
        if (!multiTouch && (event.actionMasked == MotionEvent.ACTION_MOVE ||
                event.actionMasked == MotionEvent.ACTION_UP)) {
            if (axis == 0 && max(abs(dx), abs(dy)) > slop) {
                axis = if (abs(dx) > abs(dy)) 1 else 2
                // Only a downward gesture starting at the page top may refresh.
                canPull = canPull && pageAtTop && axis == 2 && dy > 0
            }
            if (canPull && axis == 2 && !pulling) {
                val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                super.dispatchTouchEvent(cancel)
                cancel.recycle()
                pulling = true
            }
            if (pulling && !multiTouch) {
                pullDistance = max(0f, dy - slop)
                onPullProgress((pullDistance / refreshDistance).coerceIn(0f, 1f))
            }
        }
        if (pulling) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                val refresh = event.actionMasked == MotionEvent.ACTION_UP && !multiTouch && pullDistance >= refreshDistance
                pulling = false
                onPullProgress(0f)
                if (refresh) onRefresh()
            }
            // WebView already received CANCEL; never deliver half of a pointer sequence.
            return true
        }
        if (lockScrollAxis && !multiTouch && axis != 0) {
            val projected = MotionEvent.obtainNoHistory(event)
            projected.setLocation(if (axis == 2) startX else event.x, if (axis == 1) startY else event.y)
            val handled = super.dispatchTouchEvent(projected)
            projected.recycle()
            return handled
        }
        return super.dispatchTouchEvent(event)
    }

    /** WebView's own scroll offset misses fixed-header layouts with scrolling divs. */
    protected open fun requestPageTop(x: Float, y: Float, result: (Boolean) -> Unit) {
        if (width <= 0 || height <= 0) {
            result(false)
            return
        }
        evaluateJavascript("($pageTopProbe)(${x / width}, ${y / height})") { value -> result(value == "true") }
    }

}
