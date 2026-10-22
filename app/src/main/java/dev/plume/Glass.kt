package dev.plume

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.RectF
import android.graphics.PixelFormat
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Path
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView

/** tvOS / iOS style motion curves. */
internal object Motion {
    /** Fast start, long soft landing: focus lifts, sheets, text arriving. */
    val EMPHASIZED = PathInterpolator(0.2f, 0f, 0f, 1f)
    /** Symmetric ease for crossfades. */
    val STANDARD = PathInterpolator(0.4f, 0f, 0.2f, 1f)
    const val FOCUS_MS = 260L
    const val FADE_MS = 700L
    const val KEN_BURNS_MS = 16_000L
    const val KEN_BURNS_SCALE = 1.04f
}

/**
 * The full-screen picture behind everything: the focused Continue Watching item's artwork, or the wallpaper when
 * there is nothing to continue. New pictures crossfade in over the old one and then drift slowly (Ken Burns).
 */
class Backdrop(context: Context, attrs: AttributeSet?) : FrameLayout(context, attrs) {
    private val layers = Array(2) {
        ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }.also { addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)) }
    }
    private val drift = arrayOfNulls<ObjectAnimator>(2)
    private var front = 0

    /** The picture shown (or on its way in); null is the plain background colour. */
    var current: Bitmap? = null
        private set

    fun show(bmp: Bitmap?, fadeMs: Long = Motion.FADE_MS) {
        if (bmp === current) return
        current = bmp
        val i = 1 - front
        val next = layers[i]
        val prev = layers[front]
        front = i
        next.animate().cancel()
        drift[i]?.cancel()
        next.setImageBitmap(bmp)
        next.scaleX = 1f
        next.scaleY = 1f
        next.alpha = 0f
        next.bringToFront()
        next.animate().alpha(1f).setDuration(fadeMs).setInterpolator(Motion.STANDARD)
            .withEndAction { if (current === bmp) prev.setImageDrawable(null) } // keep only one picture in memory
            .start()
        if (bmp != null) {
            drift[i] = ObjectAnimator.ofPropertyValuesHolder(next,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, Motion.KEN_BURNS_SCALE),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, Motion.KEN_BURNS_SCALE),
            ).apply {
                duration = Motion.KEN_BURNS_MS
                interpolator = LinearInterpolator()
                start()
            }
        }
    }
}

/**
 * Frosted glass: draws the part of the backdrop that sits behind this view, blurred and tinted, so a panel looks
 * like glass over the picture. Android can't blur what is behind a view, so this view paints its own copy of the
 * backdrop (a small, cheap one; the blur hides the low resolution) lined up with the real one, and blurs itself.
 */
class GlassView(context: Context) : View(context) {
    private var from: Bitmap? = null
    private var to: Bitmap? = null
    private var mix = 1f
    private var fade: ValueAnimator? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply {
        // A touch more colour, as iOS materials do: blurred pictures otherwise look washed out.
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.5f) })
    }
    private val m = Matrix()
    private val at = IntArray(2)
    private val rootAt = IntArray(2)

    /** Colour laid over the blurred picture; it is blurred too, which is fine for a flat tint. */
    var tint = 0x40202024
        set(v) { field = v; invalidate() }

    var base = 0xFF0E0E12.toInt()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        if (Build.VERSION.SDK_INT >= 31) {
            val r = 56f * resources.displayMetrics.density / 2f // ~56 px on a 1080p TV
            setRenderEffect(RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP))
        } else {
            tint = 0xCC1A1A1E.toInt() // no blur before Android 12: a denser tint reads as frosted instead
        }
    }

    /** As tall as the panel around it, never taller: with no content of its own it would claim the whole screen. */
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(resolveSize(0, w), resolveSize(0, h))

    fun show(bmp: Bitmap?, fadeMs: Long = Motion.FADE_MS) {
        if (bmp === to) return
        fade?.cancel()
        from = if (mix >= 0.5f) to else from
        to = bmp
        mix = 0f
        fade = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = fadeMs
            interpolator = Motion.STANDARD
            addUpdateListener { mix = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(base)
        val root = rootView
        getLocationInWindow(at)
        root.getLocationInWindow(rootAt)
        paintLayer(c, from, 1f - mix, root.width, root.height)
        paintLayer(c, to, mix, root.width, root.height)
        c.drawColor(tint)
    }

    /** Same centre crop as the full-screen backdrop, shifted by where this view sits on screen. */
    private fun paintLayer(c: Canvas, b: Bitmap?, alpha: Float, w: Int, h: Int) {
        if (b == null || alpha <= 0f || w == 0 || h == 0) return
        val s = maxOf(w.toFloat() / b.width, h.toFloat() / b.height)
        m.setScale(s, s)
        m.postTranslate((w - b.width * s) / 2f - (at[0] - rootAt[0]), (h - b.height * s) / 2f - (at[1] - rootAt[1]))
        paint.alpha = (alpha * 255).toInt()
        c.drawBitmap(b, m, paint)
    }
}

