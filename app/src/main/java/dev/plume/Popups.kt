package dev.plume

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.format.DateFormat
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Content of a popup card (the calendar, the weather): it gets the remote's keys while it's open. */
internal interface PopupCard {
    /** True when handled. Back is handled by the popup itself (it closes). */
    fun key(e: KeyEvent): Boolean
}

/** Shared drawing bits: dp/sp, the type, the three text colours. */
private class Ink(c: Context) {
    val u = c.resources.displayMetrics.density
    private val sp = c.resources.displayMetrics.scaledDensity
    val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val regular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val light: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)

    fun paint(size: Float, face: Typeface = regular, color: Int = WHITE, align: Paint.Align = Paint.Align.LEFT) =
        TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            textSize = size * sp; typeface = face; this.color = color; textAlign = align
        }

    companion object {
        const val WHITE = 0xFFF5F5F7.toInt()
        const val MUTED = 0xA6F5F5F7.toInt()
        const val FAINT = 0x59F5F5F7
        const val AMBER = 0xFFFFC266.toInt()
        const val RAIN = 0xFF8FD0FF.toInt()
    }
}

private fun tween(ms: Long, update: (Float) -> Unit): ValueAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
    duration = ms
    interpolator = Motion.S
    addUpdateListener { update(it.animatedValue as Float) }
    start()
}

// ======================================================================================================
// Calendar
// ======================================================================================================

/**
 * A month at a glance: the grid on the left, the chosen day and this month's holidays on the right. Arrows move
 * a day or a week (crossing into the next or last month as you go), channel or page keys jump a month, OK comes
 * back to today. The highlight glides between days; the weeks scroll into the next month, carrying it along.
 */
internal class CalendarCard(c: Context, private val holidays: (year: Int, month: Int) -> List<Holiday>) : View(c), PopupCard {
    private val ink = Ink(c)
    private val u = ink.u
    private val today = LocalDate.now()
    private var sel = today
    private var shown = YearMonth.from(today)
    private var prev: YearMonth? = null // the month scrolling away
    private var monthK = 1f // 0..1 from [prev] to [shown]: titles cross-fade, in-month days brighten
    private var monthDir = 0
    private var scroll = 0f // rows the grid is still shifted by; eases to 0 (the weeks scroll up or down)
    private var hx = -1f // the highlight, in cells of [shown]'s grid
    private var hy = -1f
    private var moveAnim: ValueAnimator? = null
    private var prevSel: LocalDate? = null // the day panel cross-fades from this
    private var panelK = 1f
    private var panelAnim: ValueAnimator? = null
    private val firstDow = WeekFields.of(Locale.getDefault()).firstDayOfWeek
    private val months = HashMap<YearMonth, List<Holiday>>()

