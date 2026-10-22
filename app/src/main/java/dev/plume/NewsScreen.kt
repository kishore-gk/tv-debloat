package dev.plume

import android.app.Activity
import android.app.AlertDialog
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
import android.text.InputType
import android.text.TextUtils
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.SoundEffectConstants
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextClock
import android.widget.TextView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * The news screen, set like a printed newspaper: ivory serif type on a black page, fine rules between parts, no
 * glass. Top: the date in small capitals and the time, then the sections as a line of small capitals. Below, one
 * story: its picture in a hairline frame on the left; the section and source, a bold serif headline, the summary
 * and the time on the right. Stories turn on their own every [STORY_MS]; at a section's end the next begins.
 *
 * Two places to be, always clear: the story, or the section line. Up goes to the sections (the one you're on is
 * set in reverse, black on ivory), Down back to the story. Whichever you're not in is dimmed. In the sections
 * Left/Right pick one; on the story they turn stories and OK pauses. The last section, "Add a topic", adds your
 * own (in [NewsActivity]).
 *
 * Used by both [NewsActivity] (browsable) and [NewsDream] (the screensaver).
 */
class NewsView(context: Context) : FrameLayout(context) {
    private val d = resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor() // news lists
    private val net = Executors.newFixedThreadPool(2) // story details and pictures
    private val prefs = context.getSharedPreferences("home", Context.MODE_PRIVATE)

    private val serif = Typeface.create("serif", Typeface.NORMAL)
    private val serifBold = Typeface.create("serif", Typeface.BOLD)
    private val serifItalic = Typeface.create("serif", Typeface.ITALIC)

