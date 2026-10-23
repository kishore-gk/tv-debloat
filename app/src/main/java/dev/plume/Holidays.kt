package dev.plume

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

/** A holiday or festival on the calendar. [public]: a day off (gazetted); otherwise a festival or observance. */
internal class Holiday(val date: LocalDate, val name: String, val public: Boolean)

/**
 * Holidays and festivals for the calendar, kept light: no API calls while you browse.
 *
 * 1. Google's public holiday calendar for the TV's country (one small .ics file, fetched at most every two weeks
 *    in the background and kept on disk). It covers festivals that move every year.
 * 2. If that isn't there yet (first run, offline, or a country without one): for India, the central government's
 *    2026 and 2027 lists (DoP&T: closed holidays as days off, restricted holidays as festivals), built in.
 * 3. Beyond those years, India's fixed-date national holidays.
 */
internal object Holidays {
    private const val REFRESH_MS = 14L * 24 * 3600_000

    /** Google holiday calendar ids by country code (the TV's location, see refreshWeather). */
    private val GOOGLE = mapOf(
        "IN" to "en.indian", "US" to "en.usa", "GB" to "en.uk", "AU" to "en.australian", "CA" to "en.canadian",
        "SG" to "en.singapore", "DE" to "en.german", "FR" to "en.french", "JP" to "en.japanese",
    )

    private fun country(prefs: SharedPreferences) = prefs.getString("geo_cc", null)?.uppercase()?.ifEmpty { null } ?: "IN"

    private fun file(c: Context, id: String) = File(c.filesDir, "holidays_$id.ics")

    @Volatile private var cache: Pair<String, Map<Int, List<Holiday>>>? = null // calendar id -> holidays by year

    /** Holidays in this month, by date. Reads the saved file; never touches the network. */
    fun month(c: Context, prefs: SharedPreferences, year: Int, month: Int): List<Holiday> =
        year(c, prefs, year).filter { it.date.monthValue == month }.sortedWith(compareBy({ it.date }, { !it.public }))

    private fun year(c: Context, prefs: SharedPreferences, year: Int): List<Holiday> {
        val cc = country(prefs)
        GOOGLE[cc]?.let { id ->
            val parsed = cache?.takeIf { it.first == id }?.second ?: parse(file(c, id)).also { cache = id to it }
            parsed[year]?.let { return it }
        }
        if (cc != "IN") return emptyList()
        return INDIA[year] ?: INDIA_FIXED.map { (md, name) ->
            Holiday(LocalDate.of(year, md / 100, md % 100), name, md != 101)
        }
    }