    private val title = ink.paint(30f, ink.medium)
    private val titleYear = ink.paint(30f, ink.light, Ink.MUTED)
    private val weekday = ink.paint(11f, ink.medium, Ink.MUTED, Paint.Align.CENTER).apply { letterSpacing = 0.12f }
    private val num = ink.paint(19f, ink.regular, Ink.WHITE, Paint.Align.CENTER)
    private val numToday = ink.paint(19f, ink.medium, 0xFF101014.toInt(), Paint.Align.CENTER)
    private val dayName = ink.paint(14f, ink.regular, Ink.MUTED)
    private val dateBig = ink.paint(26f, ink.medium)
    private val rel = ink.paint(14f, ink.regular, Ink.MUTED)
    private val hName = ink.paint(17f, ink.medium)
    private val hType = ink.paint(12f, ink.regular, Ink.MUTED)
    private val label = ink.paint(11f, ink.medium, Ink.MUTED).apply { letterSpacing = 0.12f }
    private val rowNum = ink.paint(14f, ink.medium, Ink.MUTED, Paint.Align.RIGHT)
    private val rowName = ink.paint(14f, ink.regular, Ink.MUTED)
    private val hint = ink.paint(11f, ink.regular, Ink.FAINT, Paint.Align.RIGHT)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * u; color = 0x40FFFFFF }
    private val r = RectF()

    private val fmtWeekday = DateTimeFormatter.ofPattern("EEEE", Locale.getDefault())
    private val fmtDate = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.getDefault())
    private val fmtMonth = DateTimeFormatter.ofPattern("MMMM", Locale.getDefault())
    private val fmtShortDay = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())

    /** The holiday file arrived (or changed): read the months again. */
    fun reload() { months.clear(); invalidate() }

    private fun hols(ym: YearMonth) = months.getOrPut(ym) { holidays(ym.year, ym.monthValue) }

    private fun gridStart(ym: YearMonth): LocalDate {
        val first = ym.atDay(1)
        return first.minusDays(((first.dayOfWeek.value - firstDow.value + 7) % 7).toLong())
    }

    private fun cell(d: LocalDate, ym: YearMonth): Int = ChronoUnit.DAYS.between(gridStart(ym), d).toInt()

    override fun key(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return e.keyCode in KEYS
        val next = when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> sel.minusDays(1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> sel.plusDays(1)
            KeyEvent.KEYCODE_DPAD_UP -> sel.minusWeeks(1)
            KeyEvent.KEYCODE_DPAD_DOWN -> sel.plusWeeks(1)
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_MEDIA_REWIND -> sel.minusMonths(1)
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> sel.plusMonths(1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> today
            else -> return false
        }
        if (next == sel) { Sounds.edge(); return true }
        if (e.keyCode in OKS) Sounds.select() else Sounds.move()
        go(next)
        return true
    }

    /**
     * The grid is one long run of weeks: crossing into another month scrolls it (up when going forward, down when
     * going back) and the highlight rides along with the day it was on, gliding to the new one as the weeks settle.
     */
    private fun go(next: LocalDate) {
        val ym = YearMonth.from(next)
        val from = sel
        sel = next
        if (from != next) {
            prevSel = from
            panelAnim?.cancel()
            panelK = 0f
            panelAnim = tween(260) { panelK = it; invalidate() }
        }
        var s0 = scroll; var hx0 = hx; var hy0 = hy; var m0 = monthK
        if (ym != shown) {
            val rows = (ChronoUnit.DAYS.between(gridStart(shown), gridStart(ym)) / 7).toFloat()
            val screenY = hy + scroll // where the highlight is on screen now
            s0 = (scroll + rows).coerceIn(-7f, 7f)
            hy0 = screenY - s0
            monthDir = if (ym.isAfter(shown)) 1 else -1
            prev = shown
            shown = ym
            m0 = 0f
        }
        val i = cell(next, shown)
        val hx1 = (i % 7).toFloat(); val hy1 = (i / 7).toFloat()
        moveAnim?.cancel()
        moveAnim = tween(Motion.FOCUS_MS + if (m0 < 1f) 100 else 0) { k ->
            scroll = s0 * (1f - k)
            hx = hx0 + (hx1 - hx0) * k
            hy = hy0 + (hy1 - hy0) * k
            monthK = m0 + (1f - m0) * k
            invalidate()
        }
    }

    override fun onDraw(c: Canvas) {
        if (hx < 0f) cell(sel, shown).let { hx = (it % 7).toFloat(); hy = (it / 7).toFloat() }
        val w = width.toFloat(); val h = height.toFloat()
        val pad = 40f * u
        val split = w * 0.6f
        val gridL = pad; val gridR = split - 24f * u
        val cw = (gridR - gridL) / 7f
        val top = pad + 84f * u
        val ch = (h - pad - top) / 6f

        // the month's name: the old one lifts away as the new one settles in
        val dy = 10f * u
        prev?.takeIf { monthK < 1f }?.let { drawTitle(c, it, 1f - monthK, -monthDir * dy * monthK, gridL, pad) }
        drawTitle(c, shown, if (prev == null) 1f else monthK, monthDir * dy * (1f - monthK), gridL, pad)

        // weekday names stay put while the weeks scroll
        for (col in 0 until 7) {
            val d = firstDow.plus(col.toLong())
            c.drawText(fmtShortDay.format(LocalDate.of(2024, 1, 1).with(java.time.temporal.TemporalAdjusters.nextOrSame(d))).uppercase(),
                gridL + cw * (col + 0.5f), pad + 68f * u, weekday)
        }
        drawGrid(c, gridL, gridR, top, cw, ch)

        // the divider and the day panel (cross-fading to the new day)
        fill.color = 0x1FFFFFFF
        c.drawRect(split, pad, split + 1f * u, h - pad, fill)
        val px = split + 32f * u
        prevSel?.takeIf { panelK < 1f }?.let { old ->
            val a = (1f - 2f * panelK).coerceAtLeast(0f)
            if (a > 0f) { val sv = c.saveLayerAlpha(px - 8f * u, 0f, w, h, (a * 255).roundToInt()); drawDay(c, old, px, w - pad, pad, h - pad); c.restoreToCount(sv) }
        }
        if (panelK < 1f) {
            val sv = c.saveLayerAlpha(px - 8f * u, 0f, w, h, (panelK * 255).roundToInt()); drawDay(c, sel, px, w - pad, pad, h - pad); c.restoreToCount(sv)
        } else drawDay(c, sel, px, w - pad, pad, h - pad)
        c.drawText(context.getString(R.string.cal_hint), w - pad, h - pad + 14f * u, hint)
    }

    private fun drawTitle(c: Canvas, ym: YearMonth, alpha: Float, dy: Float, x: Float, pad: Float) {
        val a = (alpha * 255).roundToInt()
        if (a <= 0) return
        val month = fmtMonth.format(ym).replaceFirstChar { it.titlecase(Locale.getDefault()) }
        title.alpha = a; titleYear.alpha = (a * 0.65f).toInt()
        c.drawText(month, x, pad + 30f * u + dy, title)
        c.drawText("  ${ym.year}", x + title.measureText(month), pad + 30f * u + dy, titleYear)
    }

    /** Days in the shown month bright, others dim; while months change, that eases from the old month to the new. */
    private fun inMonth(d: LocalDate): Float {
        val now = if (YearMonth.from(d) == shown) 1f else 0.28f
        val before = if (prev != null && YearMonth.from(d) == prev) 1f else 0.28f
        return if (prev == null) now else before + (now - before) * monthK
    }

    private fun drawGrid(c: Canvas, gridL: Float, gridR: Float, top: Float, cw: Float, ch: Float) {
        c.save()
        c.clipRect(gridL - 12f * u, top, gridR + 12f * u, top + ch * 6f)
        // highlight under the chosen day, moving with the weeks
        run {
            val cx = gridL + cw * (hx + 0.5f); val cy = top + ch * (hy + scroll + 0.5f)
            val hw = min(cw, ch * 1.25f) / 2f - 3f * u; val hh = ch / 2f - 3f * u
            r.set(cx - hw, cy - hh, cx + hw, cy + hh)
            fill.color = 0x2EFFFFFF
            stroke.alpha = 0x40
            c.drawRoundRect(r, 14f * u, 14f * u, fill)
            c.drawRoundRect(r, 14f * u, 14f * u, stroke)
        }
        val start = gridStart(shown)
        val first = kotlin.math.floor(-scroll).toInt() - 1
        val last = kotlin.math.ceil(6f - scroll).toInt()
        for (row in first..last) {
            val y = row + scroll // rows on screen, 0..6
            val vis = min(y + 1f, 6f - y).coerceIn(0f, 1f) // weeks fade as they slide past the edges
            if (vis <= 0f) continue
            for (col in 0 until 7) {
                val d = start.plusDays((row * 7 + col).toLong())
                val cx = gridL + cw * (col + 0.5f); val cy = top + ch * (y + 0.5f)
                val k = inMonth(d) * vis
                val dayHols = hols(YearMonth.from(d)).filter { it.date == d }
                val public = dayHols.any { it.public }
                val base = cy + 7f * u
                if (d == today) {
                    fill.color = Ink.WHITE; fill.alpha = (255 * k).toInt()
                    c.drawCircle(cx, cy, min(cw, ch) * 0.34f, fill)
                    numToday.alpha = (255 * k).toInt()
                    c.drawText(d.dayOfMonth.toString(), cx, base, numToday)
                } else {
                    num.color = if (public) Ink.AMBER else Ink.WHITE
                    num.alpha = (255 * k).toInt()
                    c.drawText(d.dayOfMonth.toString(), cx, base, num)
                }
                if (dayHols.isNotEmpty()) { // a dot under the number: amber for a day off, white for a festival
                    fill.color = if (public) Ink.AMBER else Ink.WHITE
                    fill.alpha = (255 * k * (if (public) 1f else 0.7f)).toInt()
                    c.drawCircle(cx, cy + min(cw, ch) * 0.34f + 5f * u, 2.5f * u, fill)
                }
            }
        }
        c.restore()
    }

    private fun drawDay(c: Canvas, sel: LocalDate, x: Float, right: Float, top: Float, bottom: Float) {
        val w = right - x
        var y = top + 16f * u
        c.drawText(fmtWeekday.format(sel).replaceFirstChar { it.titlecase(Locale.getDefault()) }, x, y, dayName)
        y += 34f * u
        c.drawText(TextUtils.ellipsize(fmtDate.format(sel), dateBig, w, TextUtils.TruncateAt.END).toString(), x, y, dateBig)
        y += 24f * u
        val days = ChronoUnit.DAYS.between(today, sel)
        val res = context.resources
        val relText = when {
            days == 0L -> res.getString(R.string.cal_today)
            days == 1L -> res.getString(R.string.cal_tomorrow)
            days == -1L -> res.getString(R.string.cal_yesterday)
            days > 0 -> res.getQuantityString(R.plurals.cal_in_days, days.toInt(), days.toInt())
            else -> res.getQuantityString(R.plurals.cal_days_ago, (-days).toInt(), (-days).toInt())
        }
        c.drawText(relText, x, y, rel)
        y += 28f * u

        val month = hols(YearMonth.from(sel))
        for (hol in month.filter { it.date == sel }) {
            fill.color = if (hol.public) Ink.AMBER else Ink.WHITE
            c.drawCircle(x + 4f * u, y + 9f * u, 4f * u, fill)
            val layout = StaticLayout.Builder.obtain(hol.name, 0, hol.name.length, hName, (w - 18f * u).toInt())
                .setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
            c.save(); c.translate(x + 18f * u, y); layout.draw(c); c.restore()
            y += layout.height + 16f * u
            c.drawText(res.getString(if (hol.public) R.string.cal_holiday else R.string.cal_festival), x + 18f * u, y - 2f * u, hType)
            y += 16f * u
        }

        // this month's list
        if (month.isNotEmpty()) {
            y = max(y + 12f * u, top + (bottom - top) * 0.46f)
            c.drawText(res.getString(R.string.cal_this_month).uppercase(), x, y, label)
            y += 26f * u
            val rowH = 24f * u
            val room = ((bottom - 22f * u - y) / rowH).toInt() + 1
            // keep the chosen day's row in view
            val at = month.indexOfFirst { !it.date.isBefore(sel) }.let { if (it < 0) month.size - 1 else it }
            val first = (at - room / 2).coerceIn(0, max(0, month.size - room))
            for ((n, hol) in month.drop(first).take(room).withIndex()) {
                val on = hol.date == sel
                rowNum.color = if (hol.public) Ink.AMBER else Ink.MUTED
                if (on && !hol.public) rowNum.color = Ink.WHITE
                rowName.color = if (on) Ink.WHITE else Ink.MUTED
                rowName.typeface = if (on) ink.medium else ink.regular
                val by = y + n * rowH
                c.drawText(hol.date.dayOfMonth.toString(), x + 20f * u, by, rowNum)
                c.drawText(TextUtils.ellipsize(hol.name, rowName, w - 32f * u, TextUtils.TruncateAt.END).toString(), x + 32f * u, by, rowName)
            }
        }
    }

    companion object {
        private val OKS = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)
        private val KEYS = OKS + setOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_UP,
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
    }
}