    private val dateLine = text(13f, serif, MUTED).apply { letterSpacing = 0.18f; isAllCaps = true }
    private val clock = TextClock(context).apply {
        format12Hour = "h:mm a"; format24Hour = "HH:mm"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); typeface = serif; setTextColor(MUTED); letterSpacing = 0.12f; isAllCaps = true
    }
    private val tabs = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val tabsScroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        isFocusable = false
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS // focus is drawn by us (see onTabs), not by Android
        addView(tabs)
    }

    private val art = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val artBox = FrameLayout(context).apply {
        setPadding(dp(1), dp(1), dp(1), dp(1))
        background = GradientDrawable().apply { setColor(0xFF161616.toInt()); setStroke(maxOf(1, dp(1)), RULE) }
        addView(art, LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }
    private val kicker = text(13f, serif, MUTED).apply { letterSpacing = 0.18f; isAllCaps = true }
    private val headline = text(36f, serifBold, INK).apply {
        maxLines = 4
        ellipsize = TextUtils.TruncateAt.END
        setLineSpacing(0f, 1.02f)
    }
    private val summary = text(19f, serif, INK).apply {
        maxLines = 8
        ellipsize = TextUtils.TruncateAt.END
        setLineSpacing(0f, 1.32f)
        alpha = 0.86f
    }
    private val byline = text(16f, serifItalic, MUTED)
    private val count = text(13f, serif, MUTED).apply { letterSpacing = 0.12f }
    private val timer = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000
        progressDrawable = LayerDrawable(arrayOf(
            GradientDrawable().apply { setColor(RULE) },
            ClipDrawable(GradientDrawable().apply { setColor(INK) }, Gravity.START, ClipDrawable.HORIZONTAL),
        )).apply { setId(0, android.R.id.background); setId(1, android.R.id.progress) }
    }
    private val status = text(20f, serifItalic, MUTED).apply { gravity = Gravity.CENTER }
    private val head = LinearLayout(context)
    private val page = LinearLayout(context)

    private var sections = emptyList<News.Section>()
    private var sec = 0
    private var story = 0
    private var started = 0L
    private var swap = 0
    private var onTabs = false
    private var tabAt = 0 // focused section while on the section line; sections.size is "Add a topic"
    private var paused = false
    private val pics = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** Called when "Add a topic" is chosen (the news screen asks for it; the screensaver opens that screen). */
    var onAddTopic: (() -> Unit)? = null

    init {
        setBackgroundColor(PAPER)
        setPadding(dp(44), dp(30), dp(44), dp(28))

        // Masthead: date left, time right, a rule, the section line, a double rule.
        val dateRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(dateLine, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(clock)
        }
        head.apply {
            orientation = LinearLayout.VERTICAL
            addView(dateRow)
            addView(rule(1), LinearLayout.LayoutParams(MATCH_PARENT, maxOf(1, dp(1))).apply { topMargin = dp(10) })
            addView(tabsScroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8); bottomMargin = dp(8) })
            addView(rule(1), LinearLayout.LayoutParams(MATCH_PARENT, maxOf(1, dp(1))))
            addView(rule(1), LinearLayout.LayoutParams(MATCH_PARENT, maxOf(1, dp(1))).apply { topMargin = dp(3) })
        }

        // The story: picture left, words right, a column rule between.
        val left = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(artBox, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(timer, LinearLayout.LayoutParams(0, maxOf(1, dp(1)), 1f))
                addView(count, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(14) })
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(14) })
        }
        val words = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(kicker)
            addView(headline, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) })
            addView(rule(0), LinearLayout.LayoutParams(dp(56), maxOf(1, dp(1))).apply { topMargin = dp(16) })
            addView(summary, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(16) })
            addView(byline, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(16) })
        }
        page.apply {
            orientation = LinearLayout.HORIZONTAL
            addView(left, LinearLayout.LayoutParams(0, MATCH_PARENT, 0.42f))
            addView(rule(1), LinearLayout.LayoutParams(maxOf(1, dp(1)), MATCH_PARENT).apply { marginStart = dp(28); marginEnd = dp(28) })
            addView(words, LinearLayout.LayoutParams(0, MATCH_PARENT, 0.58f))
        }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(head)
            addView(page, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = dp(22) })
        }, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addView(status, LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER))

        dateLine.text = java.text.SimpleDateFormat("EEEE, d MMMM yyyy", java.util.Locale.getDefault()).format(java.util.Date())
        loadNews()
    }

    private fun text(sp: Float, face: Typeface, color: Int) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        typeface = face
        setTextColor(color)
        includeFontPadding = false
    }

    /** A hairline in the rule colour ([weight] 0 for the short ivory rule under the headline). */
    private fun rule(weight: Int) = View(context).apply { setBackgroundColor(if (weight == 0) INK else RULE) }

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

    /** After a topic was added: fetch at once and jump to it. */
    fun reload(jumpTo: String? = null) = io.execute {
        val fresh = News.refresh(context, prefs, force = true)
        ui.post {
            if (fresh.isNotEmpty()) setNews(fresh)
            jumpTo?.let { key -> sections.indexOfFirst { it.key == key }.takeIf { it >= 0 }?.let { sec = it; story = 0; onTabs = false; show(0) } }
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
        val names = sections.map { it.name } + context.getString(R.string.news_add_topic)
        names.forEachIndexed { i, name ->
            tabs.addView(TextView(context).apply {
                text = name
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                typeface = serif
                letterSpacing = 0.14f
                isAllCaps = true
                gravity = Gravity.CENTER
                includeFontPadding = false
                setPadding(dp(12), dp(7), dp(12), dp(7))
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { if (i > 0) marginStart = dp(10) })
        }
        styleFocus()
    }

    /**
     * Where focus is. On the section line: the focused section is set in reverse (black on ivory) and the story
     * dims. On the story: the current section is ivory and underlined, the others muted, and the line dims a little.
     */
    private fun styleFocus() {
        for (i in 0 until tabs.childCount) {
            val t = tabs.getChildAt(i) as TextView
            val focused = onTabs && i == tabAt
            val current = i == sec
            t.background = when {
                focused -> GradientDrawable().apply { setColor(INK) }
                current -> UnderlineDrawable(INK, maxOf(1, dp(2)))
                else -> null
            }
            t.setTextColor(when {
                focused -> PAPER
                current -> INK
                else -> MUTED
            })
            t.paint.isFakeBoldText = current || focused
            t.invalidate()
        }
        tabsScroll.animate().alpha(if (onTabs) 1f else 0.85f).setDuration(Motion.FOCUS_MS).setInterpolator(Motion.S).start()
        page.animate().alpha(if (onTabs) 0.4f else 1f).setDuration(Motion.FOCUS_MS).setInterpolator(Motion.S).start()
        tabs.getChildAt(if (onTabs) tabAt else sec)?.let { t -> tabsScroll.post { tabsScroll.smoothScrollTo(maxOf(0, t.left - dp(60)), 0) } }
    }

    /** Shows the current story; [dir] is the way it turned (-1, 1), 0 for no animation. */
    private fun show(dir: Int) {
        val s = sections.getOrNull(sec) ?: return
        val x = s.stories.getOrNull(story) ?: return
        val fill = {
            kicker.text = listOf(s.name, x.source).filter { it.isNotEmpty() }.joinToString("   ·   ")
            headline.text = x.title
            val det = News.cachedDetail(x)
            summary.text = det?.summary.orEmpty()
            byline.text = News.ago(x.time).let { if (it.isEmpty()) "" else context.getString(R.string.news_published, it) }
            count.text = "${story + 1} of ${s.stories.size}"
            val pic = det?.image?.let { pics.get(it) }
            art.setImageBitmap(pic)
            artBox.visibility = if (det != null && det.image == null) View.INVISIBLE else View.VISIBLE
        }
        val parts = listOf(kicker, headline, summary, byline, artBox)
        val rest = mapOf<View, Float>(kicker to 1f, headline to 1f, summary to 0.86f, byline to 1f, artBox to 1f)
        val token = ++swap
        if (dir == 0) fill() else {
            parts.forEach { it.animate().alpha(0f).setStartDelay(0).setDuration(180).setInterpolator(Motion.S).start() }
            ui.postDelayed({
                if (token != swap) return@postDelayed
                fill()
                parts.forEachIndexed { i, v ->
                    v.animate().alpha(rest[v]!!).setStartDelay(i * 40L).setDuration(380).setInterpolator(Motion.S).start()
                }
            }, 190)
        }
        styleFocus()
        loadDetail(x)
        s.stories.getOrNull(story + 1)?.let { loadDetail(it) } // ready before it's shown
        started = System.currentTimeMillis()
        ui.removeCallbacks(tick)
        ui.post(tick)
    }

    /** The story's summary and picture: fetched once, then shown if it's still the current story. */
    private fun loadDetail(x: News.Story) {
        val cached = News.cachedDetail(x)
        if (cached != null && (cached.image == null || pics.get(cached.image) != null)) {
            if (isCurrent(x)) applyDetail(cached)
            return
        }
        net.execute {
            val det = News.detail(x, prefs)
            val pic = det.image?.let { u -> pics.get(u) ?: decode(u)?.also { pics.put(u, it) } }
            ui.post { if (isCurrent(x)) applyDetail(det, pic) }
        }
    }

    private fun isCurrent(x: News.Story) = sections.getOrNull(sec)?.stories?.getOrNull(story) === x

    private fun applyDetail(det: News.Detail, pic: Bitmap? = det.image?.let { pics.get(it) }) {
        if (summary.text.isEmpty() && det.summary.isNotEmpty()) {
            summary.text = det.summary
            summary.alpha = 0f
            summary.animate().alpha(0.86f).setDuration(420).setInterpolator(Motion.S).start()
        }
        if (pic != null && art.drawable == null) {
            art.alpha = 0f
            art.setImageBitmap(pic)
            art.animate().alpha(1f).setDuration(420).setInterpolator(Motion.S).start()
        }
        artBox.visibility = if (det.image == null) View.INVISIBLE else View.VISIBLE
    }

    private fun decode(url: String): Bitmap? = try {
        val con = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 7000; readTimeout = 9000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Smart TV)")
        }
        val bytes = con.inputStream.use { it.readBytes() }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val target = resources.displayMetrics.widthPixels / 2 // the picture takes under half the screen
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= target) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?.takeIf { it.width >= 200 } // tiny logos and tracking pixels aren't pictures
    } catch (e: Throwable) {
        null
    }

    /** Fills the timer line, and turns to the next story when it's full (not while you're in the sections or paused). */
    private val tick = object : Runnable {
        override fun run() {
            if (onTabs || paused) {
                started = System.currentTimeMillis() - (timer.progress * STORY_MS / 1000)
                ui.postDelayed(this, 100)
                return
            }
            val f = ((System.currentTimeMillis() - started).toFloat() / STORY_MS).coerceIn(0f, 1f)
            timer.progress = (f * 1000).toInt()
            if (f >= 1f) step(1) else ui.postDelayed(this, 50)
        }
    }

    /** Next or previous story; past a section's end, on into the next (or previous) section. */
    private fun step(dir: Int) {
        val s = sections.getOrNull(sec) ?: return
        story += dir
        if (story >= s.stories.size) { sec = (sec + 1) % sections.size; story = 0 }
        if (story < 0) { sec = (sec - 1 + sections.size) % sections.size; story = sections[sec].stories.size - 1 }
        show(dir)
    }

    /**
     * The remote. Story: Left/Right turn stories, Up goes to the sections, OK pauses or resumes. Sections:
     * Left/Right pick one (shown at once), Down or OK goes back to the story; OK on "Add a topic" adds one.
     * True when handled.
     */
    fun key(e: KeyEvent): Boolean {
        val arrows = setOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)
        val ok = e.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || e.keyCode == KeyEvent.KEYCODE_ENTER
        if (e.keyCode !in arrows && !ok) return false
        if (e.action != KeyEvent.ACTION_DOWN || sections.isEmpty()) return true
        var moved = true
        if (onTabs) when {
            e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> if (tabAt > 0) pickTab(tabAt - 1) else moved = false
            e.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> if (tabAt < sections.size) pickTab(tabAt + 1) else moved = false
            e.keyCode == KeyEvent.KEYCODE_DPAD_UP -> moved = false
            ok && tabAt == sections.size -> { onAddTopic?.invoke(); moved = false }
            else -> { onTabs = false; styleFocus() } // Down, or OK on a section
        } else when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> step(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> step(1)
            KeyEvent.KEYCODE_DPAD_UP -> { onTabs = true; tabAt = sec; styleFocus() }
            KeyEvent.KEYCODE_DPAD_DOWN -> moved = false
            else -> { paused = !paused; moved = false; playSoundEffect(SoundEffectConstants.CLICK) }
        }
        if (moved) playSoundEffect(when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> SoundEffectConstants.NAVIGATION_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> SoundEffectConstants.NAVIGATION_RIGHT
            KeyEvent.KEYCODE_DPAD_UP -> SoundEffectConstants.NAVIGATION_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> SoundEffectConstants.NAVIGATION_DOWN
            else -> SoundEffectConstants.CLICK
        })
        return true
    }

    /** Moving along the sections shows that section straight away; "Add a topic" just takes focus. */
    private fun pickTab(i: Int) {
        tabAt = i
        if (i < sections.size && i != sec) {
            val dir = if (i > sec) 1 else -1
            sec = i
            story = 0
            show(dir)
        } else styleFocus()
    }

    fun release() {
        ui.removeCallbacksAndMessages(null)
        io.shutdownNow()
        net.shutdownNow()
    }

    private companion object {
        const val STORY_MS = 15_000L
        const val PAPER = 0xFF0A0A0A.toInt() // the page: near-black
        const val INK = 0xFFEFE9DC.toInt() // ivory, like newsprint ink reversed
        const val MUTED = 0xFFA39D92.toInt() // dates, sections, bylines
        const val RULE = 0xFF3A3834.toInt() // hairline rules
    }
}

