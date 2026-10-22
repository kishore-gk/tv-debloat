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
import android.widget.TextView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Where the launcher leaves its current backdrop, so the news screen and screensaver open on the same picture. */
internal fun backdropFile(c: Context) = File(c.filesDir, "backdrop.jpg")

/**
 * The news screen, in Plume's glass style. A header with the section tabs (on a glass capsule) and the clock, and
 * below it one story filling the rest of the screen on a frosted panel: its picture on the left, and the section,
 * headline, a few sentences of summary, source and time on the right. The story's picture also becomes the
 * drifting backdrop. Stories turn on their own every [STORY_MS] on the S-curve; at a section's end the next
 * section begins.
 *
 * Two places to be, always clearly marked: the story (its panel's rim lights up) or the tabs (the focused tab is
 * solid white). Up goes to the tabs, Down back to the story. In the tabs Left/Right pick a section; on the story
 * they turn stories. The last tab, "+ Topic", adds a topic of your own (in [NewsActivity]).
 *
 * Used by both [NewsActivity] (browsable) and [NewsDream] (the screensaver).
 */
class NewsView(context: Context, private val canType: Boolean) : FrameLayout(context) {
    private val d = resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor() // news lists
    private val net = Executors.newFixedThreadPool(2) // story details and pictures
    private val prefs = context.getSharedPreferences("home", Context.MODE_PRIVATE)

