package dev.plume

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.io.File
import java.util.concurrent.Executors

/** Where the launcher leaves its current backdrop, so the news screen and screensaver open on the same picture. */
internal fun backdropFile(c: Context) = File(c.filesDir, "backdrop.jpg")

/**
 * The news screen, in Plume's glass style: the launcher's backdrop drifting behind, section tabs on a glass
 * capsule at the top, the clock top right, and one story at a time on a frosted panel: section, headline, source
 * and how long ago. Stories turn on their own every [STORY_MS] on the S-curve; at a section's end the next
 * section begins. Used by both [NewsActivity] (browsable) and [NewsDream] (the screensaver).
 */
class NewsView(context: Context) : FrameLayout(context) {
    private val d = resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val prefs = context.getSharedPreferences("home", Context.MODE_PRIVATE)

    private val backdrop = Backdrop(context, null)
    private val panelGlass = GlassView(context).apply { tint = 0x66141418; corner = 32f * d }
    private val tabsGlass = GlassView(context).apply { capsule = true }
    private val tabs = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val tabsScroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        isFocusable = false
        addView(tabs)
    }
    private val kicker = text(13f, "sans-serif-medium").apply { letterSpacing = 0.14f; isAllCaps = true; alpha = 0.75f }
    private val headline = text(34f, "sans-serif-black").apply {
        maxLines = 4
        ellipsize = TextUtils.TruncateAt.END
        setLineSpacing(0f, 0.95f)
        letterSpacing = -0.01f
    }
    private val meta = text(16f, "sans-serif").apply { alpha = 0.8f }
    private val timer = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000
        progressDrawable = LayerDrawable(arrayOf(
            GradientDrawable().apply { cornerRadius = 2 * d; setColor(0x33FFFFFF) },
            ClipDrawable(GradientDrawable().apply { cornerRadius = 2 * d; setColor(0xE6FFFFFF.toInt()) }, Gravity.START, ClipDrawable.HORIZONTAL),
        )).apply { setId(0, android.R.id.background); setId(1, android.R.id.progress) }
    }
    private val count = text(14f, "sans-serif-medium").apply { alpha = 0.7f }
    private val status = text(18f, "sans-serif-medium").apply { gravity = Gravity.CENTER; alpha = 0.85f }

    private var sections = emptyList<News.Section>()
    private var sec = 0
    private var story = 0
    private var started = 0L
    private var swap = 0

    init {
        setBackgroundColor(Color.BLACK)
        addView(backdrop, LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // Section tabs on a glass capsule, top left.
        val tabsBox = GlassFrame(context, null).apply {
            addView(tabsGlass, 0, LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(tabsScroll, LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            foreground = GradientDrawable().apply { cornerRadius = 40 * d; setColor(0); setStroke(maxOf(1, dp(1)), 0x38FFFFFF) }
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        addView(tabsBox, LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            setMargins(dp(24), dp(24), dp(260), 0)
        })

        // Clock and date, top right.
        val clockBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            addView(TintClock(context, null).apply {
                format12Hour = "EEEE d MMMM"; format24Hour = "EEEE d MMMM"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(Color.WHITE); setShadowLayer(8 * d, 0f, d, 0x66000000); alpha = 0.9f
            })
            addView(TintClock(context, null).apply {
                format12Hour = "h:mm"; format24Hour = "HH:mm"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 56f); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                includeFontPadding = false; letterSpacing = -0.03f
                setTextColor(Color.WHITE); setShadowLayer(10 * d, 0f, d, 0x66000000)
            })
        }
        addView(clockBox, LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(20), dp(24), 0) })

        // The story, on a frosted panel along the bottom left.
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            setPadding(dp(36), dp(30), dp(36), dp(30))
            addView(kicker)
            addView(headline, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) })
            addView(meta, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(12) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(timer, LinearLayout.LayoutParams(dp(200), dp(4)))
                addView(count, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(16) })
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(20) })
        }
        val panel = GlassFrame(context, null).apply {
            addView(panelGlass, 0, LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(body, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            foreground = Glass.rim(32f * d, d)
        }
        addView(panel, LayoutParams((resources.displayMetrics.widthPixels * 0.62f).toInt(), WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
            setMargins(dp(24), 0, 0, dp(24))
        })
        addView(status, LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER))

        loadBackdrop()
        loadNews()
    }

    private fun text(sp: Float, family: String) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        typeface = Typeface.create(family, Typeface.NORMAL)
        setTextColor(Color.WHITE)
        setShadowLayer(6 * d, 0f, d, 0x59000000)
    }

    /** The launcher's last backdrop (or its default wallpaper), with a blurred copy for the glass. */
    private fun loadBackdrop() = io.execute {
        val big = try {
            BitmapFactory.decodeFile(backdropFile(context).path)
        } catch (e: Throwable) {
            null
        } ?: BitmapFactory.decodeResource(resources, R.drawable.wallpaper_dark)
        val small = big?.let { blurred(Bitmap.createScaledBitmap(it, 320, maxOf(1, 320 * it.height / it.width), true), 8) }
        ui.post {
            backdrop.show(big)
            panelGlass.show(small)
            tabsGlass.show(small)
        }
    }

    /** Saved news straight away, then a fresh fetch if it's stale. */
    private fun loadNews() = io.execute {
        val saved = News.load(context)
        ui.post { if (saved.isNotEmpty()) setNews(saved) else status.text = context.getString(R.string.news_loading) }
        val fresh = News.refresh(context, prefs)
        ui.post {
            if (fresh.isNotEmpty()) setNews(fresh)
            else if (sections.isEmpty()) status.text = context.getString(R.string.news_none)
        }
    }

    private fun setNews(list: List<News.Section>) {
        val key = sections.getOrNull(sec)?.key
        sections = list
        sec = list.indexOfFirst { it.key == key }.takeIf { it >= 0 } ?: 0
        story = story.coerceIn(0, maxOf(0, list[sec].stories.size - 1))
        status.text = ""
        buildTabs()
        show(0)
    }

    private fun buildTabs() {
        tabs.removeAllViews()
        sections.forEachIndexed { i, s ->
            tabs.addView(TextView(context).apply {
                text = s.name
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER
                setPadding(dp(16), 0, dp(16), 0)
                tag = i
            }, LinearLayout.LayoutParams(WRAP_CONTENT, dp(32)).apply { if (i > 0) marginStart = dp(4) })
        }
        styleTabs()
    }

    /** The current section's tab is solid white with dark text; the rest sit on the glass. */
    private fun styleTabs() {
        for (i in 0 until tabs.childCount) {
            val t = tabs.getChildAt(i) as TextView
            val on = i == sec
            t.background = GradientDrawable().apply { cornerRadius = 16 * d; setColor(if (on) 0xF2FFFFFF.toInt() else 0) }
            t.setTextColor(if (on) 0xFF0A0A0A.toInt() else 0xD9FFFFFF.toInt())
        }
        tabs.getChildAt(sec)?.let { t -> tabsScroll.post { tabsScroll.smoothScrollTo(maxOf(0, t.left - dp(40)), 0) } }
    }

    /** Shows the current story; [dir] is the way it turned (-1, 1), 0 for no animation. */
    private fun show(dir: Int) {
        val s = sections.getOrNull(sec) ?: return
        val x = s.stories.getOrNull(story) ?: return
        val fill = {
            kicker.text = s.name
            headline.text = x.title
            meta.text = listOf(x.source, News.ago(x.time)).filter { it.isNotEmpty() }.joinToString("  ·  ")
            count.text = "${story + 1} / ${s.stories.size}"
        }
        val parts = listOf(kicker, headline, meta)
        val rest = mapOf<View, Float>(kicker to 0.75f, headline to 1f, meta to 0.8f)
        val token = ++swap
        if (dir == 0) fill() else {
            parts.forEach { it.animate().alpha(0f).translationX(-dir * 24 * d).setStartDelay(0).setDuration(160).setInterpolator(Motion.S).start() }
            ui.postDelayed({
                if (token != swap) return@postDelayed
                fill()
                parts.forEachIndexed { i, v ->
                    v.translationX = dir * 32 * d
                    v.animate().alpha(rest[v]!!).translationX(0f).setStartDelay(i * 40L).setDuration(420).setInterpolator(Motion.S).start()
                }
            }, 170)
        }
        styleTabs()
        started = System.currentTimeMillis()
        ui.removeCallbacks(tick)
        ui.post(tick)
    }

    /** Fills the timer bar, and turns to the next story when it's full. */
    private val tick = object : Runnable {
        override fun run() {
            val f = ((System.currentTimeMillis() - started).toFloat() / STORY_MS).coerceIn(0f, 1f)
            timer.progress = (f * 1000).toInt()
            if (f >= 1f) step(1) else ui.postDelayed(this, 50)
        }
    }

    /** Next or previous story; past a section's end, on into the next (or previous) section. */
    fun step(d: Int) {
        val s = sections.getOrNull(sec) ?: return
        story += d
        if (story >= s.stories.size) { sec = (sec + 1) % sections.size; story = 0 }
        if (story < 0) { sec = (sec - 1 + sections.size) % sections.size; story = sections[sec].stories.size - 1 }
        show(d)
    }

    fun section(d: Int) {
        if (sections.isEmpty()) return
        sec = (sec + d + sections.size) % sections.size
        story = 0
        show(d)
    }

    /** Arrow keys: Left/Right stories, Up/Down sections. True when handled. */
    fun key(e: KeyEvent): Boolean {
        val handled = e.keyCode in setOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)
        if (!handled || e.action != KeyEvent.ACTION_DOWN) return handled
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> step(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> step(1)
            KeyEvent.KEYCODE_DPAD_UP -> section(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> section(1)
        }
        playSoundEffect(when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> android.view.SoundEffectConstants.NAVIGATION_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> android.view.SoundEffectConstants.NAVIGATION_RIGHT
            KeyEvent.KEYCODE_DPAD_UP -> android.view.SoundEffectConstants.NAVIGATION_UP
            else -> android.view.SoundEffectConstants.NAVIGATION_DOWN
        })
        return true
    }

    fun release() {
        ui.removeCallbacksAndMessages(null)
        io.shutdownNow()
    }

    private companion object {
        const val STORY_MS = 12_000L
    }
}

/** The news screen opened from the launcher (the morning briefing, or "Open news" in settings). Back leaves. */
class NewsActivity : Activity() {
    private var view: NewsView? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.setBackgroundDrawable(null)
        view = NewsView(this).also { setContentView(it) }
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean = view?.key(e) == true || super.dispatchKeyEvent(e)

    override fun onDestroy() {
        view?.release()
        super.onDestroy()
    }
}

/**
 * The news as a screensaver. Arrow keys browse like the news screen; OK opens the news screen itself; any other
 * key wakes up to the launcher, as the Gallery screensaver does.
 */
class NewsDream : DreamService() {
    private var view: NewsView? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = true
        isFullscreen = true
        view = NewsView(this).also { setContentView(it) }
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (view?.key(e) == true) return true
        val ok = e.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || e.keyCode == KeyEvent.KEYCODE_ENTER
        if (e.action == KeyEvent.ACTION_UP) {
            startActivity(Intent(this, if (ok) NewsActivity::class.java else MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
        }
        return true
    }

    override fun onDetachedFromWindow() {
        view?.release()
        super.onDetachedFromWindow()
    }
}
