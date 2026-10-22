package dev.plume

import android.content.Context
import android.content.SharedPreferences
import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * News from Google News' public RSS feeds: one edition per country and language, topic sections, and local news
 * by city name. No account or key. Fetched at most every [FRESH_MS], saved to a file so the news screen and the
 * screensaver show stories straight away, offline too.
 */
internal object News {

    /** A Google News edition: its name in its own language, and the three parameters every feed URL takes. */
    class Edition(val name: String, val hl: String, val gl: String, val ceid: String)

    val EDITIONS = listOf(
        Edition("India · English", "en-IN", "IN", "IN:en"),
        Edition("India · हिन्दी", "hi", "IN", "IN:hi"),
        Edition("India · தமிழ்", "ta", "IN", "IN:ta"),
        Edition("India · తెలుగు", "te", "IN", "IN:te"),
        Edition("India · മലയാളം", "ml", "IN", "IN:ml"),
        Edition("India · मराठी", "mr", "IN", "IN:mr"),
        Edition("India · বাংলা", "bn", "IN", "IN:bn"),
        Edition("United States", "en-US", "US", "US:en"),
        Edition("United Kingdom", "en-GB", "GB", "GB:en"),
        Edition("Canada · English", "en-CA", "CA", "CA:en"),
        Edition("Canada · Français", "fr-CA", "CA", "CA:fr"),
        Edition("Australia", "en-AU", "AU", "AU:en"),
        Edition("New Zealand", "en-NZ", "NZ", "NZ:en"),
        Edition("Ireland", "en-IE", "IE", "IE:en"),
        Edition("Singapore", "en-SG", "SG", "SG:en"),
        Edition("Malaysia · English", "en-MY", "MY", "MY:en"),
        Edition("Philippines", "en-PH", "PH", "PH:en"),
        Edition("Pakistan", "en-PK", "PK", "PK:en"),
        Edition("বাংলাদেশ", "bn", "BD", "BD:bn"),
        Edition("UAE · English", "en-AE", "AE", "AE:en"),
        Edition("الإمارات", "ar", "AE", "AE:ar"),
        Edition("السعودية", "ar", "SA", "SA:ar"),
        Edition("مصر", "ar", "EG", "EG:ar"),
        Edition("South Africa", "en-ZA", "ZA", "ZA:en"),
        Edition("Nigeria", "en-NG", "NG", "NG:en"),
        Edition("Kenya", "en-KE", "KE", "KE:en"),
        Edition("Deutschland", "de", "DE", "DE:de"),
        Edition("France", "fr", "FR", "FR:fr"),
        Edition("Italia", "it", "IT", "IT:it"),
        Edition("España", "es", "ES", "ES:es"),
        Edition("Nederland", "nl", "NL", "NL:nl"),
        Edition("Brasil", "pt-BR", "BR", "BR:pt-419"),
        Edition("Portugal", "pt-PT", "PT", "PT:pt-150"),
        Edition("México", "es-419", "MX", "MX:es-419"),
        Edition("Argentina", "es-419", "AR", "AR:es-419"),
        Edition("日本", "ja", "JP", "JP:ja"),
        Edition("대한민국", "ko", "KR", "KR:ko"),
        Edition("Indonesia", "id", "ID", "ID:id"),
        Edition("台灣", "zh-TW", "TW", "TW:zh-Hant"),
        Edition("香港", "zh-HK", "HK", "HK:zh-Hant"),
    )

    /** Sections, in the order they're shown. [section] is Google's topic name; null for top stories and local. */
    class Topic(val key: String, val label: String, val section: String?, val search: String)

    val TOPICS = listOf(
        Topic("top", "Top stories", null, "news"),
        Topic("world", "World", "WORLD", "world news"),
        Topic("nation", "Nation", "NATION", "national news"),
        Topic("local", "Local", null, ""),
        Topic("business", "Business", "BUSINESS", "business"),
        Topic("technology", "Technology", "TECHNOLOGY", "technology"),
        Topic("entertainment", "Entertainment", "ENTERTAINMENT", "entertainment"),
        Topic("sports", "Sports", "SPORTS", "sports"),
        Topic("science", "Science", "SCIENCE", "science"),
        Topic("health", "Health", "HEALTH", "health"),
    )

