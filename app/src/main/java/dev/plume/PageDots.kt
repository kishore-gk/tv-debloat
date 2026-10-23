package dev.plume

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.floor

/**
 * iOS-style page dots that show at most [max] at a time. When there are more items, the strip of dots slides
 * along as you page: the pill walks until it is one dot from an edge, then the dots glide past it, with the
 * edge dot shrunk (and the next one growing in) to show there's more that way. Everything (the pill's
 * stretch, the slide, the shrinking) runs off two animated numbers on the S-curve, so it moves as one.
 */
internal class PageDots @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : View(c, a) {
    var max = 7
    private val dp = resources.displayMetrics.density
    private val d = 6 * dp // dot
    private val pill = 18 * dp // current page
    private val gap = 6 * dp
    private val pad = 2 * dp // room for the soft shadow

    private var total = 0
    private var at = 0
    private var first = 0 // first item in the window (target)
    private var atF = 0f // animated
    private var startF = 0f // animated
    private var anim: ValueAnimator? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { setShadowLayer(1.5f * dp, 0f, 0.5f * dp, 0x40000000) }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f; color = 0x40000000 }
    private val r = RectF()

    private fun shown() = minOf(total, max)

    /** Show [count] items with [current] selected; animates from where the dots were unless [animate] is false. */
    fun set(count: Int, current: Int, animate: Boolean = true) {
        val sizeChanged = count != total
        total = count
        at = current.coerceIn(0, maxOf(0, count - 1))
        val n = shown()
        if (n >= 2) {
            // one dot of room on each side of the pill, unless that side really is the end
            if (at > first + n - 2) first = at - (n - 2)
            if (at < first + 1) first = at - 1
            first = first.coerceIn(0, total - n)
        } else first = 0
        visibility = if (n < 2) GONE else VISIBLE
        if (sizeChanged) requestLayout()
        anim?.cancel()
        if (!animate || sizeChanged || !isLaidOut) {
            atF = at.toFloat(); startF = first.toFloat(); invalidate(); return
        }
        val a0 = atF; val s0 = startF; val a1 = at.toFloat(); val s1 = first.toFloat()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = Motion.FOCUS_MS
            interpolator = Motion.S
            addUpdateListener { v -> val k = v.animatedValue as Float; atF = a0 + (a1 - a0) * k; startF = s0 + (s1 - s0) * k; invalidate() }
            start()
        }
    }

    override fun onMeasure(w: Int, h: Int) {
        val n = shown()
        val width = if (n < 2) 0f else n * d + (n - 1) * gap + (pill - d) + 2 * pad
        setMeasuredDimension(resolveSize(width.toInt() + 1, w), resolveSize((d + 2 * pad).toInt() + 1, h))
    }

    private fun widthOf(i: Int) = d + (pill - d) * (1f - abs(i - atF)).coerceIn(0f, 1f)

    /** 1 well inside the window, 0.66 on its edge (when more lies beyond), 0 one step outside. */
    private fun edgeScale(e: Float) = when {
        e >= 1f -> 1f
        e >= 0f -> 0.66f + 0.34f * e
        e > -1f -> 0.66f * (1f + e)
        else -> 0f
    }

    override fun onDraw(c: Canvas) {
        val n = shown()
        if (n < 2) return
        // left edge of every dot along the whole strip
        val xs = FloatArray(total + 1)
        for (i in 0 until total) xs[i + 1] = xs[i] + widthOf(i) + gap
        val s0 = floor(startF).toInt().coerceIn(0, total - 1)
        val offset = xs[s0] + (xs[minOf(s0 + 1, total)] - xs[s0]) * (startF - s0)
        val cy = height / 2f
        c.save()
        c.clipRect(0f, 0f, width.toFloat(), height.toFloat())
        for (i in 0 until total) {
            val rel = i - startF
            if (rel < -1f || rel > n) continue
            var sc = 1f
            if (i > 0) sc = minOf(sc, edgeScale(rel)) // more before it
            if (i < total - 1) sc = minOf(sc, edgeScale(n - 1 - rel)) // more after it
            if (sc <= 0.01f) continue
            val w = widthOf(i)
            val on = (1f - abs(i - atF)).coerceIn(0f, 1f)
            val cx = pad + xs[i] - offset + w / 2f
            val hw = w * sc / 2f; val hh = d * sc / 2f
            r.set(cx - hw, cy - hh, cx + hw, cy + hh)
            val alpha = (sc / 0.66f).coerceAtMost(1f)
            val base = 0x8C + ((0xFF - 0x8C) * on).toInt() // dim white to full white as it becomes the pill
            paint.color = ((base * alpha).toInt() shl 24) or 0xFFFFFF
            edge.alpha = (0x40 * alpha).toInt()
            c.drawRoundRect(r, hh, hh, paint)
            c.drawRoundRect(r, hh, hh, edge) // a fine dark edge, so the dots show on white pictures too
        }
        c.restore()
    }
}