/**
 * Apple's continuous ("squircle") corners: the curve eases into the straight edge instead of meeting it at a
 * point, which is what makes iOS shapes look soft. Bezier points from PaintCode's measurement of UIKit's
 * rounded rect, for one corner, mirrored to the other three.
 */
internal object Smooth {
    private const val EDGE = 1.52866483f
    // One corner from the top edge to the right edge, as (inset from the side, inset from the top) in radii.
    private val CORNER = floatArrayOf(
        1.08849323f, 0f, 0.86840689f, 0f, 0.66993427f, 0.06549600f, // cubic
        0.63149399f, 0.07491100f, // line
        0.37282392f, 0.16905899f, 0.16906013f, 0.37282401f, 0.07491176f, 0.63149399f, // cubic
        0.06549600f, 0.66993427f, // line
        0f, 0.86840701f, 0f, 1.08849299f, 0f, EDGE, // cubic
    )

    fun path(out: Path, l: Float, t: Float, r: Float, b: Float, radius: Float): Path {
        out.reset()
        val rr = minOf(radius, minOf(r - l, b - t) / 2f / EDGE)
        // Corner maps from (u, v) template space to the four corners, clockwise from the top right.
        val maps: Array<(Float, Float) -> Pair<Float, Float>> = arrayOf(
            { u, v -> (r - u * rr) to (t + v * rr) },
            { u, v -> (r - v * rr) to (b - u * rr) },
            { u, v -> (l + u * rr) to (b - v * rr) },
            { u, v -> (l + v * rr) to (t + u * rr) },
        )
        maps.forEachIndexed { i, m ->
            val (sx, sy) = m(EDGE, 0f)
            if (i == 0) out.moveTo(sx, sy) else out.lineTo(sx, sy)
            var k = 0
            fun pt(): Pair<Float, Float> = m(CORNER[k], CORNER[k + 1]).also { k += 2 }
            fun cubic() { val a = pt(); val c = pt(); val e = pt(); out.cubicTo(a.first, a.second, c.first, c.second, e.first, e.second) }
            fun line() { val a = pt(); out.lineTo(a.first, a.second) }
            cubic(); line(); cubic(); line(); cubic()
        }
        out.close()
        return out
    }