// ======================================================================================================
// Weather
// ======================================================================================================

/** Open-Meteo's 7-day forecast, as the weather card needs it. Temperatures in °C, wind in km/h. */
internal class Forecast(val now: Now?, val days: List<Day>, val hours: List<Hour>) {
    class Now(val temp: Double, val feels: Double, val humidity: Int, val code: Int)
    class Day(
        val date: LocalDate, val code: Int, val max: Double, val min: Double, val rainProb: Int, val rainMm: Double,
        val wind: Double, val uv: Double, val sunrise: LocalDateTime?, val sunset: LocalDateTime?,
    )
    class Hour(val time: LocalDateTime, val temp: Double, val prob: Int)

    companion object {
        fun url(lat: Float, lon: Float) = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code" +
            "&hourly=temperature_2m,precipitation_probability" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,precipitation_sum," +
            "wind_speed_10m_max,uv_index_max,sunrise,sunset&timezone=auto&forecast_days=7"

        private fun time(s: String?) = s?.takeIf { it.isNotEmpty() && it != "null" }?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }

        fun parse(j: JSONObject): Forecast {
            val now = j.optJSONObject("current")?.let {
                Now(it.optDouble("temperature_2m"), it.optDouble("apparent_temperature"), it.optInt("relative_humidity_2m"), it.optInt("weather_code"))
            }
            val d = j.getJSONObject("daily")
            val dates = d.getJSONArray("time")
            val days = (0 until dates.length()).map { i ->
                Day(
                    LocalDate.parse(dates.getString(i)), d.getJSONArray("weather_code").optInt(i),
                    d.getJSONArray("temperature_2m_max").optDouble(i), d.getJSONArray("temperature_2m_min").optDouble(i),
                    d.optJSONArray("precipitation_probability_max")?.optInt(i, 0) ?: 0,
                    d.optJSONArray("precipitation_sum")?.optDouble(i, 0.0) ?: 0.0,
                    d.optJSONArray("wind_speed_10m_max")?.optDouble(i, 0.0) ?: 0.0,
                    d.optJSONArray("uv_index_max")?.optDouble(i, 0.0) ?: 0.0,
                    time(d.optJSONArray("sunrise")?.optString(i)), time(d.optJSONArray("sunset")?.optString(i)),
                )
            }
            val hj = j.getJSONObject("hourly")
            val ht = hj.getJSONArray("time")
            val hours = (0 until ht.length()).mapNotNull { i ->
                val t = time(ht.getString(i)) ?: return@mapNotNull null
                Hour(t, hj.getJSONArray("temperature_2m").optDouble(i), hj.optJSONArray("precipitation_probability")?.optInt(i, 0) ?: 0)
            }
            return Forecast(now, days, hours)
        }
    }
}

