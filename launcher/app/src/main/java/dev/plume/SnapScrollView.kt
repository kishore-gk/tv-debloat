package dev.plume

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.HorizontalScrollView

/**
 * A row that scrolls by whole items: when focus moves past an edge, the focused item lands flush with
 * that edge of the padded area, so the row always shows full tiles and never half-cut ones. The items
 * sit on a fixed grid that exactly fills the area, so aligning one edge aligns them all.
 */
class SnapScrollView(context: Context, attrs: AttributeSet?) : HorizontalScrollView(context, attrs) {
    init {
        // Clip to our own bounds. The parent can't do it: it leaves clipping off so other views can lift.
        outlineProvider = ViewOutlineProvider.BOUNDS
        clipToOutline = true
        clipChildren = false // or the row inside clips the first tile's focus lift to its own edge

    }

    /** While true, Android's own jump-to-focus is held back, so the row can glide there instead. */
    private var deferJump = false
    private var glide: android.animation.ValueAnimator? = null
    private var glideTo = 0
    private val focusRect = Rect()

    private fun delta(rect: Rect, base: Int): Int {
        val left = base + paddingLeft
        val right = base + width - paddingRight
        return when {
            rect.left < left -> rect.left - left
            rect.right > right -> rect.right - right
            else -> 0
        }
    }

    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int = if (deferJump) 0 else delta(rect, scrollX)

    /** Focus moved to a tile: slide the row to it on the S-curve (Android would jump there in one frame). */
    override fun requestChildFocus(child: View?, focused: View?) {
        deferJump = true
        try { super.requestChildFocus(child, focused) } finally { deferJump = false }
        if (focused == null || !isLaidOut) return
        focused.getDrawingRect(focusRect)
        offsetDescendantRectToMyCoords(focused, focusRect)
        val from = if (glide?.isRunning == true) glideTo else scrollX // keep heading on from where it was going
        val content = getChildAt(0)?.width ?: return
        val max = maxOf(0, content - (width - paddingLeft - paddingRight))
        val to = (from + delta(focusRect, from)).coerceIn(0, max)
        if (to == from && glide?.isRunning != true) return
        glide?.cancel()
        glideTo = to
        glide = android.animation.ValueAnimator.ofInt(scrollX, to).apply {
            duration = Motion.FOCUS_MS + 60
            interpolator = Motion.S
            addUpdateListener { scrollTo(it.animatedValue as Int, scrollY) }
            start()
        }
    }
}
