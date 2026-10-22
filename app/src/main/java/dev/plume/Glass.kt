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
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * Every animation moves on one S-curve: a gentle start, the travel in the middle, a gentle landing (a cubic
 * ease-in-out). EMPHASIZED and STANDARD are kept as names for where they're used; both are the S-curve.
 */
internal object Motion {
    val S = PathInterpolator(0.65f, 0f, 0.35f, 1f)
    val EMPHASIZED = S
    val STANDARD = S
    const val FOCUS_MS = 340L
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

    private companion object {
        const val HOLD_MS = 3_000L
    }

    /** The picture shown (or on its way in); null is the plain background colour. */
    var current: Bitmap? = null
        private set

    init {
        setBackgroundColor(0xFF000000.toInt()) // only until a picture covers the screen (see show)
    }

    /**
     * Pause the slow zoom while the remote is in use, and resume it once it has been quiet for a moment. The
     * zoom redraws the whole screen every frame, the biggest single cost while moving around.
     */
    fun hold() {
        drift.forEach { if (it?.isRunning == true) it.pause() }
        removeCallbacks(resume)
        postDelayed(resume, HOLD_MS)
        heldUntil = SystemClock.uptimeMillis() + HOLD_MS
    }

    private var heldUntil = 0L
    private val resume = Runnable { drift.forEach { if (it?.isPaused == true) it.resume() } }

