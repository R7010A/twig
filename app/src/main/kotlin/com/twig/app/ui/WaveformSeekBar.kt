package com.twig.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * Self-drawn waveform progress bar: equal-width vertical bars (fixed [BAR_DP] width
 * + [GAP_DP] gap), played in accent colour, unplayed in translucent white. Drag to
 * seek, press callbacks preview the time.
 *
 * Before the waveform loads, draws a "short flat bar" placeholder (square corners,
 * centred thin strip) to visually distinguish it from the real waveform and hint
 * that loading is in progress; the placeholder shares the same equal-width grid
 * as the waveform bars, so when loading completes the ups and downs grow in place
 * without jumping.
 */
class WaveformSeekBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Final position callback (ms) on drag release. */
    var onSeek: ((Long) -> Unit)? = null
    /** Preview position callback (ms) during drag, null indicates drag end. */
    var onPreview: ((Long?) -> Unit)? = null

    private var wave: ByteArray? = null
    private var displayed = FloatArray(0)
    private var growth: ValueAnimator? = null
    private var durationMs = 0L
    private var positionMs = 0L
    private var dragFraction = -1f // during drag, >= 0

    private val played = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    // Unplayed bumped to ~170 alpha; the original 90 was too pale on a frosted glass background
    private val unplayed = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(170, 255, 255, 255) }

    fun setWaveform(data: ByteArray?) {
        val next = data?.takeIf { it.isNotEmpty() }
        if (next != null && wave?.contentEquals(next) == true) return
        growth?.cancel()
        growth = null
        wave = next
        if (next == null) displayed = FloatArray(0)
        else if (height > 0) animateWaveform(next)
        invalidate()
    }

    private fun targetHeight(value: Int): Float = if (value == 0) dp(PLACEHOLDER_DP)
        else (height * (0.06f + value / 255f * 0.94f)).coerceAtLeast(dp(2f))

    private fun animateWaveform(data: ByteArray) {
        val from = if (displayed.size == data.size) displayed.copyOf()
            else FloatArray(data.size) { dp(PLACEHOLDER_DP) }
        val target = FloatArray(data.size) { targetHeight(data[it].toInt() and 0xFF) }
        displayed = from.copyOf()
        growth = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                val fraction = animator.animatedValue as Float
                for (i in displayed.indices) displayed[i] = from[i] + (target[i] - from[i]) * fraction
                invalidate()
            }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (h != oldh) {
            growth?.cancel()
            displayed = FloatArray(0)
            if (h > 0) wave?.let { animateWaveform(it) }
        }
    }

    override fun onDetachedFromWindow() {
        growth?.end()
        growth = null
        super.onDetachedFromWindow()
    }

    /** Played-bar colour dynamically adjusts to the cover's primary tone (0 = restore default accent). */
    fun setAccent(color: Int) { played.color = if (color != 0) color else accent; invalidate() }

    /** Unplayed-bar colour (derived by [MusicTint] from the background, no longer fixed translucent white). */
    fun setUnplayedColor(color: Int) { unplayed.color = color; invalidate() }

    fun setProgress(pos: Long, dur: Long) {
        positionMs = pos; durationMs = dur
        if (dragFraction < 0) invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0) return
        val data = wave
        val frac = if (dragFraction >= 0) dragFraction
        else if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        val playedX = frac * w

        val barW = dp(BAR_DP)
        val gap = dp(GAP_DP)
        val n = (((w + gap) / (barW + gap)).toInt()).coerceAtLeast(1)
        // Centred overall: the leftover space is split evenly across both ends
        val startX = (w - (n * barW + (n - 1) * gap)) / 2f

        if (data == null) {
            // Placeholder: short flat bar (square corners), fixed height, only colour
            // distinguishes played/unplayed, so it's still draggable before loading
            val bh = dp(PLACEHOLDER_DP)
            val top = (h - bh) / 2f
            for (i in 0 until n) {
                val left = startX + i * (barW + gap)
                canvas.drawRect(left, top, left + barW, top + bh,
                    if (left + barW / 2 <= playedX) played else unplayed)
            }
            return
        }

        val buckets = data.size
        for (i in 0 until n) {
            val bi = (i.toLong() * buckets / n).toInt().coerceIn(0, buckets - 1)
            // Animate height around the fixed horizontal centre, equally in both directions.
            val bh = if (displayed.size == buckets) displayed[bi]
                else targetHeight(data[bi].toInt() and 0xFF)
            val left = startX + i * (barW + gap)
            val top = (h - bh) / 2f
            canvas.drawRect(left, top, left + barW, top + bh,
                if (left + barW / 2 <= playedX) played else unplayed)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragFraction = (event.x / width).coerceIn(0f, 1f)
                onPreview?.invoke((dragFraction * durationMs).toLong())
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val f = (event.x / width).coerceIn(0f, 1f)
                dragFraction = -1f
                onPreview?.invoke(null)
                onSeek?.invoke((f * durationMs).toLong())
                performClick()
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { dragFraction = -1f; onPreview?.invoke(null); invalidate() }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    companion object {
        private val accent = Color.parseColor("#66BB6A")
        private const val BAR_DP = 1.5f        // each bar's fixed width
        private const val GAP_DP = 1.5f          // bar spacing
        private const val PLACEHOLDER_DP = 3f  // placeholder flat bar height while loading (short, square corners)
    }
}