    /** Fetches the country's holiday file if it's two weeks old (call off the UI thread). */
    fun refresh(c: Context, prefs: SharedPreferences) {
        val id = GOOGLE[country(prefs)] ?: return
        val f = file(c, id)
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < REFRESH_MS) return
        try {
            val url = "https://calendar.google.com/calendar/ical/$id%23holiday%40group.v.calendar.google.com/public/basic.ics"
            val conn = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 10_000; readTimeout = 15_000 }
            val text = conn.inputStream.use { it.readBytes().decodeToString() }
            if (!text.contains("BEGIN:VEVENT")) return
            val tmp = File(c.filesDir, "holidays_$id.tmp")
            tmp.writeText(text)
            tmp.renameTo(f)
            cache = null
        } catch (e: Exception) {
            android.util.Log.w("TvHome", "holidays failed ($e)") // keep what we have; try again next time
        }
    }

    /** A minimal iCalendar reader: all-day events, their name, and whether Google calls them a public holiday. */
    private fun parse(f: File): Map<Int, List<Holiday>> {
        if (!f.exists()) return emptyMap()
        val lines = ArrayList<String>()
        f.forEachLine { l -> if (l.startsWith(" ") && lines.isNotEmpty()) lines[lines.size - 1] += l.substring(1) else lines += l.trimEnd('\r') }
        val out = HashMap<Int, MutableList<Holiday>>()
        var date: LocalDate? = null; var name = ""; var desc = ""
        for (l in lines) {
            when {
                l == "BEGIN:VEVENT" -> { date = null; name = ""; desc = "" }
                l.startsWith("DTSTART") -> date = runCatching {
                    val v = l.substringAfter(':').take(8)
                    LocalDate.of(v.take(4).toInt(), v.substring(4, 6).toInt(), v.substring(6, 8).toInt())
                }.getOrNull()
                l.startsWith("SUMMARY") -> name = unescape(l.substringAfter(':'))
                l.startsWith("DESCRIPTION") -> desc = l.substringAfter(':')
                l == "END:VEVENT" -> date?.let { d ->
                    if (name.isNotBlank()) out.getOrPut(d.year) { ArrayList() } +=
                        Holiday(d, name, desc.startsWith("Public holiday") || desc.contains("Gazetted"))
                }
            }
        }
        return out
    }

    private fun unescape(s: String) = s.replace("\\,", ",").replace("\\;", ";").replace("\\n", " ").replace("\\\\", "\\").trim()

    // ---------- built in ----------

    /** MMDD to name; Jan 1 is an observance, the rest days off. */
    private val INDIA_FIXED = listOf(101 to "New Year's Day", 126 to "Republic Day", 815 to "Independence Day",
        1002 to "Gandhi Jayanti", 1225 to "Christmas")

    private fun list(year: Int, vararg items: Triple<Int, String, Boolean>) =
        items.map { (md, name, pub) -> Holiday(LocalDate.of(year, md / 100, md % 100), name, pub) }

    private fun h(md: Int, name: String) = Triple(md, name, true)
    private fun f(md: Int, name: String) = Triple(md, name, false)

    /** DoP&T lists for Delhi/New Delhi (O.M. 03.07.2025 for 2026, 16.07.2026 for 2027). Islamic dates follow the moon. */
    private val INDIA = mapOf(
        2026 to list(2026,
            f(101, "New Year's Day"), f(103, "Hazrat Ali's Birthday"), f(114, "Makar Sankranti / Pongal"),
            f(123, "Basant Panchami"), h(126, "Republic Day"), f(201, "Guru Ravidas Jayanti"),
            f(212, "Swami Dayananda Saraswati Jayanti"), f(215, "Maha Shivaratri"), f(219, "Shivaji Jayanti"),
            f(303, "Holika Dahan"), h(304, "Holi"), f(319, "Ugadi / Gudi Padwa"), f(320, "Jamat-ul-Vida"),
            h(321, "Eid ul-Fitr"), h(326, "Ram Navami"), h(331, "Mahavir Jayanti"), h(403, "Good Friday"),
            f(405, "Easter Sunday"), f(414, "Ambedkar Jayanti / Vaisakhi / Vishu"), f(415, "Bengali New Year / Bohag Bihu"),
            h(501, "Buddha Purnima"), f(509, "Rabindranath Tagore Jayanti"), h(527, "Bakrid / Eid ul-Adha"),
            h(626, "Muharram"), f(716, "Rath Yatra"), h(815, "Independence Day"), f(815, "Parsi New Year"),
            h(826, "Milad-un-Nabi"), f(826, "Onam"), f(828, "Raksha Bandhan"), h(904, "Janmashtami"),
            f(914, "Ganesh Chaturthi"), h(1002, "Gandhi Jayanti"), f(1018, "Durga Puja (Saptami)"),
            f(1019, "Durga Puja (Ashtami)"), f(1020, "Maha Navami"), h(1020, "Dussehra"),
            f(1026, "Valmiki Jayanti"), f(1029, "Karwa Chauth"), h(1108, "Diwali"), f(1108, "Naraka Chaturdashi"),
            f(1109, "Govardhan Puja"), f(1111, "Bhai Dooj"), f(1115, "Chhath Puja"), h(1124, "Guru Nanak Jayanti"),
            f(1124, "Guru Tegh Bahadur Martyrdom Day"), f(1223, "Hazrat Ali's Birthday"), f(1224, "Christmas Eve"),
            h(1225, "Christmas"),
        ),
        2027 to list(2027,
            f(101, "New Year's Day"), f(114, "Makar Sankranti / Magh Bihu"), f(115, "Pongal / Guru Gobind Singh Jayanti"),
            h(126, "Republic Day"), f(211, "Basant Panchami"), f(219, "Shivaji Jayanti"), f(220, "Guru Ravidas Jayanti"),
            f(302, "Swami Dayananda Saraswati Jayanti"), f(305, "Jamat-ul-Vida"), f(306, "Maha Shivaratri"),
            h(310, "Eid ul-Fitr"), f(322, "Holika Dahan"), h(323, "Holi"), h(326, "Good Friday"), f(328, "Easter Sunday"),
            f(407, "Ugadi / Gudi Padwa"), f(414, "Ambedkar Jayanti / Vaisakhi / Vishu"), h(415, "Ram Navami"),
            f(415, "Bengali New Year / Bohag Bihu"), h(419, "Mahavir Jayanti"), f(509, "Rabindranath Tagore Jayanti"),
            h(517, "Bakrid / Eid ul-Adha"), h(520, "Buddha Purnima"), h(616, "Muharram"), f(705, "Rath Yatra"),
            h(815, "Independence Day"), h(815, "Milad-un-Nabi"), f(815, "Parsi New Year"), f(817, "Raksha Bandhan"),
            h(825, "Janmashtami"), f(904, "Ganesh Chaturthi"), f(912, "Onam"), h(1002, "Gandhi Jayanti"),
            f(1006, "Durga Puja (Saptami)"), f(1007, "Durga Puja (Ashtami)"), f(1008, "Maha Navami"), h(1009, "Dussehra"),
            f(1015, "Valmiki Jayanti"), f(1018, "Karwa Chauth"), f(1028, "Naraka Chaturdashi"), h(1029, "Diwali"),
            f(1030, "Govardhan Puja"), f(1031, "Bhai Dooj"), f(1104, "Chhath Puja"), h(1114, "Guru Nanak Jayanti"),
            f(1124, "Guru Tegh Bahadur Martyrdom Day"), f(1212, "Hazrat Ali's Birthday"), f(1224, "Christmas Eve"),
            h(1225, "Christmas"),
        ),
    )
}
