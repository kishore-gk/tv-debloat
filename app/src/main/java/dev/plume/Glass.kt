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
    const val KEN_BURNS_SCALE = 1.06f
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

internal object Glass {
    /** Rounded clip for a glass panel, so the blur and the tiles' glow stay inside its corners. */
    fun round(v: View, radius: Float) {
        v.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, o: Outline) = o.setRoundRect(0, 0, view.width, view.height, radius)
        }
        v.clipToOutline = true
    }

    /**
     * The glass edge, drawn over the panel: a hairline rim, brighter along the top where light catches it.
     * Clear inside, so it can sit in front of the content.
     */
    fun rim(radius: Float, density: Float): Drawable {
        val edge = GradientDrawable().apply {
            cornerRadius = radius
            setColor(0)
            setStroke(maxOf(1, density.toInt()), 0x2EFFFFFF) // white 18%
        }
        val top = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x1FFFFFFF, 0x00FFFFFF)).apply {
            cornerRadius = radius
        }
        return LayerDrawable(arrayOf(top, edge)).apply {
            setLayerGravity(0, Gravity.TOP or Gravity.FILL_HORIZONTAL)
            setLayerHeight(0, (radius * 1.5f).toInt())
        }
    }

    /** A small glass pill or circle (buttons on the picture): translucent white, hairline rim. */
    fun chip(radius: Float, lit: Boolean): Drawable = GradientDrawable().apply {
        cornerRadius = radius
        setColor(if (lit) 0xFFF5F5F7.toInt() else 0x33FFFFFF)
        if (!lit) setStroke(1, 0x26FFFFFF)
    }

    /**
     * Darkening over the backdrop so white text reads on any picture: strong on the left behind the show's
     * title, at the bottom behind the dock, and a little at the top behind the clock.
     */
    fun scrim(): Drawable = LayerDrawable(arrayOf(
        GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xD9000000.toInt(), 0x80000000.toInt(), 0x00000000)).apply { setDither(true) },
        GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xF2000000.toInt(), 0x99000000.toInt(), 0x00000000)).apply { setDither(true) },
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x8C000000.toInt(), 0x00000000)).apply { setDither(true) },
        ColorDrawable(0x14000000),
    )).apply {
        setLayerGravity(0, Gravity.START or Gravity.FILL_VERTICAL)
        setLayerGravity(1, Gravity.BOTTOM or Gravity.FILL_HORIZONTAL)
        setLayerGravity(2, Gravity.TOP or Gravity.FILL_HORIZONTAL)
    }

    /** Sizes the scrim's bands to the screen once it is laid out (gravity layers need explicit sizes). */
    fun sizeScrim(d: Drawable, w: Int, h: Int) {
        (d as LayerDrawable).apply {
            setLayerWidth(0, (w * 0.62f).toInt())
            setLayerHeight(1, (h * 0.62f).toInt())
            setLayerHeight(2, (h * 0.28f).toInt())
        }
    }
}

/**
 * iOS "Liquid Glass" focus lens: one glass plate behind the focused dock tile. When focus moves it glides to the
 * next tile on a spring, stretching along the way it travels and squashing a little, then settles with a soft
 * wobble. A bright rim (lit from the top left) and a gloss on its upper half make it read as a piece of glass.
 */
class FocusLens(context: Context) : View(context) {
    private val d = resources.displayMetrics.density
    private val radius = 18f * d
    private val box = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x2EFFFFFF }
    private val gloss = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 6f * d; color = 0x14FFFFFF }

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
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        rim.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(0xCCFFFFFF.toInt(), 0x33FFFFFF, 0x1AFFFFFF, 0x66FFFFFF), floatArrayOf(0f, 0.45f, 0.75f, 1f), Shader.TileMode.CLAMP)
        gloss.shader = LinearGradient(0f, 0f, 0f, h * 0.55f, 0x33FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        val inset = rim.strokeWidth / 2
        box.set(inset, inset, width - inset, height - inset)
        c.drawRoundRect(box, radius, radius, glow)
        c.drawRoundRect(box, radius, radius, fill)
        c.drawRoundRect(box, radius, radius, gloss)
        c.drawRoundRect(box, radius, radius, rim)
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
            scaleX = 0.92f
            scaleY = 0.92f
            animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(Motion.EMPHASIZED).start()
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
        val stretch = (kotlin.math.abs(v) / (width.coerceAtLeast(1) * 14f)).coerceAtMost(0.32f)
        scaleX = 1f + stretch
        scaleY = 1f - stretch * 0.22f
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
 * focus moved), plus a soft gloss on the tile's top half and a hairline rim. [level] 0..1 fades it all in and out.
 */
class SheenDrawable(private val radius: Float, private val density: Float) : Drawable() {
    var progress = 0f
        set(v) { field = v; invalidateSelf() }
    var level = 0f
        set(v) { field = v; invalidateSelf() }

    private val band = Paint(Paint.ANTI_ALIAS_FLAG)
    private val top = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = maxOf(1f, density) }
    private val box = RectF()

    override fun draw(c: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        box.set(b)
        val a = (level * 255).toInt()
        if (a > 0) {
            top.shader = LinearGradient(0f, b.top.toFloat(), 0f, b.top + h * 0.5f, Color.argb(a * 40 / 255, 255, 255, 255), 0, Shader.TileMode.CLAMP)
            c.drawRoundRect(box, radius, radius, top)
            val cx = b.left - w * 0.5f + progress * w * 2f
            band.shader = LinearGradient(cx - w * 0.35f, b.top.toFloat(), cx + w * 0.35f, b.top + h,
                intArrayOf(0, Color.argb(a * 90 / 255, 255, 255, 255), 0), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            c.drawRoundRect(box, radius, radius, band)
        }
        rim.color = Color.argb(46 + (level * 70).toInt(), 255, 255, 255)
        c.drawRoundRect(box, radius, radius, rim)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