    /** Clip a view to continuous corners (Android 13+ can clip to any path; older ones get a plain round rect). */
    fun clip(v: View, radius: Float) {
        v.outlineProvider = object : ViewOutlineProvider() {
            private val p = Path()
            override fun getOutline(view: View, o: Outline) {
                if (Build.VERSION.SDK_INT >= 33) o.setPath(path(p, 0f, 0f, view.width.toFloat(), view.height.toFloat(), radius))
                else o.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        v.clipToOutline = true
    }
}

internal object Glass {
    /** Continuous-corner clip for a glass panel, so the blur and the tiles' glow stay inside its corners. */
    fun round(v: View, radius: Float) = Smooth.clip(v, radius)

    /** The glass edge, drawn over a panel: a hairline rim, brightest along the top where light catches it. */
    fun rim(radius: Float, density: Float): Drawable = RimDrawable(radius, density, 0x47FFFFFF, 0x14FFFFFF)

    /** A small glass pill (buttons on the picture): translucent white with a hairline rim, solid white when lit. */
    fun chip(radius: Float, lit: Boolean): Drawable = GradientDrawable().apply {
        cornerRadius = radius
        setColor(if (lit) 0xFFF5F5F7.toInt() else 0x38FFFFFF)
        if (!lit) setStroke(1, 0x33FFFFFF)
    }
}

/** A hairline glass rim on continuous corners: [top] colour along the top edge fading to [bottom] at the bottom. */
class RimDrawable(private val radius: Float, density: Float, private val top: Int, private val bottom: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = maxOf(1f, density) }
    private val path = Path()

    override fun onBoundsChange(b: android.graphics.Rect) {
        val i = paint.strokeWidth / 2
        Smooth.path(path, b.left + i, b.top + i, b.right - i, b.bottom - i, radius)
        paint.shader = LinearGradient(0f, b.top.toFloat(), 0f, b.bottom.toFloat(), top, bottom, Shader.TileMode.CLAMP)
    }

    override fun draw(c: Canvas) = c.drawPath(path, paint)
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * iOS "Liquid Glass" focus lens: one glass plate behind the focused dock tile, its corners concentric with the
 * tile's. When focus moves it glides to the next tile on a spring, stretching along the way it travels and
 * squashing a little, then settles with a soft wobble. A rim lit from the top left and a gloss on its upper half
 * make it read as a piece of glass.
 */
class FocusLens(context: Context, private val radius: Float) : View(context) {
    private val d = resources.displayMetrics.density
    private val shape = Path()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x2BFFFFFF }
    private val gloss = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d }

    // Spring state, in px and px/s along x (the dock only moves sideways).
    private var x = 0f
    private var v = 0f
    private var target = 0f
    private var last = 0L
    private var running = false
    private var shown = false

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        alpha = 0f
        // Soft shadow under the glass plate, like iOS controls floating over content.
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, o: Outline) = o.setRoundRect(0, 0, view.width, view.height, radius)
        }
        elevation = 6f * d
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val i = rim.strokeWidth / 2
        Smooth.path(shape, i, i, w - i, h - i, radius)
        rim.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(0xD9FFFFFF.toInt(), 0x40FFFFFF, 0x1AFFFFFF, 0x73FFFFFF), floatArrayOf(0f, 0.4f, 0.72f, 1f), Shader.TileMode.CLAMP)
        gloss.shader = LinearGradient(0f, 0f, 0f, h * 0.55f, 0x38FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        c.drawPath(shape, fill)
        c.drawPath(shape, gloss)
        c.drawPath(shape, rim)
    }

    /** Glide to [tx] (in the parent's coordinates); [y] is set at once. Appears in place if it was hidden. */
    fun moveTo(tx: Float, y: Float) {
        translationY = y
        target = tx
        if (!shown) {
            shown = true
            x = tx
            v = 0f
            translationX = tx
            scaleX = 0.9f
            scaleY = 0.9f
            animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(360).setInterpolator(Motion.EMPHASIZED).start()
            return
        }
        if (!running) {
            running = true
            last = System.nanoTime()
            postOnAnimation(::step)
        }
    }

    fun hide() {
        if (!shown) return
        shown = false
        running = false
        animate().alpha(0f).scaleX(0.94f).scaleY(0.94f).setDuration(200).setInterpolator(Motion.STANDARD).start()
    }

    private fun step() {
        if (!running) return
        val now = System.nanoTime()
        val dt = ((now - last) / 1e9f).coerceAtMost(1f / 30f)
        last = now
        // A slightly underdamped spring (iOS's "bouncy" focus feel), integrated in small steps to stay stable.
        val k = STIFFNESS
        val damp = 2f * kotlin.math.sqrt(k) * DAMPING_RATIO
        repeat(4) {
            val h = dt / 4f
            v += (-k * (x - target) - damp * v) * h
            x += v * h
        }
        translationX = x
        val stretch = (kotlin.math.abs(v) / (width.coerceAtLeast(1) * 14f)).coerceAtMost(0.3f)
        scaleX = 1f + stretch
        scaleY = 1f - stretch * 0.2f
        if (kotlin.math.abs(x - target) < 0.5f && kotlin.math.abs(v) < 8f) {
            x = target
            translationX = x
            scaleX = 1f
            scaleY = 1f
            running = false
        } else {
            postOnAnimation(::step)
        }
    }

    private companion object {
        const val STIFFNESS = 420f
        const val DAMPING_RATIO = 0.68f
    }
}