    private val backdrop = Backdrop(context, null)
    private val panelGlass = GlassView(context).apply { tint = 0x8C121216.toInt(); corner = 32f * d }
    private val tabsGlass = GlassView(context).apply { capsule = true }
    private val tabs = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(6), dp(6), dp(6), dp(6))
    }
    private val tabsScroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        isFocusable = false
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS // focus is drawn by us (see zone), not Android's
        addView(tabs)
    }
    private val tabsRim = GradientDrawable().apply { cornerRadius = 40 * d; setColor(0); setStroke(maxOf(1, dp(1)), 0x38FFFFFF) }

    private val art = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val artBox = FrameLayout(context)
    private val kicker = text(13f, "sans-serif-medium").apply { letterSpacing = 0.14f; isAllCaps = true; alpha = 0.75f }
    private val headline = text(32f, "sans-serif-black").apply {
        maxLines = 3
        ellipsize = TextUtils.TruncateAt.END
        setLineSpacing(0f, 0.95f)
        letterSpacing = -0.01f
    }
    private val summary = text(17f, "sans-serif").apply {
        maxLines = 7
        ellipsize = TextUtils.TruncateAt.END
        setLineSpacing(0f, 1.18f)
        alpha = 0.88f
    }
    private val meta = text(15f, "sans-serif-medium").apply { alpha = 0.8f }
    private val timer = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000
        progressDrawable = LayerDrawable(arrayOf(
            GradientDrawable().apply { cornerRadius = 2 * d; setColor(0x33FFFFFF) },
            ClipDrawable(GradientDrawable().apply { cornerRadius = 2 * d; setColor(0xE6FFFFFF.toInt()) }, Gravity.START, ClipDrawable.HORIZONTAL),
        )).apply { setId(0, android.R.id.background); setId(1, android.R.id.progress) }
    }
    private val count = text(14f, "sans-serif-medium").apply { alpha = 0.7f }
    private val status = text(18f, "sans-serif-medium").apply { gravity = Gravity.CENTER; alpha = 0.85f }
    private val panel = GlassFrame(context, null)
    private val panelRim = RimDrawable(32f * d, d, 0x47FFFFFF, 0x14FFFFFF)
    private val panelRimLit = RimDrawable(32f * d, d * 2f, 0xE6FFFFFF.toInt(), 0x66FFFFFF)

    private var sections = emptyList<News.Section>()
    private var sec = 0
    private var story = 0
    private var started = 0L
    private var swap = 0
    private var onTabs = false
    private var tabAt = 0 // focused tab while on the tabs; sections.size is "+ Topic"
    private var paused = false
    private val pics = object : LruCache<String, Pair<Bitmap, Bitmap>>(40 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Pair<Bitmap, Bitmap>) = value.first.byteCount + value.second.byteCount
    }
    private var homeBig: Bitmap? = null
    private var homeSmall: Bitmap? = null

    /** Called when "+ Topic" is chosen (the browsable screen asks for a topic; the screensaver opens that screen). */
    var onAddTopic: (() -> Unit)? = null

    init {
        setBackgroundColor(Color.BLACK)
        addView(backdrop, LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // Header: tabs on a glass capsule (left), date and time (right).
        val tabsBox = GlassFrame(context, null).apply {
            addView(tabsGlass, 0, LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(tabsScroll, LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            foreground = tabsRim
        }
        addView(tabsBox, LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            setMargins(dp(24), dp(24), dp(250), 0)
        })
        val clockBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            addView(TintClock(context, null).apply {
                format12Hour = "EEEE d MMMM"; format24Hour = "EEEE d MMMM"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(Color.WHITE); setShadowLayer(8 * d, 0f, d, 0x66000000); alpha = 0.9f
            })
            addView(TintClock(context, null).apply {
                format12Hour = "h:mm"; format24Hour = "HH:mm"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 40f); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                includeFontPadding = false; letterSpacing = -0.03f
                setTextColor(Color.WHITE); setShadowLayer(10 * d, 0f, d, 0x66000000)
            })
        }
        addView(clockBox, LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(22), dp(24), 0) })

        // The story: one frosted panel filling the rest of the screen. Picture left, words right.
        artBox.apply {
            background = TileArt(null, 20f * d, 0x33FFFFFF, 0x14FFFFFF)
            addView(art, LayoutParams(MATCH_PARENT, MATCH_PARENT))
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, view.width, view.height, 20f * d)
            }
            clipToOutline = true // a round rect: the cheap kind of clip
        }
        val words = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            addView(kicker)
            addView(headline, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
            addView(summary, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(14) })
        }
        val left = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(artBox, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(meta, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(16) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(timer, LinearLayout.LayoutParams(dp(160), dp(4)))
                addView(count, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(14) })
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(12) })
        }
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            setPadding(dp(28), dp(28), dp(32), dp(28))
            addView(left, LinearLayout.LayoutParams(0, MATCH_PARENT, 0.44f))
            addView(words, LinearLayout.LayoutParams(0, MATCH_PARENT, 0.56f).apply { marginStart = dp(32) })
        }
        panel.apply {
            addView(panelGlass, 0, LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(body, LayoutParams(MATCH_PARENT, MATCH_PARENT))
            foreground = panelRimLit
        }
        addView(panel, LayoutParams(MATCH_PARENT, MATCH_PARENT).apply { setMargins(dp(24), dp(104), dp(24), dp(24)) })
        addView(status, LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER))

        loadHomeBackdrop()
        loadNews()
    }

    private fun text(sp: Float, family: String) = TintText(context, null).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        typeface = Typeface.create(family, Typeface.NORMAL)
        setTextColor(Color.WHITE)
        setShadowLayer(6 * d, 0f, d, 0x59000000)
    }

    /** The launcher's last backdrop (or its default wallpaper): shown until a story's own picture arrives. */
    private fun loadHomeBackdrop() = io.execute {
        val big = try {
            BitmapFactory.decodeFile(backdropFile(context).path)
        } catch (e: Throwable) {
            null
        } ?: BitmapFactory.decodeResource(resources, R.drawable.wallpaper_dark)
        val small = big?.let { smallBlur(it) }
        ui.post {
            homeBig = big
            homeSmall = small
            if (backdrop.current == null) showPicture(big, small)
        }
    }

    private fun smallBlur(b: Bitmap) = blurred(Bitmap.createScaledBitmap(b, 320, maxOf(1, 320 * b.height / b.width), true), 8)

    private fun showPicture(big: Bitmap?, small: Bitmap?) {
        backdrop.show(big)
        panelGlass.show(small)
        tabsGlass.show(small)
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
        (sections.map { it.name } + "+  " + context.getString(R.string.news_add_topic)).forEachIndexed { i, name ->
            tabs.addView(TextView(context).apply {
                text = name
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER
                setPadding(dp(16), 0, dp(16), 0)
            }, LinearLayout.LayoutParams(WRAP_CONTENT, dp(34)).apply { if (i > 0) marginStart = dp(4) })
        }
        styleFocus()
    }

    /**
     * Where focus is, drawn plainly. On the tabs: the focused tab is solid white with dark text and a little larger;
     * the current section keeps a glass outline; the capsule's rim lights up and the story panel dims. On the story:
     * its panel's rim lights up and the current section's tab is solid white.
     */
    private fun styleFocus() {
        for (i in 0 until tabs.childCount) {
            val t = tabs.getChildAt(i) as TextView
            val focused = onTabs && i == tabAt
            val current = i == sec
            t.background = GradientDrawable().apply {
                cornerRadius = 17 * d
                when {
                    focused -> setColor(0xFFFFFFFF.toInt())
                    current && !onTabs -> setColor(0xF2FFFFFF.toInt())
                    current -> { setColor(0x26FFFFFF); setStroke(maxOf(1, dp(1)), 0x99FFFFFF.toInt()) }
                    else -> setColor(0)
                }
            }
            t.setTextColor(if (focused || (current && !onTabs)) 0xFF0A0A0A.toInt() else 0xD9FFFFFF.toInt())
            t.animate().scaleX(if (focused) 1.08f else 1f).scaleY(if (focused) 1.08f else 1f)
                .setDuration(Motion.FOCUS_MS).setInterpolator(Motion.S).start()
        }
        tabsRim.setStroke(maxOf(1, dp(if (onTabs) 2 else 1)), if (onTabs) 0xCCFFFFFF.toInt() else 0x38FFFFFF)
        panel.foreground = if (onTabs) panelRim else panelRimLit
        panel.animate().alpha(if (onTabs) 0.72f else 1f).setDuration(Motion.FOCUS_MS).setInterpolator(Motion.S).start()
        val target = tabs.getChildAt(if (onTabs) tabAt else sec)
        target?.let { t -> tabsScroll.post { tabsScroll.smoothScrollTo(maxOf(0, t.left - dp(60)), 0) } }
    }

    /** Shows the current story; [dir] is the way it turned (-1, 1), 0 for no animation. */
    private fun show(dir: Int) {
        val s = sections.getOrNull(sec) ?: return
        val x = s.stories.getOrNull(story) ?: return
        val fill = {
            kicker.text = s.name
            headline.text = x.title
            val det = News.cachedDetail(x)
            summary.text = det?.summary.orEmpty()
            meta.text = listOf(x.source, News.ago(x.time)).filter { it.isNotEmpty() }.joinToString("  ·  ")
            count.text = "${story + 1} / ${s.stories.size}"
            art.setImageBitmap(det?.image?.let { pics.get(it)?.first })
        }
        val parts = listOf(kicker, headline, summary, meta, artBox)
        val rest = mapOf<View, Float>(kicker to 0.75f, headline to 1f, summary to 0.88f, meta to 0.8f, artBox to 1f)
        val token = ++swap
        if (dir == 0) fill() else {
            parts.forEach { it.animate().alpha(0f).translationX(-dir * 24 * d).setStartDelay(0).setDuration(160).setInterpolator(Motion.S).start() }
            ui.postDelayed({
                if (token != swap) return@postDelayed
                fill()
                parts.forEachIndexed { i, v ->
                    v.translationX = dir * 32 * d
                    v.animate().alpha(rest[v]!!).translationX(0f).setStartDelay(i * 35L).setDuration(420).setInterpolator(Motion.S).start()
                }
            }, 170)
        }
        styleFocus()
        loadDetail(x, current = true)
        s.stories.getOrNull(story + 1)?.let { loadDetail(it, current = false) } // ready before it's shown
        started = System.currentTimeMillis()
        ui.removeCallbacks(tick)
        ui.post(tick)
    }

    /** The story's summary and picture: fetched once, then shown if it's still the current story. */
    private fun loadDetail(x: News.Story, current: Boolean) {
        val cached = News.cachedDetail(x)
        if (cached != null && (cached.image == null || pics.get(cached.image) != null)) {
            if (current) applyDetail(x, cached)
            return
        }
        net.execute {
            val det = News.detail(x, prefs)
            val pic = det.image?.let { u -> pics.get(u) ?: decode(u)?.let { b -> (b to smallBlur(b)).also { p -> ui.post { pics.put(u, p) } } } }
            ui.post { if (current || isCurrent(x)) applyDetail(x, det, pic) }
        }
    }

    private fun isCurrent(x: News.Story) = sections.getOrNull(sec)?.stories?.getOrNull(story) === x

    private fun applyDetail(x: News.Story, det: News.Detail, pic: Pair<Bitmap, Bitmap>? = det.image?.let { pics.get(it) }) {
        if (!isCurrent(x)) return
        if (summary.text.isEmpty() && det.summary.isNotEmpty()) {
            summary.text = det.summary
            summary.alpha = 0f
            summary.animate().alpha(0.88f).setDuration(420).setInterpolator(Motion.S).start()
        }
        if (pic != null) {
            if (art.drawable == null) {
                art.alpha = 0f
                art.setImageBitmap(pic.first)
                art.animate().alpha(1f).setDuration(420).setInterpolator(Motion.S).start()
            }
            showPicture(pic.first, pic.second)
        } else if (backdrop.current == null || det.image == null) {
            showPicture(homeBig, homeSmall)
        }
        artBox.visibility = if (pic != null || det.image != null) View.VISIBLE else View.INVISIBLE
    }

    private fun decode(url: String): Bitmap? = try {
        val con = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 7000; readTimeout = 9000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Smart TV)")
        }
        val bytes = con.inputStream.use { it.readBytes() }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val target = resources.displayMetrics.widthPixels
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= target) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?.takeIf { it.width >= 200 } // tiny logos and tracking pixels aren't pictures
    } catch (e: Throwable) {
        null
    }

    /** Fills the timer bar, and turns to the next story when it's full (not while you're in the tabs). */
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
     * The remote. Story: Left/Right turn stories, Up goes to the tabs, OK pauses or resumes. Tabs: Left/Right pick
     * a section (shown at once), Down or OK goes back to the story; OK on "+ Topic" adds one. True when handled.
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

    /** Moving along the tabs shows that section straight away; "+ Topic" just takes focus. */
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
    }
}

/** The news screen opened from the launcher (the morning briefing, or News in settings). Back leaves. */
class NewsActivity : Activity() {
    private var view: NewsView? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.setBackgroundDrawable(null)
        view = NewsView(this, canType = true).also {
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
        view = NewsView(this, canType = false).also {
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