    /** Off until turned on in settings: kept short so the screensaver cycles the essentials. */
    val DEFAULT_OFF = setOf("entertainment", "sports", "science", "health")

    class Story(val title: String, val source: String, val link: String, val time: Long)
    class Section(val key: String, val name: String, val stories: List<Story>)

    private const val FRESH_MS = 30 * 60_000L
    private const val PER_SECTION = 8
    private const val FILE = "news.json"

    /** The chosen edition, or the one matching the TV's language and country (or the IP's country), else US. */
    fun edition(prefs: SharedPreferences): Edition {
        prefs.getString("news_edition", null)?.let { id -> EDITIONS.firstOrNull { it.ceid == id }?.let { return it } }
        val loc = Locale.getDefault()
        val country = loc.country.ifEmpty { prefs.getString("geo_cc", "") ?: "" }.uppercase()
        return EDITIONS.firstOrNull { it.gl == country && it.hl.substringBefore('-') == loc.language }
            ?: EDITIONS.firstOrNull { it.gl == country && it.hl.startsWith("en") }
            ?: EDITIONS.firstOrNull { it.gl == country }
            ?: EDITIONS.first { it.ceid == "US:en" }
    }

    /** Topics you added (anything Google News can search: "Formula 1", "ISRO", "Bengaluru metro"), oldest first. */
    fun custom(prefs: SharedPreferences): List<Topic> =
        (prefs.getString("news_custom", "") ?: "").split('\n').filter { it.isNotBlank() }.map { Topic("q:$it", it, null, it) }

    fun addCustom(prefs: SharedPreferences, term: String) {
        val t = term.trim().replace('\n', ' ')
        if (t.isEmpty() || custom(prefs).any { it.label.equals(t, ignoreCase = true) }) return
        prefs.edit().putString("news_custom", (custom(prefs).map { it.label } + t).joinToString("\n")).apply()
    }

    fun removeCustom(prefs: SharedPreferences, term: String) {
        prefs.edit().putString("news_custom", custom(prefs).map { it.label }.filter { it != term }.joinToString("\n")).apply()
    }

    fun topicsOn(prefs: SharedPreferences): List<Topic> {
        val off = prefs.getStringSet("news_off", null) ?: DEFAULT_OFF
        return ordered(prefs, TOPICS.filter { it.key !in off && (it.key != "local" || city(prefs) != null) } + custom(prefs)) { it.key }
    }

    /** The section order you set (long-press a section on the news screen); new ones go at the end. */
    fun order(prefs: SharedPreferences): List<String> = (prefs.getString("news_order", "") ?: "").split('\n').filter { it.isNotEmpty() }

    fun setOrder(prefs: SharedPreferences, keys: List<String>) { prefs.edit().putString("news_order", keys.joinToString("\n")).apply() }

    fun <T> ordered(prefs: SharedPreferences, items: List<T>, key: (T) -> String): List<T> {
        val o = order(prefs)
        return items.withIndex().sortedBy { (i, it) -> o.indexOf(key(it)).let { at -> if (at < 0) o.size + i else at } }.map { it.value }
    }

    /** Hides a built-in section (it can be turned back on in settings → News → Topics). */
    fun hide(prefs: SharedPreferences, key: String) {
        val off = prefs.getStringSet("news_off", null) ?: DEFAULT_OFF
        prefs.edit().putStringSet("news_off", off + key).apply()
    }

    /** Local news follows the weather location (automatic from the IP, or the city picked in settings). */
    fun city(prefs: SharedPreferences): String? = prefs.getString("geo_city", null)?.takeIf { it.isNotBlank() }

    /** Shown name of a section: the country for "Nation", the city for "Local". */
    fun sectionName(t: Topic, prefs: SharedPreferences): String = when (t.key) {
        "nation" -> Locale("", edition(prefs).gl).displayCountry.ifEmpty { t.label }
        "local" -> city(prefs) ?: t.label
        else -> t.label
    }