/**
 * The week ahead: a strip of seven days along the top (Left/Right picks one, the highlight glides), the chosen
 * day below it (conditions, high and low, the day's temperature through the hours with the chance of rain, wind,
 * UV, sunrise and sunset), and page dots at the bottom.
 */
internal class WeatherCard(
    c: Context, private val fahrenheit: Boolean, private val city: String?, private val word: (Int) -> String,
) : FrameLayout(c), PopupCard {
    private val ink = Ink(c)
    private val u = ink.u
    private var fc: Forecast? = null
    private var failed = false
    private var day = 0
    private var prevDay = 0
    private var hx = 0f
    private var k = 1f
    private var dir = 0
    private var moveAnim: ValueAnimator? = null
    private var fadeAnim: ValueAnimator? = null
    private val dots = PageDots(c).apply { max = 7 }
    private val today = LocalDate.now()
    private val h24 = DateFormat.is24HourFormat(c)

    private val cityP = ink.paint(13f, ink.medium, Ink.MUTED).apply { letterSpacing = 0.1f }
    private val center = ink.paint(16f, ink.regular, Ink.MUTED, Paint.Align.CENTER)
    private val colLabel = ink.paint(13f, ink.medium, Ink.MUTED, Paint.Align.CENTER)
    private val hiP = ink.paint(15f, ink.medium)
    private val loP = ink.paint(14f, ink.regular, Ink.MUTED)
    private val dayTitle = ink.paint(14f, ink.regular, Ink.MUTED)
    private val cond = ink.paint(22f, ink.medium)
    private val bigHi = ink.paint(46f, ink.light)
    private val bigLo = ink.paint(30f, ink.light, Ink.MUTED)
    private val chartTemp = ink.paint(12f, ink.medium, Ink.WHITE, Paint.Align.CENTER)
    private val chartTime = ink.paint(11f, ink.regular, Ink.MUTED, Paint.Align.CENTER)
    private val statLabel = ink.paint(11f, ink.medium, Ink.MUTED).apply { letterSpacing = 0.1f }
    private val statValue = ink.paint(16f, ink.regular)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * u; color = 0x40FFFFFF }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.2f * u; color = Ink.WHITE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val area = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val path = Path()

    private val fmtShort = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())
    private val fmtTitle = DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault())
    private val fmtClock = DateTimeFormatter.ofPattern(if (h24) "HH:mm" else "h:mm a", Locale.getDefault())
    private val fmtHour = DateTimeFormatter.ofPattern(if (h24) "HH" else "h a", Locale.getDefault())

    init {
        setWillNotDraw(false)
        addView(dots, LayoutParams(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            .apply { bottomMargin = (24 * u).toInt() })
        dots.visibility = View.GONE
    }

    /** New data (null: the fetch failed). Keeps the chosen day. */
    fun setData(f: Forecast?) {
        if (f == null) failed = fc == null
        else {
            fc = f
            failed = false
            day = day.coerceIn(0, max(0, f.days.size - 1)); hx = day.toFloat()
            dots.set(f.days.size, day, false)
        }
        invalidate()
    }

    override fun key(e: KeyEvent): Boolean {
        val n = fc?.days?.size ?: 0
        val arrows = e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || e.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
        if (e.action != KeyEvent.ACTION_DOWN) return arrows
        if (!arrows) return false
        val next = day + if (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
        if (next !in 0 until n) { Sounds.edge(); return true }
        Sounds.move()
        prevDay = day; day = next; dir = if (next > prevDay) 1 else -1
        dots.set(n, day)
        val x0 = hx
        moveAnim?.cancel()
        moveAnim = tween(Motion.FOCUS_MS) { hx = x0 + (day - x0) * it; invalidate() }
        fadeAnim?.cancel()
        k = 0f
        fadeAnim = tween(Motion.FOCUS_MS + 60) { k = it; invalidate() }
        return true
    }

    private fun deg(cel: Double) = if (cel.isNaN()) "–" else "${(if (fahrenheit) cel * 9 / 5 + 32 else cel).roundToInt()}°"

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val pad = 40f * u
        val res = context.resources
        val f = fc
        if (f == null || f.days.isEmpty()) {
            c.drawText(res.getString(if (failed) R.string.wx_failed else R.string.wx_loading), w / 2f, h / 2f, center)
            return
        }
        city?.let { c.drawText(it.uppercase(), pad, pad + 10f * u, cityP) }

        // the week strip
        val n = f.days.size
        val stripTop = pad + 26f * u
        val stripH = 92f * u
        val colW = (w - 2 * pad) / n
        r.set(pad + hx * colW + 5f * u, stripTop, pad + (hx + 1) * colW - 5f * u, stripTop + stripH)
        fill.color = 0x2EFFFFFF
        c.drawRoundRect(r, 20f * u, 20f * u, fill)
        c.drawRoundRect(r, 20f * u, 20f * u, stroke)
        for ((i, d) in f.days.withIndex()) {
            val cx = pad + colW * (i + 0.5f)
            val on = 1f - min(1f, abs(i - hx))
            colLabel.color = blend(Ink.MUTED, Ink.WHITE, on)
            val name = if (d.date == today) res.getString(R.string.wx_today) else fmtShort.format(d.date)
            c.drawText(name, cx, stripTop + 24f * u, colLabel)
            WxIcon.draw(c, d.code, cx, stripTop + 48f * u, 28f * u)
            val hi = deg(d.max); val lo = deg(d.min)
            val gap = 6f * u
            val total = hiP.measureText(hi) + gap + loP.measureText(lo)
            c.drawText(hi, cx - total / 2f, stripTop + 80f * u, hiP)
            c.drawText(lo, cx - total / 2f + hiP.measureText(hi) + gap, stripTop + 80f * u, loP)
        }

        // the chosen day, sliding in
        val top = stripTop + stripH + 30f * u
        val bottom = h - pad - 34f * u
        // in place: the old day fades out quickly, the new one fades in
        if (k < 1f) drawDay(c, f, prevDay, (1f - 2f * k).coerceAtLeast(0f), 0f, pad, w - pad, top, bottom)
        drawDay(c, f, day, k, 0f, pad, w - pad, top, bottom)
    }

    private fun drawDay(c: Canvas, f: Forecast, i: Int, alpha: Float, dx: Float, left: Float, right: Float, top: Float, bottom: Float) {
        if (alpha <= 0.01f) return
        val d = f.days[i]
        val res = context.resources
        val save = c.saveLayerAlpha(left - 40f * u, top - 10f * u, right + 40f * u, bottom + 10f * u, (alpha * 255).roundToInt())
        c.translate(dx, 0f)
        val statsTop = bottom - 46f * u
        val lw = (right - left) * 0.34f

        // left: the day, conditions, high / low
        var title = fmtTitle.format(d.date)
        if (d.date == today && f.now != null) title = res.getString(R.string.wx_now_line, deg(f.now.temp), deg(f.now.feels))
        c.drawText(TextUtils.ellipsize(title, dayTitle, lw, TextUtils.TruncateAt.END).toString(), left, top + 12f * u, dayTitle)
        WxIcon.draw(c, d.code, left + 24f * u, top + 50f * u, 46f * u)
        c.drawText(TextUtils.ellipsize(word(d.code), cond, lw - 60f * u, TextUtils.TruncateAt.END).toString(), left + 60f * u, top + 58f * u, cond)
        val hi = deg(d.max)
        c.drawText(hi, left, top + 118f * u, bigHi)
        c.drawText("  " + deg(d.min), left + bigHi.measureText(hi), top + 118f * u, bigLo)

        // right: the hours
        drawChart(c, f, d, left + lw + 24f * u, right, top, statsTop - 14f * u)

        // bottom: details
        val rain = if (d.rainMm >= 0.1) "${d.rainProb}%  ·  ${"%.1f".format(Locale.getDefault(), d.rainMm)} mm" else "${d.rainProb}%"
        val wind = if (fahrenheit) "${(d.wind * 0.621).roundToInt()} mph" else "${d.wind.roundToInt()} km/h"
        val stats = listOf(
            res.getString(R.string.wx_rain_chance) to rain,
            res.getString(R.string.wx_wind) to wind,
            res.getString(R.string.wx_uv) to (if (d.uv.isNaN()) "–" else d.uv.roundToInt().toString()),
            res.getString(R.string.wx_sunrise) to (d.sunrise?.let { fmtClock.format(it) } ?: "–"),
            res.getString(R.string.wx_sunset) to (d.sunset?.let { fmtClock.format(it) } ?: "–"),
        )
        fill.color = 0x1FFFFFFF
        c.drawRect(left, statsTop - 4f * u, right, statsTop - 3f * u, fill)
        val sw = (right - left) / stats.size
        for ((n, s) in stats.withIndex()) {
            val x = left + sw * n
            c.drawText(s.first.uppercase(), x, statsTop + 16f * u, statLabel)
            c.drawText(s.second, x, statsTop + 40f * u, statValue)
        }
        c.restoreToCount(save)
    }

    /** The day's temperature as a soft line, the chance of rain as faint bars under it, labels every 3 hours. */
    private fun drawChart(c: Canvas, f: Forecast, d: Forecast.Day, left: Float, right: Float, top: Float, bottom: Float) {
        val hours = f.hours.filter { it.time.toLocalDate() == d.date && !it.temp.isNaN() }
        if (hours.size < 2) return
        val plotTop = top + 26f * u
        val plotBottom = bottom - 22f * u
        val lo = hours.minOf { it.temp }; val hi = hours.maxOf { it.temp }
        val span = max(1.0, hi - lo)
        val step = (right - left) / (hours.size - 1)
        fun x(i: Int) = left + step * i
        fun y(t: Double) = (plotBottom - (t - lo) / span * (plotBottom - plotTop) * 0.8f - (plotBottom - plotTop) * 0.1f).toFloat()

        // rain chance, from the bottom
        fill.color = Ink.RAIN
        for ((i, hr) in hours.withIndex()) if (hr.prob > 0) {
            fill.alpha = 0x55
            val bh = (plotBottom - plotTop) * 0.45f * hr.prob / 100f
            r.set(x(i) - step * 0.28f, plotBottom - bh, x(i) + step * 0.28f, plotBottom)
            c.drawRoundRect(r, 2f * u, 2f * u, fill)
        }
        // the line, smoothed, with a soft fade under it
        path.reset()
        path.moveTo(x(0), y(hours[0].temp))
        for (i in 1 until hours.size) {
            val mx = (x(i - 1) + x(i)) / 2f
            path.cubicTo(mx, y(hours[i - 1].temp), mx, y(hours[i].temp), x(i), y(hours[i].temp))
        }
        val under = Path(path).apply { lineTo(x(hours.size - 1), plotBottom); lineTo(x(0), plotBottom); close() }
        area.shader = LinearGradient(0f, plotTop, 0f, plotBottom, 0x40FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawPath(under, area)
        c.drawPath(path, line)
        // now
        if (d.date == today) {
            val nowAt = hours.indexOfFirst { it.time.hour == LocalDateTime.now().hour }
            if (nowAt >= 0) {
                fill.color = Ink.WHITE
                c.drawCircle(x(nowAt), y(hours[nowAt].temp), 4.5f * u, fill)
            }
        }
        for ((i, hr) in hours.withIndex()) if (hr.time.hour % 3 == 0) {
            c.drawText(deg(hr.temp), x(i), y(hr.temp) - 10f * u, chartTemp)
            c.drawText(fmtHour.format(hr.time).lowercase(), x(i), bottom, chartTime)
        }
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int) = (((a shr s) and 0xFF) + ((((b shr s) and 0xFF) - ((a shr s) and 0xFF)) * t)).roundToInt() shl s
        return ch(24) or ch(16) or ch(8) or ch(0)
    }
}