/**
 * Light passing over glass: a diagonal band of light that sweeps across a tile when it gets focus (in the direction
 * focus moved), a soft gloss on the tile's top half, and a hairline rim on continuous corners.
 * [level] 0..1 fades the light in and out.
 */
class SheenDrawable(private val radius: Float, private val density: Float) : Drawable() {
    var progress = 0f
        set(v) { field = v; invalidateSelf() }
    var level = 0f
        set(v) { field = v; invalidateSelf() }

    private val band = Paint(Paint.ANTI_ALIAS_FLAG)
    private val top = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = maxOf(1f, density) }
    private val shape = Path()
    private val edge = Path()

    override fun onBoundsChange(b: android.graphics.Rect) {
        Smooth.path(shape, b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), radius)
        val i = rim.strokeWidth / 2
        Smooth.path(edge, b.left + i, b.top + i, b.right - i, b.bottom - i, radius)
    }

    override fun draw(c: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        val a = (level * 255).toInt()
        if (a > 0) {
            top.shader = LinearGradient(0f, b.top.toFloat(), 0f, b.top + h * 0.5f, Color.argb(a * 46 / 255, 255, 255, 255), 0, Shader.TileMode.CLAMP)
            c.drawPath(shape, top)
            val cx = b.left - w * 0.5f + progress * w * 2f
            band.shader = LinearGradient(cx - w * 0.35f, b.top.toFloat(), cx + w * 0.35f, b.top + h,
                intArrayOf(0, Color.argb(a * 90 / 255, 255, 255, 255), 0), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            c.drawPath(shape, band)
        }
        rim.color = Color.argb(51 + (level * 77).toInt(), 255, 255, 255)
        c.drawPath(edge, rim)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * Lock-screen glass digits (iOS 26): the clock is cut out of frosted glass. Each glyph shows the blurred picture
 * behind it, brightened, with a light edge along the top that fades down the strokes; no flat white fill.
 * [source] is the same small copy of the backdrop the dock's glass uses; scaled up, it is already soft.
 */
class GlassClock(context: Context, attrs: AttributeSet?) : android.widget.TextClock(context, attrs) {
    var source: Bitmap? = null
        set(v) { field = v; invalidate() }

    private val pic = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply {
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.6f) })
    }
    private val frost = Paint().apply {
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_ATOP)
    }
    private val m = Matrix()
    private val at = IntArray(2)
    private val rootAt = IntArray(2)
    private val edge = 1.4f * resources.displayMetrics.density

    override fun onDraw(c: Canvas) {
        val p = paint
        // 1. The glyphs as a mask, then the blurred picture inside them, lightened like frosted glass.
        val layer = c.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        p.style = Paint.Style.FILL
        super.onDraw(c)
        val b = source
        if (b != null) {
            val root = rootView
            getLocationInWindow(at)
            root.getLocationInWindow(rootAt)
            val s = maxOf(root.width.toFloat() / b.width, root.height.toFloat() / b.height)
            m.setScale(s, s)
            m.postTranslate((root.width - b.width * s) / 2f - (at[0] - rootAt[0]) + scrollX, (root.height - b.height * s) / 2f - (at[1] - rootAt[1]) + scrollY)
            c.drawBitmap(b, m, pic)
        }
        frost.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), if (b == null) 0xF2FFFFFF.toInt() else 0x8CFFFFFF.toInt(),
            if (b == null) 0xD9FFFFFF.toInt() else 0x59FFFFFF, Shader.TileMode.CLAMP)
        c.drawPaint(frost)
        c.restoreToCount(layer)
        // 2. The lit edge: a thin stroke, bright at the top of each glyph and fading towards the bottom.
        p.style = Paint.Style.STROKE
        p.strokeWidth = edge
        p.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), 0xF2FFFFFF.toInt(), 0x40FFFFFF, Shader.TileMode.CLAMP)
        super.onDraw(c)
        p.shader = null
        p.style = Paint.Style.FILL
    }
}