    /** What's saved, newest fetch; empty until the first one. */
    fun load(c: Context): List<Section> = try {
        val o = JSONObject(File(c.filesDir, FILE).readText())
        val arr = o.getJSONArray("sections")
        List(arr.length()) { i ->
            val s = arr.getJSONObject(i)
            val st = s.getJSONArray("stories")
            Section(s.getString("key"), s.getString("name"), List(st.length()) { j ->
                val x = st.getJSONObject(j)
                Story(x.getString("title"), x.getString("source"), x.getString("link"), x.getLong("time"))
            })
        }
    } catch (e: Exception) {
        emptyList()
    }

    /** The settings the saved news was fetched with: a change (edition, topics, city) refetches at once. */
    private fun signature(prefs: SharedPreferences) =
        edition(prefs).ceid + "|" + topicsOn(prefs).map { it.key }.sorted().joinToString(",") + "|" + (city(prefs) ?: "")

    /**
     * Fetches every section that's on, unless the saved news is fresh and was fetched with the same settings.
     * Blocking: call it off the main thread. Returns what's saved afterwards (the old news if every fetch failed).
     */
    fun refresh(c: Context, prefs: SharedPreferences, force: Boolean = false): List<Section> {
        val sig = signature(prefs)
        val fresh = System.currentTimeMillis() - prefs.getLong("news_time", 0) < FRESH_MS && prefs.getString("news_sig", null) == sig
        if (fresh && !force) return load(c)
        val ed = edition(prefs)
        val seen = HashSet<String>()
        val sections = topicsOn(prefs).mapNotNull { t ->
            val stories = fetchTopic(t, ed, prefs).filter { seen.add(it.title.lowercase()) }.take(PER_SECTION)
            if (stories.isEmpty()) null else Section(t.key, sectionName(t, prefs), stories)
        }
        if (sections.isEmpty()) return load(c) // offline or blocked: keep what we had
        val json = JSONObject().put("sections", JSONArray().apply {
            for (s in sections) put(JSONObject().put("key", s.key).put("name", s.name).put("stories", JSONArray().apply {
                for (x in s.stories) put(JSONObject().put("title", x.title).put("source", x.source).put("link", x.link).put("time", x.time))
            }))
        })
        File(c.filesDir, "$FILE.part").apply { writeText(json.toString()); renameTo(File(c.filesDir, FILE)) }
        prefs.edit().putLong("news_time", System.currentTimeMillis()).putString("news_sig", sig).apply()
        return sections
    }

    /** The topic's own feed; if that fails or comes back empty, a search for it in the same edition. */
    private fun fetchTopic(t: Topic, ed: Edition, prefs: SharedPreferences): List<Story> {
        val q = "hl=${ed.hl}&gl=${ed.gl}&ceid=${enc(ed.ceid)}"
        if (t.key.startsWith("q:")) return parse("https://news.google.com/rss/search?q=${enc("${t.search} when:2d")}&$q")
        val primary = when {
            t.key == "top" -> "https://news.google.com/rss?$q"
            t.key == "local" -> "https://news.google.com/rss/headlines/section/geo/${enc(city(prefs) ?: return emptyList())}?$q"
            else -> "https://news.google.com/rss/headlines/section/topic/${t.section}?$q"
        }
        val term = if (t.key == "local") city(prefs) ?: return emptyList() else t.search
        val fallback = "https://news.google.com/rss/search?q=${enc("$term when:1d")}&$q"
        return parse(primary).ifEmpty { parse(fallback) }
    }