/** Simple weather glyphs, drawn: sun, cloud, rain, drizzle, snow, thunder, fog (WMO codes, as Open-Meteo gives). */
internal object WxIcon {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val tmp = Path()

    fun draw(c: Canvas, code: Int, cx: Float, cy: Float, s: Float) {
        when (code) {
            0 -> sun(c, cx, cy, s)
            1, 2 -> { sun(c, cx - s * 0.16f, cy - s * 0.14f, s * 0.72f); cloud(c, cx + s * 0.08f, cy + s * 0.06f, s * 0.78f, 0xFFF5F5F7.toInt()) }
            3 -> cloud(c, cx, cy, s, 0xFFDCDDE3.toInt())
            45, 48 -> fog(c, cx, cy, s)
            in 51..57 -> { cloud(c, cx, cy - s * 0.12f, s * 0.9f, 0xFFDCDDE3.toInt()); drops(c, cx, cy, s, short = true) }
            in 61..67, in 80..82 -> { cloud(c, cx, cy - s * 0.12f, s * 0.9f, 0xFFDCDDE3.toInt()); drops(c, cx, cy, s, short = false) }
            in 71..77, 85, 86 -> { cloud(c, cx, cy - s * 0.12f, s * 0.9f, 0xFFDCDDE3.toInt()); snow(c, cx, cy, s) }
            in 95..99 -> { cloud(c, cx, cy - s * 0.12f, s * 0.9f, 0xFFC9CBD3.toInt()); bolt(c, cx, cy, s) }
            else -> cloud(c, cx, cy, s, 0xFFDCDDE3.toInt())
        }
    }

