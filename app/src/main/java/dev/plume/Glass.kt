package dev.plume

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
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