/** A line under a section name, the way a newspaper marks the page you're on. */
private class UnderlineDrawable(private val color: Int, private val h: Int) : android.graphics.drawable.Drawable() {
    private val p = android.graphics.Paint().apply { this.color = color }
    override fun draw(c: android.graphics.Canvas) {
        val b = bounds
        c.drawRect(b.left + b.width() * 0.12f, (b.bottom - h).toFloat(), b.right - b.width() * 0.12f, b.bottom.toFloat(), p)
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

/** The news screen opened from the launcher (the morning briefing, or News in settings). Back leaves. */
class NewsActivity : Activity() {
    private var view: NewsView? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.setBackgroundDrawable(null)
        view = NewsView(this).also {
            it.onAddTopic = { askTopic() }
            setContentView(it)
        }
        if (intent.getBooleanExtra(EXTRA_ADD, false)) view?.post { askTopic() }
    }

    /** A topic of your own: anything Google News can search ("Formula 1", "ISRO", "Bengaluru metro"). */
    private fun askTopic() {
        val field = EditText(this).apply {
            hint = getString(R.string.news_topic_hint)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        val box = FrameLayout(this).apply {
            val p = (24 * resources.displayMetrics.density).toInt()
            setPadding(p, p / 2, p, 0)
            addView(field)
        }
        val add = { term: String ->
            if (term.isNotBlank()) {
                News.addCustom(getSharedPreferences("home", MODE_PRIVATE), term)
                view?.reload("q:${term.trim()}")
            }
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.news_add_topic)
            .setView(box)
            .setPositiveButton(R.string.news_add) { _, _ -> add(field.text.toString()) }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        field.setOnEditorActionListener { v, _, _ -> add(v.text.toString()); dialog.dismiss(); true }
        dialog.show()
        field.requestFocus()
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean = view?.key(e) == true || super.dispatchKeyEvent(e)

    override fun onDestroy() {
        view?.release()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ADD = "add_topic"
    }
}

/**
 * The news as a screensaver. Browses like the news screen; Back or Home wakes up to the launcher. "+ Topic" opens
 * the news screen to type it (a screensaver can't show a keyboard).
 */
class NewsDream : DreamService() {
    private var view: NewsView? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = true
        isFullscreen = true
        view = NewsView(this).also {
            it.onAddTopic = {
                startActivity(Intent(this, NewsActivity::class.java).putExtra(NewsActivity.EXTRA_ADD, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                finish()
            }
            setContentView(it)
        }
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (view?.key(e) == true) return true
        if (e.action == KeyEvent.ACTION_UP) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
        }
        return true
    }

    override fun onDetachedFromWindow() {
        view?.release()
        super.onDetachedFromWindow()
    }
}