    private fun parse(url: String): List<Story> = try {
        val con = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android TV) Plume")
        }
        if (con.responseCode != 200) emptyList() else con.inputStream.use { input ->
            val p = Xml.newPullParser()
            p.setInput(input, null)
            val out = ArrayList<Story>()
            var inItem = false
            var title = ""; var link = ""; var source = ""; var date = ""
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                when (p.eventType) {
                    XmlPullParser.START_TAG -> when (p.name) {
                        "item" -> { inItem = true; title = ""; link = ""; source = ""; date = "" }
                        "title" -> if (inItem) title = p.nextText()
                        "link" -> if (inItem) link = p.nextText()
                        "source" -> if (inItem) source = p.nextText()
                        "pubDate" -> if (inItem) date = p.nextText()
                    }
                    XmlPullParser.END_TAG -> if (p.name == "item" && inItem) {
                        inItem = false
                        // Titles come as "Headline - Source": the source is shown on its own line.
                        val clean = if (source.isNotEmpty() && title.endsWith(" - $source")) title.removeSuffix(" - $source") else title
                        if (clean.isNotBlank()) out += Story(clean.trim(), source.trim(), link.trim(), time(date))
                    }
                }
            }
            out
        }
    } catch (e: Exception) {
        android.util.Log.w("TvHome", "news fetch failed: $url ($e)")
        emptyList()
    }

    private fun time(s: String): Long = try {
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(s)?.time ?: 0L
    } catch (e: Exception) {
        0L
    }

    /** "12 min ago", "3 h ago", "2 d ago". */
    fun ago(time: Long): String {
        if (time <= 0L) return ""
        val min = (System.currentTimeMillis() - time) / 60_000
        return when {
            min < 1 -> "now"
            min < 60 -> "$min min ago"
            min < 48 * 60 -> "${min / 60} h ago"
            else -> "${min / 1440} d ago"
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ---------- story details: a summary and a picture ----------

    /** What a headline alone doesn't give: a few sentences of summary, a picture, and the publisher's own link. */
    class Detail(val summary: String, val image: String?, val url: String?)

    private val details = object : android.util.LruCache<String, Detail>(64) {}

    fun cachedDetail(s: Story): Detail? = details.get(s.title)

    /**
     * Google News feeds carry only headlines. For the picture and summary: Bing's news feed, searched for the
     * headline, gives a snippet, a picture (up to 1600 px) and the publisher's link; the publisher's own page then
     * usually has a larger picture and a longer summary (its Open Graph tags). Each step is optional: a story shows
     * with whatever arrived. Blocking: call it off the main thread.
     */
    fun detail(s: Story, prefs: SharedPreferences): Detail {
        details.get(s.title)?.let { return it }
        val ed = edition(prefs)
        val mkt = (if (ed.hl.contains('-')) ed.hl else "${ed.hl}-${ed.gl}").let { if (it.length == 5) it else "en-${ed.gl}" }
        var summary = ""
        var image: String? = null
        var url: String? = null
        try {
            val hit = bingItems("https://www.bing.com/news/search?q=${enc(s.title)}&format=rss&setmkt=$mkt")
                .maxByOrNull { overlap(it.title, s.title) }?.takeIf { overlap(it.title, s.title) >= 0.45 }
            if (hit != null) {
                summary = hit.description
                image = hit.image?.let { (if (it.startsWith("http:")) "https:" + it.removePrefix("http:") else it) + "&w=1600&h=900&c=14" }
                url = hit.url
            }
        } catch (e: Exception) {
            android.util.Log.w("TvHome", "news detail (bing) failed: ${s.title} ($e)")
        }
        url?.let { page ->
            try {
                val og = openGraph(page)
                og["og:image"]?.takeIf { it.startsWith("http") }?.let { image = it }
                // The longest of: the article's opening (its embedded text or first paragraphs), the page's own
                // summary, and Bing's snippet.
                val longer = listOfNotNull(og["body"], og["og:description"], og["description"]).maxByOrNull { it.length }
                if (longer != null && longer.length > summary.length) summary = longer
            } catch (e: Throwable) {
                // Paywalls, consent pages, app-only sites, odd pages: keep what Bing gave.
            }
        }
        val d = Detail(clean(summary), image, url)
        details.put(s.title, d)
        return d
    }

    private class BingItem(val title: String, val description: String, val image: String?, val url: String?)

    private fun bingItems(feed: String): List<BingItem> {
        val con = open(feed)
        if (con.responseCode != 200) return emptyList()
        return con.inputStream.use { input ->
            val p = Xml.newPullParser()
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            p.setInput(input, null)
            val out = ArrayList<BingItem>()
            var inItem = false
            var title = ""; var desc = ""; var img: String? = null; var link = ""
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                when (p.eventType) {
                    XmlPullParser.START_TAG -> when (p.name) {
                        "item" -> { inItem = true; title = ""; desc = ""; img = null; link = "" }
                        "title" -> if (inItem) title = p.nextText()
                        "description" -> if (inItem) desc = p.nextText()
                        "link" -> if (inItem) link = p.nextText()
                        "News:Image" -> if (inItem) img = p.nextText()
                    }
                    XmlPullParser.END_TAG -> if (p.name == "item" && inItem) {
                        inItem = false
                        // Bing wraps the publisher's link; the real one is its url= parameter.
                        val real = Regex("[?&]url=([^&]+)").find(link)?.groupValues?.get(1)?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                        out += BingItem(title, desc, img, real ?: link.takeIf { it.isNotBlank() })
                    }
                }
            }
            out
        }
    }

    /**
     * From a web page: og:image, og:description, the plain description, and "body": the article's opening, from
     * the full text many news sites embed for search engines (articleBody), or else its first real paragraphs.
     */
    private fun openGraph(page: String): Map<String, String> {
        val con = open(page)
        if (con.responseCode !in 200..299) return emptyMap()
        val head = con.inputStream.use { input ->
            val buf = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(32 * 1024)
            while (buf.size() < 900 * 1024) { // enough for the article's start on most pages
                val n = input.read(chunk)
                if (n < 0) break
                buf.write(chunk, 0, n)
            }
            buf.toString("UTF-8")
        }
        val out = HashMap<String, String>()
        for (m in Regex("<meta\\s[^>]*>", RegexOption.IGNORE_CASE).findAll(head)) {
            val tag = m.value
            val key = Regex("(?:property|name)\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)?.lowercase() ?: continue
            val value = Regex("content\\s*=\\s*\"([^\"]*)\"|content\\s*=\\s*'([^']*)'", RegexOption.IGNORE_CASE).find(tag)
                ?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } } ?: continue
            if (key in setOf("og:image", "og:description", "description", "twitter:image") && key !in out) out[key] = value
        }
        if ("og:image" !in out) out["twitter:image"]?.let { out["og:image"] = it }
        articleOpening(head)?.let { out["body"] = it }
        return out
    }

    private fun articleOpening(html: String): String? {
        // The embedded full text, read as a JSON string by hand: a regex over a long article can overflow the stack.
        val key = html.indexOf("\"articleBody\"")
        if (key >= 0) {
            var i = html.indexOf('"', html.indexOf(':', key) + 1)
            if (i > 0) {
                val sb = StringBuilder()
                i++
                while (i < html.length && sb.length < 3000) {
                    val ch = html[i]
                    if (ch == '"') break
                    if (ch == '\\' && i + 1 < html.length) {
                        when (val e = html[i + 1]) {
                            'n', 'r', 't' -> sb.append(' ')
                            'u' -> if (i + 5 < html.length) {
                                html.substring(i + 2, i + 6).toIntOrNull(16)?.let { sb.append(it.toChar()) }
                                i += 4
                            }
                            else -> sb.append(e)
                        }
                        i += 2
                        continue
                    }
                    sb.append(ch)
                    i++
                }
                if (sb.length > 200) return sb.toString()
            }
        }
        val paras = Regex("<p[^>]*>(.*?)</p>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).findAll(html)
            .map { android.text.Html.fromHtml(it.groupValues[1], android.text.Html.FROM_HTML_MODE_LEGACY).toString().replace(Regex("\\s+"), " ").trim() }
            .filter { it.length > 80 && !it.contains("cookie", ignoreCase = true) && !it.contains("subscribe", ignoreCase = true) }
            .take(4).toList()
        return paras.joinToString(" ").takeIf { it.length > 120 }
    }

    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 7000
        readTimeout = 7000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Smart TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
        setRequestProperty("Accept-Language", "en")
    }

    /** Share of the headline's words found in the other title: picks the right Bing result for a story. */
    private fun overlap(a: String, b: String): Double {
        fun words(s: String) = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 2 }.toSet()
        val wb = words(b)
        if (wb.isEmpty()) return 0.0
        return words(a).count { it in wb }.toDouble() / wb.size
    }

    /** HTML entities and tags out, whitespace tidied, and cut at a sentence end near 1100 characters. */
    private fun clean(s: String): String {
        val t = android.text.Html.fromHtml(s, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
            .replace(Regex("\\s+"), " ").trim().removeSuffix("...").removeSuffix("…").trim()
        if (t.length <= 1100) return t
        val cut = t.substring(0, 1100)
        val end = cut.lastIndexOfAny(charArrayOf('.', '!', '?'))
        return if (end > 500) cut.substring(0, end + 1) else cut.substringBeforeLast(' ') + "…"
    }
}