    fun show(bmp: Bitmap?, fadeMs: Long = Motion.FADE_MS) {
        if (bmp === current) return
        current = bmp
        // A picture covers the whole screen, so nothing needs painting under it: the black ground is dropped once
        // the picture has faded in (one full-screen fill less on every frame), and comes back when there's none.
        if (bmp == null) setBackgroundColor(0xFF000000.toInt())
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
            .withEndAction {
                if (current === bmp) {
                    prev.setImageDrawable(null) // keep only one picture in memory
                    drift[1 - i]?.cancel() // and stop zooming the empty layer
                    if (bmp != null) background = null
                }
            }
            .start()
        if (bmp != null) {
            drift[i] = ObjectAnimator.ofPropertyValuesHolder(next,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, Motion.KEN_BURNS_SCALE),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, Motion.KEN_BURNS_SCALE),
            ).apply {
                duration = Motion.KEN_BURNS_MS
                interpolator = Motion.S
                start()
                if (SystemClock.uptimeMillis() < heldUntil) pause()
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

    /** Colour laid over the picture. */
    var tint = 0x40202024
        set(v) { field = v; invalidate() }

    var base = 0xFF0E0E12.toInt()

    /**
     * The panel's shape, drawn rather than clipped (clipping to a path costs the GPU on every frame): continuous
     * corners of this radius, or a capsule when [capsule].
     */
    var corner = 0f
        set(v) { field = v; shapeDirty = true; invalidate() }
    var capsule = false
        set(v) { field = v; shapeDirty = true; invalidate() }

    private val shape = Path()
    private val shaders = arrayOfNulls<android.graphics.BitmapShader>(2)
    private val shaderOf = arrayOfNulls<Bitmap>(2)
    private var shapeDirty = true
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pics = arrayOf(
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply { colorFilter = paint.colorFilter },
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply { colorFilter = paint.colorFilter },
    )

    init {
        // The picture comes in already blurred (see blurred()): no live blur, which ran on every frame.
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { shapeDirty = true }

    private fun shape(): Path {
        if (shapeDirty) {
            shapeDirty = false
            val w = width.toFloat()
            val h = height.toFloat()
            shape.reset()
            when {
                capsule -> shape.addRoundRect(0f, 0f, w, h, h / 2f, h / 2f, Path.Direction.CW)
                corner > 0f -> Smooth.path(shape, 0f, 0f, w, h, corner)
                else -> shape.addRect(0f, 0f, w, h, Path.Direction.CW)
            }
        }
        return shape
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

    private val bright = Ease(this)
    private var lum = 0f
    private var lumOf: Bitmap? = null
    private val lumAt = IntArray(2)

    /** Extra smoke laid on bright pictures, so white logos and icons on the glass still read (iOS adapts the same way). */
    var adapt = true

    override fun onDraw(c: Canvas) {
        val sh = shape()
        fill.color = base
        c.drawPath(sh, fill)
        val root = rootView
        getLocationInWindow(at)
        root.getLocationInWindow(rootAt)
        paintLayer(c, sh, pics[0], from, 1f - mix, root.width, root.height)
        paintLayer(c, sh, pics[1], to, mix, root.width, root.height)
        fill.color = tint
        c.drawPath(sh, fill)
        val b = to
        if (adapt && b != null) {
            // Brightness only re-sampled when the picture or this panel's place on screen changes.
            if (b !== lumOf || at[0] != lumAt[0] || at[1] != lumAt[1]) {
                lum = backdropLuminance(b, this, at, rootAt)
                lumOf = b
                lumAt[0] = at[0]; lumAt[1] = at[1]
            }
            val t = bright.to(brightness(lum))
            if (t > 0f) {
                fill.color = Color.argb((t * 0x59).toInt(), 0x1C, 0x1C, 0x1E)
                c.drawPath(sh, fill)
            }
        }
    }

    /** Same centre crop as the full-screen backdrop, shifted by where this view sits on screen. */
    private fun paintLayer(c: Canvas, sh: Path, pic: Paint, b: Bitmap?, alpha: Float, w: Int, h: Int) {
        if (b == null || alpha <= 0f || w == 0 || h == 0) return
        // A scaled parent (the resting dock) shrinks what we draw; draw the picture that much bigger so it still
        // lines up with the screen.
        var z = 1f
        var p: View? = this
        while (p != null) { z *= p.scaleX; p = p.parent as? View }
        val s = maxOf(w.toFloat() / b.width, h.toFloat() / b.height) / z
        m.setScale(s, s)
        m.postTranslate(((w - b.width * s * z) / 2f - (at[0] - rootAt[0])) / z, ((h - b.height * s * z) / 2f - (at[1] - rootAt[1])) / z)
        // One shader per picture, reused; only its placement changes from frame to frame.
        val i = if (pic === pics[0]) 0 else 1
        val shader = shaders[i]?.takeIf { shaderOf[i] === b }
            ?: android.graphics.BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also { shaders[i] = it; shaderOf[i] = b; pic.shader = it }
        shader.setLocalMatrix(m)
        pic.alpha = (alpha * 255).toInt()
        c.drawPath(sh, pic)
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

/**
 * How bright the backdrop is behind [v], 0 (black) .. 1 (white), sampled from the small backdrop copy [b] on a
 * coarse grid. Glass uses it to adapt like iOS materials: white frost on dark pictures, smoked glass on bright ones.
 */
internal fun backdropLuminance(b: Bitmap, v: View, at: IntArray, rootAt: IntArray): Float {
    val root = v.rootView
    if (root.width == 0 || v.width == 0) return 0f
    val s = maxOf(root.width.toFloat() / b.width, root.height.toFloat() / b.height)
    val ox = (root.width - b.width * s) / 2f
    val oy = (root.height - b.height * s) / 2f
    var sum = 0f
    var n = 0
    for (i in 0 until 8) for (j in 0 until 4) {
        val sx = at[0] - rootAt[0] + v.width * (i + 0.5f) / 8f
        val sy = at[1] - rootAt[1] + v.height * (j + 0.5f) / 4f
        val bx = ((sx - ox) / s).toInt().coerceIn(0, b.width - 1)
        val by = ((sy - oy) / s).toInt().coerceIn(0, b.height - 1)
        val c = b.getPixel(bx, by)
        sum += (0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)) / 255f
        n++
    }
    return sum / n
}

/** 0 on dark pictures, 1 on bright ones, with a soft band between (so mid-grey pictures don't flicker). */
internal fun brightness(lum: Float): Float {
    val t = ((lum - 0.5f) / 0.25f).coerceIn(0f, 1f)
    return t * t * (3 - 2 * t)
}

/** Eases a 0..1 value towards a target on the S-curve, redrawing [v] as it goes. */
internal class Ease(private val v: View, private val ms: Long = 600L) {
    var value = -1f
        private set
    private var target = -1f
    private var anim: ValueAnimator? = null

    fun to(t: Float): Float {
        if (value < 0f) { value = t; target = t; return t }
        if (kotlin.math.abs(t - target) < 0.04f) return value
        target = t
        anim?.cancel()
        anim = ValueAnimator.ofFloat(value, t).apply {
            duration = ms
            interpolator = Motion.S
            addUpdateListener { value = it.animatedValue as Float; v.invalidate() }
            start()
        }
        return value
    }
}

internal object Glass {
    /** Continuous-corner clip for a glass panel, so the blur and the tiles' glow stay inside its corners. */
    fun round(v: View, radius: Float) = Smooth.clip(v, radius)

    /** The glass edge, drawn over a panel: a hairline rim, brightest along the top where light catches it. */
    fun rim(radius: Float, density: Float): Drawable = RimDrawable(radius, density, 0x47FFFFFF, 0x14FFFFFF)
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

    private var glide: ValueAnimator? = null
    private var shown = false

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        alpha = 0f
        // No elevation: a shadow under translucent glass shows through it as a dark box.
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

    /**
     * Glide to [tx] (in the parent's coordinates) on the S-curve; [y] is set at once. On the way it stretches along
     * its travel and slims a little, most in the middle of the move, back to its shape as it lands.
     */
    fun moveTo(tx: Float, y: Float) {
        translationY = y
        if (!shown) {
            shown = true
            glide?.cancel()
            translationX = tx
            scaleX = 0.9f
            scaleY = 0.9f
            animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(380).setInterpolator(Motion.S).start()
            return
        }
        glide?.cancel()
        val from = translationX
        val reach = (kotlin.math.abs(tx - from) / width.coerceAtLeast(1)).coerceAtMost(1.5f)
        glide = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (300 + 120 * reach).toLong()
            interpolator = Motion.S
            addUpdateListener {
                val p = it.animatedValue as Float
                translationX = from + (tx - from) * p
                val stretch = kotlin.math.sin(Math.PI * p).toFloat() * 0.22f * reach.coerceAtMost(1f)
                scaleX = 1f + stretch
                scaleY = 1f - stretch * 0.25f
            }
            start()
        }
    }

    fun hide() {
        if (!shown) return
        shown = false
        glide?.cancel()
        animate().alpha(0f).scaleX(0.94f).scaleY(0.94f).setDuration(240).setInterpolator(Motion.S).start()
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
 * Text over the artwork in a colour taken from the picture behind it (see [tintFor]). Never drawn through an
 * offscreen layer when it fades, so its shadow isn't cropped to a box.
 */
class TintText(context: Context, attrs: AttributeSet?) : android.widget.TextView(context, attrs) {
    override fun hasOverlappingRendering() = false
}

/** The clock and date, tinted like [TintText]. */
class TintClock(context: Context, attrs: AttributeSet?) : android.widget.TextClock(context, attrs) {
    override fun hasOverlappingRendering() = false
}

/** Average colour of the backdrop behind [v], from the small backdrop copy [b], on the same grid as [backdropLuminance]. */
internal fun backdropColor(b: Bitmap, v: View, at: IntArray, rootAt: IntArray): Int {
    val root = v.rootView
    if (root.width == 0 || v.width == 0) return Color.BLACK
    val s = maxOf(root.width.toFloat() / b.width, root.height.toFloat() / b.height)
    val ox = (root.width - b.width * s) / 2f
    val oy = (root.height - b.height * s) / 2f
    var r = 0; var g = 0; var bl = 0; var n = 0
    for (i in 0 until 8) for (j in 0 until 4) {
        val sx = at[0] - rootAt[0] + v.width * (i + 0.5f) / 8f
        val sy = at[1] - rootAt[1] + v.height * (j + 0.5f) / 4f
        val c = b.getPixel(((sx - ox) / s).toInt().coerceIn(0, b.width - 1), ((sy - oy) / s).toInt().coerceIn(0, b.height - 1))
        r += Color.red(c); g += Color.green(c); bl += Color.blue(c); n++
    }
    return Color.rgb(r / n, g / n, bl / n)
}

/** WCAG relative luminance of an sRGB colour, 0..1. */
internal fun relativeLuminance(c: Int): Double {
    fun lin(x: Int): Double { val v = x / 255.0; return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4) }
    return 0.2126 * lin(Color.red(c)) + 0.7152 * lin(Color.green(c)) + 0.0722 * lin(Color.blue(c))
}

internal fun contrast(a: Int, b: Int): Double {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}

/**
 * Text colour for a background: the background's own hue (so the text belongs to the picture, as on Apple TV),
 * pushed to the opposite brightness: a pale tint on dark pictures, a deep shade on bright ones. If that tint
 * doesn't reach 4.5:1 contrast (WCAG AA), it is pushed further until it does, ending at near-white or near-black.
 */
internal fun tintFor(bg: Int): Int {
    val hsv = FloatArray(3)
    Color.colorToHSV(bg, hsv)
    val lightText = contrast(Color.WHITE, bg) >= contrast(0xFF101010.toInt(), bg)
    var sat = if (lightText) minOf(hsv[1], 0.16f) else minOf(hsv[1] * 0.9f, 0.5f)
    var value = if (lightText) 0.98f else 0.24f
    var c = Color.HSVToColor(floatArrayOf(hsv[0], sat, value))
    var step = 0
    while (contrast(c, bg) < 4.5 && step < 12) {
        sat *= 0.7f
        value = if (lightText) minOf(1f, value + 0.01f) else maxOf(0.04f, value - 0.04f)
        c = Color.HSVToColor(floatArrayOf(hsv[0], sat, value))
        step++
    }
    return c
}

/**
 * A frame that sizes to its content and then stretches any [GlassView] in it to fill itself. FrameLayout only does
 * that when it holds two or more full-size children, so a glass capsule around one row of buttons came out empty.
 */
class GlassFrame(context: Context, attrs: AttributeSet?) : FrameLayout(context, attrs) {
    override fun onMeasure(w: Int, h: Int) {
        super.onMeasure(w, h)
        val ew = MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY)
        val eh = MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) (getChildAt(i) as? GlassView)?.measure(ew, eh)
    }

    /** The glass fills the whole frame, padding included (FrameLayout would inset it by the padding). */
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        for (i in 0 until childCount) (getChildAt(i) as? GlassView)?.layout(0, 0, r - l, b - t)
    }
}


/**
 * A soft blur done once on the CPU: three box-blur passes each way approximate a Gaussian. Run on the small backdrop
 * copy (~320 px wide) whenever the picture changes, it costs a few milliseconds, and the glass then draws the
 * finished picture instead of blurring live on every frame.
 */
internal fun blurred(src: Bitmap, radius: Int): Bitmap {
    val w = src.width
    val h = src.height
    val a = IntArray(w * h)
    src.getPixels(a, 0, w, 0, 0, w, h)
    val b = IntArray(w * h)
    repeat(3) {
        boxPass(a, b, w, h, radius, true)
        boxPass(b, a, w, h, radius, false)
    }
    return Bitmap.createBitmap(a, w, h, Bitmap.Config.ARGB_8888)
}

/** One running-sum box blur along rows ([horizontal]) or columns, edges clamped. */
private fun boxPass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
    val lines = if (horizontal) h else w
    val len = if (horizontal) w else h
    val span = 2 * r + 1
    for (line in 0 until lines) {
        fun idx(i: Int) = if (horizontal) line * w + i.coerceIn(0, len - 1) else i.coerceIn(0, len - 1) * w + line
        var sr = 0; var sg = 0; var sb = 0
        for (i in -r..r) { val c = src[idx(i)]; sr += (c shr 16) and 255; sg += (c shr 8) and 255; sb += c and 255 }
        for (i in 0 until len) {
            dst[idx(i)] = (0xFF shl 24) or ((sr / span) shl 16) or ((sg / span) shl 8) or (sb / span)
            val out = src[idx(i - r)]
            val inn = src[idx(i + r + 1)]
            sr += ((inn shr 16) and 255) - ((out shr 16) and 255)
            sg += ((inn shr 8) and 255) - ((out shr 8) and 255)
            sb += (inn and 255) - (out and 255)
        }
    }
}