    private fun sun(c: Canvas, cx: Float, cy: Float, s: Float) {
        p.style = Paint.Style.FILL; p.color = 0xFFFFD25A.toInt()
        c.drawCircle(cx, cy, s * 0.2f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.065f; p.strokeCap = Paint.Cap.ROUND
        for (i in 0 until 8) {
            val a = Math.PI * i / 4
            val ca = Math.cos(a).toFloat(); val sa = Math.sin(a).toFloat()
            c.drawLine(cx + ca * s * 0.3f, cy + sa * s * 0.3f, cx + ca * s * 0.42f, cy + sa * s * 0.42f, p)
        }
    }

    private fun cloud(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        path.reset()
        path.addRoundRect(cx - s * 0.4f, cy - s * 0.02f, cx + s * 0.4f, cy + s * 0.2f, s * 0.11f, s * 0.11f, Path.Direction.CW)
        for ((x, y, r) in listOf(Triple(-0.16f, 0.0f, 0.17f), Triple(0.08f, -0.07f, 0.23f), Triple(0.27f, 0.05f, 0.14f))) {
            tmp.reset(); tmp.addCircle(cx + x * s, cy + y * s, r * s, Path.Direction.CW)
            path.op(tmp, Path.Op.UNION)
        }
        p.style = Paint.Style.FILL; p.color = color
        c.drawPath(path, p)
    }

    private fun drops(c: Canvas, cx: Float, cy: Float, s: Float, short: Boolean) {
        p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.06f; p.strokeCap = Paint.Cap.ROUND; p.color = Ink.RAIN
        val len = if (short) 0.08f else 0.16f
        for (i in -1..1) {
            val x = cx + i * s * 0.2f
            c.drawLine(x, cy + s * 0.18f, x - s * 0.05f, cy + s * (0.18f + len), p)
        }
    }

    private fun snow(c: Canvas, cx: Float, cy: Float, s: Float) {
        p.style = Paint.Style.FILL; p.color = 0xFFFFFFFF.toInt()
        for (i in -1..1) c.drawCircle(cx + i * s * 0.2f, cy + s * (0.24f + if (i == 0) 0.08f else 0f), s * 0.045f, p)
    }

    private fun bolt(c: Canvas, cx: Float, cy: Float, s: Float) {
        path.reset()
        path.moveTo(cx + s * 0.04f, cy + s * 0.08f)
        path.lineTo(cx - s * 0.1f, cy + s * 0.28f)
        path.lineTo(cx + s * 0.0f, cy + s * 0.28f)
        path.lineTo(cx - s * 0.06f, cy + s * 0.46f)
        path.lineTo(cx + s * 0.12f, cy + s * 0.22f)
        path.lineTo(cx + s * 0.02f, cy + s * 0.22f)
        path.close()
        p.style = Paint.Style.FILL; p.color = 0xFFFFC94A.toInt()
        c.drawPath(path, p)
    }

    private fun fog(c: Canvas, cx: Float, cy: Float, s: Float) {
        p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.07f; p.strokeCap = Paint.Cap.ROUND; p.color = 0xFFDCDDE3.toInt()
        for ((i, wdt) in listOf(0.36f, 0.28f, 0.34f).withIndex()) {
            val y = cy + (i - 1) * s * 0.17f
            val off = if (i == 1) s * 0.06f else 0f
            c.drawLine(cx - s * wdt + off, y, cx + s * wdt + off, y, p)
        }
    }
}