/**
 * A dock tile's artwork drawn straight into its continuous-corner shape, so the tile needs no clipping (clipping to
 * a path costs the GPU every frame). The source drawable is painted into a bitmap once, at the tile's size.
 */
class TileArt(private val src: Drawable?, private val radius: Float, private val ground: Int, private val ground2: Int = ground) : Drawable() {
    private val shape = Path()
    private val back = Paint(Paint.ANTI_ALIAS_FLAG)
    private val art = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var bmp: Bitmap? = null

    override fun onBoundsChange(b: android.graphics.Rect) {
        Smooth.path(shape, b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), radius)
        back.shader = LinearGradient(0f, b.top.toFloat(), 0f, b.bottom.toFloat(), ground, ground2, Shader.TileMode.CLAMP)
        bmp?.recycle()
        bmp = null
        art.shader = null
        if (src == null || b.width() <= 0 || b.height() <= 0) return
        val bm = Bitmap.createBitmap(b.width(), b.height(), Bitmap.Config.ARGB_8888)
        // Centre-crop the artwork to the tile (banners are 16:9 like the tile; custom art may not be).
        val iw = src.intrinsicWidth.takeIf { it > 0 } ?: b.width()
        val ih = src.intrinsicHeight.takeIf { it > 0 } ?: b.height()
        val s = maxOf(b.width().toFloat() / iw, b.height().toFloat() / ih)
        val dw = (iw * s).toInt()
        val dh = (ih * s).toInt()
        val l = (b.width() - dw) / 2
        val t = (b.height() - dh) / 2
        src.setBounds(l, t, l + dw, t + dh)
        src.draw(Canvas(bm))
        bmp = bm
        art.shader = android.graphics.BitmapShader(bm, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply { setTranslate(b.left.toFloat(), b.top.toFloat()) })
        }
    }

    override fun draw(c: Canvas) {
        c.drawPath(shape, back)
        if (art.shader != null) c.drawPath(shape, art)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
