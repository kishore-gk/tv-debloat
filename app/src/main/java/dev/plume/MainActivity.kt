package dev.plume

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.app.PendingIntent
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageInstaller
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Path
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.PathShape
import android.graphics.drawable.TransitionDrawable
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.text.InputType
import android.text.SpannableString
import android.text.TextUtils
import android.text.style.BulletSpan
import android.util.LruCache
import android.view.Gravity
import android.view.KeyEvent
import android.view.SoundEffectConstants
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Calendar
import java.util.concurrent.Executors
import org.json.JSONObject

/** Light/dark from the launcher's theme setting; shared with the Gallery screensaver. */
internal fun prefersDark(prefs: SharedPreferences) = when (prefs.getString("theme", "light")) {
    "dark" -> true
    "auto" -> Calendar.getInstance().get(Calendar.HOUR_OF_DAY) !in 7..18
    else -> false
}

/** "1.10" > "1.9" > "1.2.1" > "1.2": compared number by number, missing parts count as 0. */
internal fun newerVersion(a: String, b: String): Boolean {
    val x = a.split('.').map { it.toIntOrNull() ?: 0 }
    val y = b.split('.').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(x.size, y.size)) {
        val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
        if (d != 0) return d > 0
    }
    return false
}

/** "external" covers every attached volume (USB drives too) from Android 10; before that only the main one. */
internal val PHOTOS: Uri by lazy { // lazy: unit tests load this file without Android
    if (Build.VERSION.SDK_INT >= 29) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
}

/** Photo ids in a "folder:<bucket id>:<name>" choice. */
internal fun folderPhotos(cr: ContentResolver, choice: String): List<Long> =
    cr.query(PHOTOS, arrayOf(MediaStore.Images.Media._ID), "${MediaStore.Images.Media.BUCKET_ID} = ?", arrayOf(choice.split(':')[1]), null)
        ?.use { c -> List(c.count) { c.moveToNext(); c.getLong(0) } } ?: emptyList()

/**
 * Sort key of a Watch Next row: what you started comes before suggestions (types other than 0, "continue"), then
 * newest first. Some apps write the time in seconds, not milliseconds (Wholphin does, for what is playing): a number
 * that small is read as seconds, or it would sort a thousand times older than every other row.
 */
internal fun watchSortKey(type: Long, engaged: Long): Long {
    val millis = if (engaged in 1 until 100_000_000_000L) engaged * 1000 else engaged
    return (if (type == 0L) 1L shl 50 else 0L) + millis
}

/**
 * Newest first across apps (ties keep their order), each app's [perApp] newest ahead of the rest: one app's ten
 * shows can't bury the others. The rest follow, newest first, so an app on its own isn't held to [perApp].
 */
internal fun <T> newestPerApp(items: List<Pair<Long, T>>, perApp: Int, app: (T) -> String?): List<T> {
    val shown = HashMap<String?, Int>()
    val (front, rest) = items.sortedByDescending { it.first }.map { it.second }.partition { shown.merge(app(it), 1, Int::plus)!! <= perApp }
    return front + rest
}

/** Layout lock PIN, stored as "salt:sha256". Keeps it out of plain sight in a prefs backup; not real security. */
internal fun hashPin(pin: String, salt: String = ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }) =
    salt + ":" + MessageDigest.getInstance("SHA-256").digest((salt + pin).toByteArray()).joinToString("") { "%02x".format(it) }

internal fun pinMatches(stored: String, pin: String) = hashPin(pin, stored.substringBefore(':')) == stored

private val SET_TOP_WORDS = listOf("sky", "tivo", "xfinity", "comcast", "directv", "dish", "stb", "set-top", "settop", "cable", "satellite", "freesat", "freeview")
private val AUDIO_WORDS = listOf("soundbar", "sound bar", "sonos", "avr", "receiver", "home theater", "home theatre", "bose", "denon", "marantz", "onkyo", "yamaha", "harman", "jbl")

/**
 * Icon key for an HDMI tile, from its name: the CEC device name, or the name the user gave the input in TV
 * settings. Android doesn't give apps the CEC device type, so the name is all there is. One brand word covers
 * the family ("PlayStation" is PS3 to PS5). [named]: a CEC device, which is a player when nothing else matches.
 */
internal fun hdmiIcon(name: String, named: Boolean): String {
    val n = name.lowercase()
    return when {
        "playstation" in n -> "playstation"
        "xbox" in n -> "xbox"
        "nintendo" in n || "switch" in n -> "nintendoswitch"
        "apple tv" in n || "appletv" in n -> "appletv"
        SET_TOP_WORDS.any { it in n } -> "settop"
        named -> "playback"
        else -> "hdmi"
    }
}

/** Soundbars and AV receivers name themselves over CEC too, but there's nothing to switch to. */
internal fun isAudioDevice(name: String) = name.lowercase().let { n -> AUDIO_WORDS.any { it in n } }

/** A plain HDMI port (no CEC name) is in the home row only while it has a signal, unless it was pinned by hand. */
internal fun inRow(pkg: String, offline: Boolean, hidden: Set<String>, pinned: Set<String>) =
    pkg !in hidden && (!offline || pkg in pinned)

/**
 * Links to the same picture at screen size, for image servers that take the size in the link. Apps often hand the
 * launcher a thumbnail-sized link; these servers resize on request, so asking for [w] px gets a sharp picture.
 * Covers Cloudinary-style transforms (w_384,h_216: JioHotstar, SonyLIV and many others), Amazon's image service
 * suffixes (._V1_SX300_.jpg: Prime Video), YouTube thumbnail names, and width/height query parameters.
 */
internal fun hiResArt(u: String, w: Int): List<String> {
    if (!u.startsWith("http")) return emptyList()
    val h = w * 9 / 16
    val out = LinkedHashSet<String>()
    val cw = Regex("""([/,])w_\d+""")
    if (cw.containsMatchIn(u)) out += u.replace(cw, "$1w_$w").replace(Regex("""([/,])h_\d+"""), "$1h_$h")
    val amazon = Regex("""\._[A-Za-z0-9_,\-]+_\.(jpg|jpeg|png|webp)""", RegexOption.IGNORE_CASE)
    if ("media-amazon.com" in u || "ssl-images-amazon.com" in u) out += u.replace(amazon, ".$1")
    val cr = Regex("""/imgsrv/display/thumbnail/\d+x\d+/""")
    if (cr.containsMatchIn(u)) out += u.replace(cr, "/imgsrv/display/thumbnail/${w}x$h/")
    if ("ytimg.com" in u) out += u.replace(Regex("""/(hq|mq|sd|)default(_live)?\."""), "/maxresdefault.")
    val qw = Regex("""([?&](?:w|width|imwidth|resize_w))=\d+""")
    if (qw.containsMatchIn(u)) out += u.replace(qw, "$1=$w").replace(Regex("""([?&](?:h|height|resize_h))=\d+"""), "$1=$h")
    out -= u
    return out.toList()
}

/** Allowed to start an activity from the background because the Home button service is bound. */
class Updated : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        // Installing over Plume unbinds its Home service, which marks it "turned off by the user". It wasn't:
        // clear that, so the new version turns the service back on when it opens.
        c.getSharedPreferences("home", Context.MODE_PRIVATE).edit().remove("home_off").commit()
        c.startActivity(Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

class MainActivity : Activity() {

    /** [offline]: an HDMI port with no signal right now (see inRow). */
    private class App(val pkg: String, val label: String, val launch: Intent, val icon: Drawable, val offline: Boolean = false, val banner: Drawable? = null)
    /** [art]: the widest, largest picture the app gave, for the full-screen Top Shelf. */
    private class Prog(val title: String, val sub: String, val progress: Int, val image: String?, val intent: String?, val pkg: String?, val art: String? = image, val alt: String? = null)

    private val prefs by lazy { getSharedPreferences("home", MODE_PRIVATE) }
    private val io = Executors.newSingleThreadExecutor()
    // Posters get their own threads: one slow image server must not hold up the rest (or the row data).
    private val posterIo = Executors.newFixedThreadPool(3)
    private val loading = java.util.Collections.synchronizedSet(HashSet<String>())
    private val ui = Handler(Looper.getMainLooper())
    private val posters = object : LruCache<String, Bitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    // Kept across activity rebuilds (theme switch), so the new home screen shows its apps at once.
    private var apps = APPS ?: emptyList()
    private var appsDirty = APPS == null
    private var watchKey: String? = null
    private var lastTop: View? = null // last focused view in each home row, so Up/Down return to it
    private var lastApp: View? = null
    private var editing = false
    private var grabbed: View? = null
    private var holdFired = false
    private var dark = false
    private var fg = Color.BLACK
    private var ground = Color.WHITE

    private lateinit var appRow: LinearLayout
    private lateinit var backdrop: Backdrop
    private lateinit var glass: GlassView
    private lateinit var shelf: LinearLayout
    private lateinit var dock: FrameLayout
    private lateinit var lens: FocusLens
    private lateinit var topGlass: GlassView
    private var tintSource: Bitmap? = null // the small backdrop copy the text colours are taken from
    private val tintAnims = HashMap<View, ValueAnimator>()
    private lateinit var drawerGlass: GlassView
    private lateinit var playButtonGlass: GlassView
    private lateinit var sheetGlass: GlassView
    private var dockOpen = -1f // 0 compact, 1 open (lens and name room); -1 not laid out yet
    private var dockAnim: ValueAnimator? = null
    private val appsTitle by lazy { findViewById<TextView>(R.id.appsTitle) }
    // Glass layout state: the wallpaper (shown when there's nothing to continue), the Top Shelf position, and the
    // last Left/Right press (dock tiles tilt in from that side).
    private var wallpaperBmp: Bitmap? = null
    private var wallpaperSmall: Bitmap? = null
    private var shelfAt = 0
    private var shelfSwap = 0 // bumps on every page turn, so a late text swap from an older turn is skipped
    private var lastDir = 0
    private var lastKeyAt = 0L
    /** Full-screen artwork with its small copy for the glass, by URI. ~8 MB a picture at 1080p. */
    private val heroes = object : LruCache<String, Pair<Bitmap, Bitmap>>(44 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Pair<Bitmap, Bitmap>) = value.first.byteCount + value.second.byteCount
    }
    private val heroWaiters = HashMap<String, MutableList<(Pair<Bitmap, Bitmap>) -> Unit>>() // UI thread only
    private lateinit var allApps: View
    private lateinit var allAppsGrid: GridLayout
    private lateinit var wifi: ImageView

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            appsDirty = true
        }
    }

    private val net = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
            ui.post {
                showNet(caps)
                // Online again (e.g. Wi-Fi coming up after boot): fetch any posters that failed meanwhile.
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) retryPosters()
            }
        }

        override fun onLost(n: Network) {
            ui.post { showNet(null) }
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        prefs.edit().remove("dbg").apply() // left over from the benchmark switches
        // The backdrop covers the whole window: no window background painted under it on every frame.
        window.setBackgroundDrawable(null)
        setContentView(R.layout.activity_main)
        appRow = findViewById(R.id.appRow)
        allApps = findViewById(R.id.allApps)
        allAppsGrid = findViewById(R.id.allAppsGrid)
        wifi = findViewById(R.id.wifi)
        applyTheme()
        setupGlass()
        loadWallpaper()

        // Top bar: network, All apps, Android settings, launcher settings. Round, lit on focus.
        for ((id, action) in listOf<Pair<Int, () -> Unit>>(
            R.id.wifi to { openSettings(Settings.ACTION_WIFI_SETTINGS) },
            R.id.allAppsButton to { openAllApps() },
            R.id.systemSettings to { openSettings(Settings.ACTION_SETTINGS) },
            R.id.launcherSettings to { unlock { openLauncherSettings() } },
        )) findViewById<ImageView>(id).apply {
            setColorFilter(fg)
            // Small glass circles; focused, they turn solid white with a dark icon (tvOS).
            val ring = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x00FFFFFF)
            }
            background = ring
            setOnFocusChangeListener { v, focused ->
                if (focused) lastTop = v
                ValueAnimator.ofArgb(if (focused) 0x00FFFFFF else 0xFFF5F5F7.toInt(), if (focused) 0xFFF5F5F7.toInt() else 0x00FFFFFF).apply {
                    duration = Motion.FOCUS_MS
                    addUpdateListener { ring.setColor(it.animatedValue as Int) }
                    start()
                }
                setColorFilter(if (focused) 0xFF0A0A0A.toInt() else fg)
                animate().scaleX(if (focused) 1.12f else 1f).scaleY(if (focused) 1.12f else 1f)
                    .setDuration(Motion.FOCUS_MS).setInterpolator(Motion.EMPHASIZED).start()
            }
            setOnClickListener { action() }
        }
        if (prefs.getBoolean("reopen_settings", false)) {
            prefs.edit().remove("reopen_settings").apply()
            ui.post { openLauncherSettings(R.string.theme) }
        } else if (!homeServiceOn() && !prefs.getBoolean("setup_shown", false)) {
            // Fresh install: walk through making Home land on Plume, the one step that has to happen by hand.
            prefs.edit().putBoolean("setup_shown", true).apply()
            ui.post { startHomeSetup() }
        } else if (notesUnseen()) {
            ui.post { whatsNewCard() } // after an update; the self-updater reopens Plume, so it's seen right away
        }

        registerReceiver(packageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })
        // Application context: ConnectivityManager keeps its first instance (and that instance's context) in a
        // static callback handler forever, so an Activity context here leaks the whole first home screen.
        applicationContext.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(net)
        getSystemService(TvInputManager::class.java)?.registerCallback(inputCallback, ui)

        if (checkSelfPermission(TV_LISTINGS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(TV_LISTINGS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        ensureHomeService()
        homeSetupResumed()
        if (wantDark() != dark) {
            recreate()
            return
        }
        // Only rebuild what changed, so focus stays where it was when returning from an app.
        if (appsDirty) refreshApps() else if (appRow.childCount == 0) renderApps()
        refreshWatchNext()
        scheduleShelfTick()
        refreshNews()
        showWeather()
        ui.removeCallbacks(weatherTick)
        ui.post(weatherTick)
        ui.postDelayed(themeTick, 60_000L)
        ui.removeCallbacks(wallpaperTick)
        ui.post(wallpaperTick)
        // Gallery chosen but its paintings missing (updated from a version that bundled them): fetch them.
        if (galleryOn() && photoScreensaver() == null && !paintingsReady(this)) downloadPaintings {}
        checkUpdate(force = false)
        Bing.onChanged = bingChanged
        Bing.refresh(applicationContext, prefs)
        ui.post { updateBadge() } // after layout, so the icon has its size
    }

    /** Auto theme flips at 7:00 and 19:00 even while the home screen just sits there. */
    private val themeTick = object : Runnable {
        override fun run() {
            if (wantDark() != dark) recreate() else ui.postDelayed(this, 60_000L)
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        if (code != MEDIA_REQUEST) return refreshWatchNext()
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) afterPhotoAccess?.invoke()
        else Toast.makeText(this, getString(R.string.photos_permission), Toast.LENGTH_LONG).show()
    }

    @Deprecated("Only used for the default-home role request")
    override fun onActivityResult(code: Int, result: Int, data: Intent?) {
        if (code == ROLE_REQUEST && settingsScrim.visibility == View.VISIBLE) renderSettings(R.string.default_launcher)
        if (code == ROLE_REQUEST && setupStep == 1) {
            setupStep = 0
            afterHomeRole()
        }
    }

    override fun onPause() {
        ui.removeCallbacks(weatherTick)
        ui.removeCallbacks(themeTick)
        ui.removeCallbacks(wallpaperTick)
        ui.removeCallbacks(shelfTick)
        if (editing) endEdit()
        super.onPause()
    }

    override fun onStop() {
        unlockedUntil = 0L // leaving the home screen locks it again (onPause would also fire for permission dialogs)
        if (homeTest != 0) leftDuringTest = true
        super.onStop()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        posters.evictAll()
        heroes.evictAll()
    }

    override fun onDestroy() {
        if (Bing.onChanged === bingChanged) Bing.onChanged = null
        unregisterReceiver(packageReceiver)
        applicationContext.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(net)
        getSystemService(TvInputManager::class.java)?.unregisterCallback(inputCallback)
        upload?.stop()
        io.shutdownNow()
        posterIo.shutdownNow()
        super.onDestroy()
    }

    /**
     * Android drops our Home service from the enabled accessibility services whenever the app is
     * force-stopped, and then Home lands on Google TV. Put it back (needs the adb-granted permission),
     * unless the user turned it off.
     */
    private fun ensureHomeService() {
        if (homeServiceOn()) return prefs.edit().remove("home_off").apply() // what Android says wins
        if (prefs.getBoolean("home_off", false) || checkSelfPermission(WRITE_SECURE) != PackageManager.PERMISSION_GRANTED) return
        val me = ComponentName(this, HomeOverride::class.java).flattenToString()
        val on = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        Settings.Secure.putString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, if (on.isEmpty()) me else "$on:$me")
        Settings.Secure.putInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
    }

    /** Compared as components: Settings may store us in short form (dev.plume/.HomeOverride). */
    private fun homeServiceOn(): Boolean {
        val me = ComponentName(this, HomeOverride::class.java)
        return Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            .split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    private fun toggleHomeButton() = if (homeServiceOn()) turnOffHomeButton() else turnOnHomeButton()

    /** Off hands Home back to the TV's own launcher, and Plume leaves it off until switched on again. */
    private fun turnOffHomeButton() {
        prefs.edit().putBoolean("home_off", true).apply()
        HomeOverride.running?.disableSelf()
        if (homeServiceOn() && checkSelfPermission(WRITE_SECURE) == PackageManager.PERMISSION_GRANTED) {
            val me = ComponentName(this, HomeOverride::class.java)
            val on = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            Settings.Secure.putString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                on.split(':').filter { ComponentName.unflattenFromString(it) != me }.joinToString(":"))
        }
        if (homeServiceOn()) {
            Toast.makeText(this, getString(R.string.home_off_hint), Toast.LENGTH_LONG).show()
            openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        }
        renderSettings(R.string.home_button)
    }

    /** The Home key service is an accessibility service, which Android only lets the user switch on. */
    private fun turnOnHomeButton() {
        prefs.edit().remove("home_off").apply()
        ensureHomeService() // works where the adb permission was granted
        if (homeServiceOn()) return renderSettings(R.string.home_button)
        // The written path is the safety net: some Google TVs (a Xiaomi stick) answer the Accessibility link
        // with "You don't have an app that can do this", which we can't detect from here.
        Toast.makeText(this, getString(R.string.home_on_hint), Toast.LENGTH_LONG).show()
        openHomeServiceSettings()
    }

    /** False when no Settings screen opened at all. */
    private fun openHomeServiceSettings(): Boolean {
        val service = ComponentName(this, HomeOverride::class.java).flattenToString()
        val tries = listOf(
            // Straight to our own toggle (Android 12+, where the TV supports it), then the list, then Settings.
            Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").putExtra(Intent.EXTRA_COMPONENT_NAME, service),
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in tries) {
            try {
                startActivity(intent) // not resolveActivity(): Android 11+ hides other apps from that
                return true
            } catch (e: Exception) {
            }
        }
        return false
    }

    private fun isDefaultHome() = Build.VERSION.SDK_INT >= 29 &&
        getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_HOME)

    /**
     * Asks Android to make us the default home app (its own confirmation dialog). On Google TV the system
     * launcher still wins Home intents, which is why the Home button service exists; on other Android TVs
     * this alone is enough.
     */
    private fun makeDefaultHome() {
        val roles = if (Build.VERSION.SDK_INT >= 29) getSystemService(RoleManager::class.java) else null
        if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME) && !roles.isRoleHeld(RoleManager.ROLE_HOME)) {
            @Suppress("DEPRECATION")
            startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_HOME), ROLE_REQUEST)
        } else {
            openSettings(Settings.ACTION_HOME_SETTINGS) // Android lets apps ask for Home, not give it back
        }
    }

    // ---------- Home setup ----------
    // Popups that try each way of making Home land on Plume, stopping at the first that works: the default-home
    // role, then the Home button service, then adb help for TVs that grey out the service (Android 13+
    // "restricted settings" for sideloaded apps). Each way is checked by asking for a Home press: one that
    // reaches us arrives as a Home intent; one that doesn't sends us to the background.

    private var setupDialog: Dialog? = null
    private var setupStep = 0 // 1 role dialog open, 2 Accessibility settings open
    private var homeTest = 0 // the way being checked (1 role, 2 service) while waiting for a Home press
    private var leftDuringTest = false
    private var previewAt = -1 // debug builds: which popup the preview is on, -1 when not previewing

    private fun startHomeSetup() {
        val roles = if (Build.VERSION.SDK_INT >= 29) getSystemService(RoleManager::class.java) else null
        if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME) && !roles.isRoleHeld(RoleManager.ROLE_HOME)) rolePopup()
        else afterHomeRole()
    }

    private fun afterHomeRole() = if (isDefaultHome() || homeServiceOn()) testPopup(1) else tryHomeService()

    private fun tryHomeService() {
        prefs.edit().remove("home_off").apply()
        ensureHomeService() // works where the adb permission was granted
        if (homeServiceOn()) testPopup(2) else servicePopup()
    }

    /** Back from the role dialog, Accessibility settings, or another home screen after a Home press. */
    private fun homeSetupResumed() {
        if (setupStep == 2) {
            setupStep = 0
            if (homeServiceOn()) testPopup(2) else adbPopup()
        } else if (homeTest != 0 && leftDuringTest) {
            val failed = homeTest
            homeTest = 0
            if (failed == 1) tryHomeService() else adbPopup()
        }
    }

    private fun rolePopup() = homePopup(1, getString(R.string.setup_role_title), getString(R.string.setup_role_body), null,
        getString(R.string.setup_continue) to {
            setupStep = 1
            @Suppress("DEPRECATION")
            startActivityForResult(getSystemService(RoleManager::class.java).createRequestRoleIntent(RoleManager.ROLE_HOME), ROLE_REQUEST)
        },
        getString(R.string.setup_not_now) to { setupDialog?.dismiss() })

    private fun testPopup(way: Int) {
        if (previewAt < 0) {
            homeTest = way
            leftDuringTest = false
        }
        homePopup(way, getString(R.string.setup_test_title), getString(R.string.setup_test_body), null,
            getString(R.string.setup_skip_check) to { homeTest = 0; if (way == 1) tryHomeService() else adbPopup() })
    }

    private fun servicePopup() = homePopup(2, getString(R.string.setup_service_title), getString(R.string.setup_service_body), null,
        getString(R.string.setup_open_accessibility) to {
            setupStep = 2
            if (!openHomeServiceSettings()) {
                setupStep = 0
                adbPopup()
            }
        },
        getString(R.string.setup_not_now) to { setupDialog?.dismiss() })

    /** The one-time grant lets Plume switch the service on itself, past the greyed-out toggle. */
    private fun adbPopup() {
        val mono = Typeface.MONOSPACE
        val code = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(if (dark) DARK_BG else 0xFFE9EBEF.toInt())
            }
            listOfNotNull(lanAddress()?.let { "adb connect $it:5555" }, "adb shell pm grant $packageName $WRITE_SECURE").forEach {
                addView(label(it, 12f).apply { typeface = mono; maxLines = 2; ellipsize = null; setPadding(0, dp(2), 0, dp(2)) })
            }
        }
        val qr = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(ImageView(context).apply { setImageBitmap(qrBitmap(HELP_URL, dp(108))) }, LinearLayout.LayoutParams(dp(108), dp(108)))
            addView(label(getString(R.string.setup_scan_help), 11f).apply { alpha = 0.6f; setPadding(0, dp(6), 0, 0) })
        }
        val extra = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
            addView(code, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(qr, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(20) })
        }
        homePopup(3, getString(R.string.setup_adb_title), getString(R.string.setup_adb_body), extra,
            getString(R.string.setup_try_again) to { tryHomeService() },
            getString(R.string.setup_close) to { setupDialog?.dismiss() })
    }

    /**
     * Once after an update: the first three CHANGELOG.md lines of this version, so put the best ones first.
     * Closing it counts as reading the notes, which clears the dot on the Plume settings icon.
     */
    private fun whatsNewCard() {
        prefs.edit().putString("seen_notes", myVersion()).apply()
        updateBadge()
        val notes = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            getString(R.string.whats_new).split('\n').take(3).forEach {
                addView(label("", 15f).apply {
                    text = SpannableString(it).apply { setSpan(BulletSpan(dp(10), fg), 0, it.length, 0) }
                    maxLines = Int.MAX_VALUE
                    ellipsize = null
                    setLineSpacing(0f, 1.15f)
                    setPadding(0, dp(12), 0, 0)
                })
            }
        }
        homePopup(0, getString(R.string.whats_new_card_title, myVersion()), null, notes,
            getString(R.string.done) to { setupDialog?.dismiss() },
            getString(R.string.see_all_changes) to {
                setupDialog?.dismiss()
                unlock { openLauncherSettings(R.string.updates); showUpdates() }
            })
    }

    private fun donePopup() = homePopup(0, getString(R.string.setup_done_title),
        getString(if (homeServiceOn()) R.string.setup_done_body_service else R.string.setup_done_body), null,
        getString(R.string.setup_done) to { setupDialog?.dismiss() })

    /** A card in the middle of the screen over the dimmed home screen, in the hold menus' colours. */
    private fun homePopup(step: Int, title: String, body: String?, extra: View?, vararg buttons: Pair<String, () -> Unit>) {
        setupDialog?.let { setupDialog = null; it.dismiss() } // replaced, not closed: the dismiss listener leaves the flow alone
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(24), dp(28), dp(20))
            background = if (dark) rimCard(dp(16), false) else GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(0xFFF7F8FA.toInt())
            }
            if (step > 0) addView(label(getString(R.string.setup_step, step), 12f).apply { secondary(0.6f) })
            addView(label(title, 22f).apply { setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(4), 0, 0) })
            body?.let { addView(label(it, 14f).apply { maxLines = 6; secondary(0.8f); setLineSpacing(0f, 1.15f); setPadding(0, dp(10), 0, 0) }) }
            extra?.let { addView(it) }
            buttons.forEachIndexed { i, (name, action) ->
                addView(settingsRow(name, "") { if (previewAt >= 0) previewHomeSetup(previewAt + 1) else action() },
                    LinearLayout.LayoutParams(MATCH_PARENT, dp(44)).apply { topMargin = if (i == 0) dp(20) else dp(4) })
            }
        }
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(card)
        dialog.window?.run {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(dp(if (extra != null) 600 else 480), ViewGroup.LayoutParams.WRAP_CONTENT)
            setDimAmount(0.6f)
        }
        dialog.setOnDismissListener {
            if (setupDialog !== dialog) return@setOnDismissListener
            setupDialog = null // closed with Back or a button: setup stops here, and Settings can start it again
            setupStep = 0
            homeTest = 0
            previewAt = -1
        }
        setupDialog = dialog
        dialog.show()
        card.getChildAt(card.childCount - buttons.size).requestFocus()
    }

    /** Debug builds: every popup in turn, with buttons that only move on, to check the look on a TV. */
    private fun previewHomeSetup(at: Int = 0) {
        previewAt = at
        when (at) {
            0 -> rolePopup()
            1 -> testPopup(1)
            2 -> servicePopup()
            3 -> adbPopup()
            4 -> donePopup()
            else -> setupDialog?.dismiss()
        }
    }

    /** Home pressed while already home: close whatever is open and go back to the start of the apps row. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == UPDATE_STATUS) return installStatus(intent)
        if (homeTest != 0 && intent.hasCategory(Intent.CATEGORY_HOME)) { // the Home press reached us: setup works
            homeTest = 0
            return donePopup()
        }
        if (editing) endEdit()
        if (settingsScrim.visibility == View.VISIBLE) closeLauncherSettings()
        if (allApps.visibility == View.VISIBLE) closeAllApps()
        appRow.getChildAt(0)?.requestFocus()
    }

    @Deprecated("Home screen: Back only closes overlays")
    override fun onBackPressed() {
        when {
            settingsScrim.visibility == View.VISIBLE -> pageBack?.invoke() ?: closeLauncherSettings()
            allApps.visibility == View.VISIBLE -> closeAllApps()
        }
    }

    // ---------- theme ----------

    private fun wantDark() = true // the glass layout is dark only: white text on pictures

    private fun applyTheme() {
        dark = wantDark()
        fg = if (dark) DARK_TEXT else 0xFF111214.toInt()
        // Must match the colour each wallpaper fades into, or the bottom edge shows a seam.
        ground = if (dark) DARK_BG else 0xFFF3F5F7.toInt()
        findViewById<View>(R.id.root).background = null // the backdrop paints black itself when it has no picture
        for (id in intArrayOf(R.id.clock, R.id.date, R.id.appsTitle, R.id.shelfSource, R.id.shelfTitle, R.id.shelfSub, R.id.allAppsTitle, R.id.rowLabel, R.id.weather)) {
            findViewById<TextView>(id).setTextColor(fg)
        }
        wifi.setColorFilter(fg)
    }

    // ---------- wallpapers ----------
    // Bundled ones have their fade baked in. Your own go in Android/data/dev.plume/files/wallpapers/
    // (adb push, no permission needed) and get the fade painted once when loaded. A folder on the TV (MediaStore,
    // so USB drives too) shows its photos in shuffled turns, one decoded at a time.
    // Each theme remembers its own pick. Decoded off the UI thread, max 1080p (~8 MB). Full colour, not RGB_565:
    // 565 rounds a smooth sky's 1-2 level JPEG ripples into visible grey and green blocks.

    private fun wallpaperDir() = getExternalFilesDir("wallpapers")

    /** A plain choice first (just the theme's base colour, no image), then pictures. */
    private fun wallpaperChoices(forDark: Boolean): List<Pair<String, String>> =
        listOf(plainWallpaper(forDark).let { wallpaperName(it) to it }) +
            BUNDLED_WALLPAPERS.keys.map { wallpaperName("res:$it") to "res:$it" } +
            (wallpaperName(Bing.CHOICE) to Bing.CHOICE) +
            (wallpaperDir()?.listFiles { f -> f.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp") }
                ?.sortedBy { it.name }?.map { it.nameWithoutExtension to "file:${it.name}" } ?: emptyList()) +
            listOfNotNull(currentWallpaper(forDark).takeIf { it.startsWith("folder:") }?.let { wallpaperName(it) to it })

    // Light gets a soft white (the light ground colour), not #FFFFFF: pure white glares on a TV.
    private fun plainWallpaper(forDark: Boolean) = if (forDark) "plain:Pure black" else "plain:Soft white"

    private fun defaultWallpaper(forDark: Boolean) = if (forDark) "res:Rockies (dusk)" else "res:Rockies (day)"

    private fun currentWallpaper(forDark: Boolean = dark) =
        prefs.getString(if (forDark) "wp_dark" else "wp_light", null) ?: defaultWallpaper(forDark)

    /** Fade your own photos into the background at the bottom. Bundled wallpapers have it baked in either way. */
    private fun wallpaperFade() = prefs.getBoolean("wp_fade", true)

    private fun wallpaperName(choice: String) = when {
        choice == Bing.CHOICE -> getString(R.string.bing_daily)
        choice.startsWith("folder:") -> getString(R.string.folder_name, choice.substringAfter(':').substringAfter(':'))
        else -> choice.substringAfter(':').substringBeforeLast('.').let { n -> WALLPAPER_NAMES[n]?.let { getString(it) } ?: n }
    }

    /** The wallpaper is the backdrop whenever Continue Watching is empty (the Top Shelf's fallback). */
    private fun loadWallpaper() {
        val choice = currentWallpaper()
        if (choice.startsWith("plain:")) { // the root's ground colour shows through
            wallpaperBmp = null
            wallpaperSmall = null
            if (watchList.isEmpty()) showBackdrop(null, null)
            return
        }
        val fallback = defaultWallpaper(dark)
        io.execute {
            val bmp = try {
                decodeWallpaper(choice)
            } catch (e: Exception) {
                null
            } catch (e: OutOfMemoryError) { // a picture on a TV short of memory: show the default instead of crashing
                null
            } ?: decodeWallpaper(fallback)
            val small = bmp?.let { smallCopy(it) }
            ui.post {
                wallpaperBmp = bmp
                wallpaperSmall = small
                if (watchList.isEmpty()) showBackdrop(bmp, small)
            }
        }
    }

    private fun decodeWallpaper(choice: String): Bitmap? {
        BUNDLED_WALLPAPERS[choice.removePrefix("res:")]?.let { res ->
            return BitmapFactory.decodeResource(resources, res)
        }
        // fitWallpaper's bitmap is mutable: the fades are painted onto it.
        val bmp = when {
            choice == Bing.CHOICE -> fitWallpaper { Bing.file(this).inputStream() }
            choice.startsWith("folder:") -> {
                val uri = ContentUris.withAppendedId(PHOTOS, folderPhoto(choice) ?: return null)
                fitWallpaper { contentResolver.openInputStream(uri) ?: throw IOException("gone: $uri") }
            }
            else -> {
                val file = File(wallpaperDir(), choice.removePrefix("file:"))
                fitWallpaper { file.inputStream() }
            }
        } ?: return null
        // Bing's pictures are often bright daylight, which fights true black: dim them in dark mode.
        if (dark && choice == Bing.CHOICE) Canvas(bmp).drawColor(Color.argb(DARK_DIM, 0, 0, 0))
        // Same fade as the bundled ones: clear above 28% height, into the background colour by 50%.
        val h = bmp.height.toFloat()
        val base = ground and 0x00FFFFFF
        if (wallpaperFade()) Canvas(bmp).drawRect(0f, 0f, bmp.width.toFloat(), h, Paint().apply {
            isDither = true
            shader = LinearGradient(0f, h * 0.28f, 0f, h * 0.5f, base, base or 0xE6000000.toInt(), Shader.TileMode.CLAMP)
        })
        // Photos rarely have the bundled ones' empty sky: a soft veil behind the clock and top-bar icons keeps them readable.
        // Strong over the clock and icons (top ~20%), gone by 30% so the photo still shows above the apps.
        Canvas(bmp).drawRect(0f, 0f, bmp.width.toFloat(), h * 0.3f, Paint().apply {
            isDither = true
            shader = LinearGradient(0f, 0f, 0f, h * 0.3f, intArrayOf(base or 0xC0000000.toInt(), base or 0xA0000000.toInt(), base),
                floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        })
        return bmp
    }

    /** Wallpapers page, framed by "Add from phone…" first and, once there are photos, "Remove a photo…" last. */
    private fun pickWallpaper(forDark: Boolean) {
        val row = if (forDark) R.string.dark_wallpaper else R.string.light_wallpaper
        val choices = wallpaperChoices(forDark)
        val own = choices.filter { it.second.startsWith("file:") }
        val current = currentWallpaper(forDark)
        val rows = listOf(page(getString(R.string.add_from_phone)) { uploadWallpaper(forDark) }, page(getString(R.string.choose_from_tv)) { pickFolder(forDark) }) +
            choices.map { (name, choice) -> page(name, if (choice == current) CHOSEN else "") { setWallpaper(forDark, choice); renderSettings(row) } } +
            if (own.isEmpty()) emptyList() else listOf(page(getString(R.string.remove_photo_row)) { removeWallpaper(own, forDark) })
        showPage(if (forDark) getString(R.string.dark_wallpaper) else getString(R.string.light_wallpaper), rows, 2 + choices.indexOfFirst { it.second == current }) {
            renderSettings(row)
        }
    }

    private fun setWallpaper(forDark: Boolean, choice: String) {
        prefs.edit().putString(if (forDark) "wp_dark" else "wp_light", choice)
            .remove(photoKey(forDark)).putLong(photoAtKey(forDark), System.currentTimeMillis()).apply()
        Bing.dropIfUnused(applicationContext, prefs) // choosing another wallpaper deletes the Bing picture at once
        if (choice == Bing.CHOICE) Bing.refresh(applicationContext, prefs, force = true)
        if (forDark == dark) loadWallpaper()
    }

    private val bingChanged: () -> Unit = { if (currentWallpaper() == Bing.CHOICE) loadWallpaper() }

    // ---------- folder wallpapers ----------

    private var afterPhotoAccess: (() -> Unit)? = null // what asked, while Android's permission prompt is up
    private val deck = ArrayDeque<Long>() // photos not shown yet this round; io thread only

    private fun photoKey(forDark: Boolean = dark) = if (forDark) "wp_photo_dark" else "wp_photo_light"
    private fun photoAtKey(forDark: Boolean = dark) = if (forDark) "wp_photo_at_dark" else "wp_photo_at_light"
    private fun photoEvery() = prefs.getInt("wp_every", 30)

    private fun pickPhotoEvery() {
        val rows = PHOTO_EVERY.map { (minutes, name) ->
            page(getString(name), if (minutes == photoEvery()) CHOSEN else "") {
                prefs.edit().putInt("wp_every", minutes).apply()
                ui.removeCallbacks(wallpaperTick)
                ui.post(wallpaperTick)
                renderSettings(R.string.folder_photo_changes)
            }
        }
        showPage(getString(R.string.folder_photo_changes), rows, PHOTO_EVERY.keys.indexOf(photoEvery())) { renderSettings(R.string.folder_photo_changes) }
    }

    /** While the home screen shows, a folder wallpaper moves on to its next photo when its time is up. */
    private val wallpaperTick = object : Runnable {
        override fun run() {
            val every = photoEvery() * 60_000L
            if (!currentWallpaper().startsWith("folder:")) return
            val wait = prefs.getLong(photoAtKey(), 0) + every - System.currentTimeMillis()
            if (wait <= 0) nextPhoto()
            ui.postDelayed(this, if (wait <= 0) every else wait)
        }
    }

    private fun nextPhoto() {
        prefs.edit().remove(photoKey()).putLong(photoAtKey(), System.currentTimeMillis()).apply()
        loadWallpaper()
    }

    /** The folder's photo on show now, or the next one in this round's shuffle. Runs on the io thread. */
    private fun folderPhoto(choice: String): Long? {
        val ids = folderPhotos(contentResolver, choice)
        val all = ids.toHashSet()
        prefs.getLong(photoKey(), -1).takeIf { it in all }?.let { return it }
        deck.removeAll { it !in all } // deleted, or another folder's
        if (deck.isEmpty()) deck.addAll(ids.shuffled())
        return deck.removeFirstOrNull()?.also { prefs.edit().putLong(photoKey(), it).apply() }
    }

    /** Every folder with photos in it, internal storage and USB drives alike: (bucket id, name, photo count). */
    private fun photoFolders(): List<Triple<String, String, Int>> {
        val found = LinkedHashMap<String, Pair<String, Int>>()
        contentResolver.query(PHOTOS, arrayOf(MediaStore.Images.Media.BUCKET_ID, MediaStore.Images.Media.BUCKET_DISPLAY_NAME),
            null, null, "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} COLLATE NOCASE")?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                found[id] = (c.getString(1) ?: "Photos") to (found[id]?.second ?: 0) + 1
            }
        }
        return found.map { (id, v) -> Triple(id, v.first, v.second) }
    }

    private fun pickFolder(forDark: Boolean) = pickFolder({ pickWallpaper(forDark) }) { choice ->
        setWallpaper(forDark, choice)
        renderSettings(if (forDark) R.string.dark_wallpaper else R.string.light_wallpaper)
    }

    /** Folders page for wallpapers and the screensaver; [onPick] gets "folder:<bucket id>:<name>". */
    private fun pickFolder(back: () -> Unit, onPick: (String) -> Unit) {
        if (checkSelfPermission(READ_PHOTOS) != PackageManager.PERMISSION_GRANTED) {
            afterPhotoAccess = { pickFolder(back, onPick) }
            return requestPermissions(arrayOf(READ_PHOTOS), MEDIA_REQUEST)
        }
        val folders = photoFolders()
        if (folders.isEmpty()) {
            return Toast.makeText(this, getString(R.string.no_photos), Toast.LENGTH_LONG).show()
        }
        val rows = folders.map { (id, name, n) ->
            page(name, resources.getQuantityString(R.plurals.photo_count, n, n)) { onPick("folder:$id:$name") }
        }
        showPage(getString(R.string.choose_folder), rows, back = back)
    }

    private fun removeWallpaper(own: List<Pair<String, String>>, forDark: Boolean) {
        showPage(getString(R.string.remove_photo_title), own.map { (name, choice) ->
            page(name) {
                showPage(getString(R.string.delete_photo, name), listOf(
                    page(getString(R.string.cancel)) { removeWallpaper(own, forDark) },
                    page(getString(R.string.delete)) {
                        File(wallpaperDir(), choice.removePrefix("file:")).delete()
                        // A theme showing it falls back to its default.
                        prefs.edit().apply {
                            if (prefs.getString("wp_light", null) == choice) remove("wp_light")
                            if (prefs.getString("wp_dark", null) == choice) remove("wp_dark")
                        }.apply()
                        loadWallpaper()
                        pickWallpaper(forDark)
                    },
                )) { removeWallpaper(own, forDark) }
            }
        }) { pickWallpaper(forDark) }
    }

    private var upload: UploadServer? = null

    /**
     * "Add from phone": a QR code for a page served from this TV. The phone posts a photo, we turn it into a
     * wallpaper and show it at once. The server runs only while this panel is open.
     */
    private fun uploadWallpaper(forDark: Boolean) {
        val ip = lanAddress() ?: return Toast.makeText(this, getString(R.string.connect_network), Toast.LENGTH_LONG).show()
        val dir = wallpaperDir() ?: return
        val status = label(getString(R.string.waiting_photo), 14f).apply { gravity = Gravity.CENTER; maxLines = 2 }
        val server = try {
            UploadServer((1000..9999).random().toString(), File(cacheDir, "upload.tmp")) { file ->
                val saved = importWallpaper(file, dir) ?: return@UploadServer null
                ui.post {
                    setWallpaper(forDark, "file:${saved.name}")
                    status.text = getString(R.string.photo_added, saved.nameWithoutExtension)
                }
                saved.nameWithoutExtension
            }
        } catch (e: IOException) {
            return Toast.makeText(this, getString(R.string.upload_failed), Toast.LENGTH_LONG).show()
        }
        upload = server
        val url = "http://$ip:${server.port}/${server.code}"
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(4), 0, dp(12))
            addView(ImageView(this@MainActivity).apply { setImageBitmap(qrBitmap(url, dp(200))) })
            addView(label(url, 14f).apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(4)) })
            addView(label(getString(R.string.scan_hint), 12f).apply {
                gravity = Gravity.CENTER
                alpha = 0.6f
                maxLines = 3
            })
            addView(status.apply { setPadding(0, dp(12), 0, 0) })
        }
        showPage(getString(R.string.add_from_your_phone), listOf(page(getString(R.string.done)) { pickWallpaper(forDark) }), top = box) { pickWallpaper(forDark) }
        pageExit = {
            server.stop()
            if (upload === server) upload = null
        }
    }

    /** The TV's own address on the home network (IPv4: easy to read and type). */
    private fun lanAddress(): String? {
        val cm = getSystemService(ConnectivityManager::class.java)
        return cm.getLinkProperties(cm.activeNetwork)?.linkAddresses?.map { it.address }
            ?.firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress }?.hostAddress
    }

    /** Black on white with the standard 4-module quiet zone, whole pixels per module so it stays sharp. */
    private fun qrBitmap(text: String, maxPx: Int): Bitmap {
        val m = Qr.encode(text)
        val n = m.size + 8
        val px = maxOf(1, maxPx / n)
        val bmp = Bitmap.createBitmap(n * px, n * px, Bitmap.Config.RGB_565)
        bmp.eraseColor(Color.WHITE)
        val canvas = Canvas(bmp)
        val paint = Paint().apply { color = Color.BLACK }
        for (r in m.indices) for (c in m.indices) if (m[r][c]) {
            canvas.drawRect(((c + 4) * px).toFloat(), ((r + 4) * px).toFloat(), ((c + 5) * px).toFloat(), ((r + 5) * px).toFloat(), paint)
        }
        return bmp
    }

    // ---------- launcher settings panel (top-bar button; the Settings tile is Android's) ----------

    private val settingsScrim by lazy { findViewById<View>(R.id.settingsScrim) }
    private val settingsList by lazy { findViewById<LinearLayout>(R.id.settingsList) }

    private fun openLauncherSettings(at: Int = lastSettings) {
        showPanel()
        renderSettings(at)
    }

    private fun showPanel() {
        panelFrom = currentFocus
        // Dark: true black, set apart from the home screen by a hairline on its left edge.
        findViewById<View>(R.id.settingsPanel).background = null // the glass sheet behind it (setupSheet) is its background
        findViewById<TextView>(R.id.settingsTitle).setTextColor(fg)
        settingsScrim.visibility = View.VISIBLE
        homeFocusable(false)
    }

    private fun closeLauncherSettings() {
        pageExit?.invoke()
        pageExit = null
        pageBack = null
        settingsScrim.visibility = View.GONE
        homeFocusable(allApps.visibility != View.VISIBLE) // the PIN can open over All apps
        (panelFrom?.takeIf { it.isAttachedToWindow } ?: findViewById(R.id.launcherSettings)).requestFocus()
        panelFrom = null
    }

    private var panelFrom: View? = null // focus to go back to when the panel closes

    /**
     * Plume settings, as a short list of sections that each open a page. [at] is the string id of the row to
     * focus: a row inside a section opens that section on it; a section or a top-level row focuses it in the
     * main list. Rows are found by name, so adding one never moves the others.
     */
    private fun renderSettings(at: Int = 0) {
        val theme = prefs.getString("theme", "light")!!
        val sort = prefs.getString("sort", "manual")!!
        val hidden = prefs.getStringSet("hidden_watch", emptySet())!!.size
        val weather = prefs.getString("weather", "off")!!
        val unit = if (weather == "off") prefs.getString("wx_unit", "c")!! else weather
        val cwOff = prefs.getStringSet("cw_off", emptySet())!!.count { pkg -> apps.any { it.pkg == pkg } } // uninstalled ones don't count
        val rowHidden = rowHiddenSet().count { pkg -> apps.any { it.pkg == pkg } }
        val names = rowNames()
        val colour = colourIcons()
        // Rows with more than two values also step back with Left; two-value rows flip either way.
        val steps = mutableMapOf<Int, (Int) -> Unit>(
            R.string.theme to { d ->
                prefs.edit().putString("theme", listOf("light", "dark", "auto").step(theme, d)).putBoolean("reopen_settings", true).apply()
                recreate() // colours are applied once per activity; reopen the panel after
            },
            R.string.row_position to { d ->
                prefs.edit().putString("row_align", ROW_ALIGN.keys.toList().step(rowAlign(), d)).apply()
                alignRow()
                renderSettings(R.string.row_position)
            },
            R.string.app_names to { d ->
                prefs.edit().putString("names", listOf("focus", "always", "never").step(names, d)).apply()
                renderApps()
                renderSettings(R.string.app_names)
            },
        )
        val rows = linkedMapOf<Int, Triple<String, String, () -> Unit>>(
            R.string.edit_apps_row to Triple(getString(R.string.edit_apps_row), "") { closeLauncherSettings(); startEdit(grab = null) },
            R.string.theme to Triple(getString(R.string.theme), getString(mapOf("light" to R.string.theme_light, "dark" to R.string.theme_dark, "auto" to R.string.theme_auto)[theme] ?: R.string.theme_light)) {
                steps[R.string.theme]!!(1)
            },
            R.string.light_wallpaper to Triple(getString(R.string.light_wallpaper), wallpaperName(currentWallpaper(false))) { pickWallpaper(false) },
            R.string.dark_wallpaper to Triple(getString(R.string.dark_wallpaper), wallpaperName(currentWallpaper(true))) { pickWallpaper(true) },
            R.string.photo_fade to Triple(getString(R.string.photo_fade), toggle(wallpaperFade())) {
                prefs.edit().putBoolean("wp_fade", !wallpaperFade()).apply()
                loadWallpaper()
                renderSettings(R.string.photo_fade)
            },
            R.string.folder_photo_changes to Triple(getString(R.string.folder_photo_changes), getString(PHOTO_EVERY[photoEvery()]!!)) { pickPhotoEvery() },
            R.string.sort_apps to Triple(getString(R.string.sort_apps), if (sort == "manual") getString(R.string.manual) else getString(R.string.most_used)) {
                prefs.edit().putString("sort", if (sort == "manual") "usage" else "manual").apply()
                renderApps()
                renderSettings(R.string.sort_apps)
            },
            R.string.apps_in_row to Triple(getString(R.string.apps_in_row), if (rowHidden == 0) getString(R.string.all) else getString(R.string.n_of_m, apps.size - rowHidden, apps.size)) { pickRowApps() },
            R.string.row_position to Triple(getString(R.string.row_position), getString(ROW_ALIGN[rowAlign()]!!.first)) {
                steps[R.string.row_position]!!(1)
            },
            R.string.app_names to Triple(getString(R.string.app_names), mapOf("focus" to getString(R.string.on_focus), "always" to getString(R.string.always), "never" to getString(R.string.never))[names]!!) {
                steps[R.string.app_names]!!(1)
            },
            R.string.icon_style to Triple(getString(R.string.icon_style), if (colour) getString(R.string.colour) else getString(R.string.monochrome)) {
                prefs.edit().putBoolean("colour_icons", !colour).apply()
                renderApps()
                renderSettings(R.string.icon_style)
            },
            R.string.restore_hidden to Triple(getString(R.string.restore_hidden), if (hidden == 0) getString(R.string.none_hidden) else getString(R.string.n_hidden, hidden)) {
                prefs.edit().remove("hidden_watch").apply()
                watchKey = null
                refreshWatchNext()
                renderSettings(R.string.restore_hidden)
            },
            R.string.cw_apps to Triple(getString(R.string.cw_apps), if (cwOff == 0) getString(R.string.all_on) else getString(R.string.n_off, cwOff)) { pickWatchSources() },
            // "weather" is off/c/f as before; "wx_unit" keeps the unit while it's off.
            R.string.weather to Triple(getString(R.string.weather), toggle(weather != "off")) {
                if (weather == "off") prefs.edit().putString("weather", unit).apply()
                else prefs.edit().putString("wx_unit", weather).putString("weather", "off").apply()
                showWeather()
                ui.removeCallbacks(weatherTick)
                ui.post(weatherTick)
                renderSettings(R.string.weather)
            },
            R.string.weather_units to Triple(getString(R.string.weather_units), if (unit == "f") "°F" else "°C") {
                val next = if (unit == "f") "c" else "f"
                prefs.edit().putString("wx_unit", next).putString("weather", if (weather == "off") "off" else next).apply()
                showWeather()
                renderSettings(R.string.weather_units)
            },
            R.string.weather_location to Triple(getString(R.string.weather_location), if (manualCity()) prefs.getString("geo_city", null) ?: "" else "Automatic") { pickCity() },
            R.string.screensaver to Triple(getString(R.string.screensaver), when {
                downloading -> getString(R.string.downloading_gallery)
                newsOn() -> getString(R.string.news)
                !galleryOn() -> screensaverName()
                else -> photoScreensaver()?.let { wallpaperName(it) } ?: getString(R.string.gallery)
            }) { pickScreensaver() },
            R.string.news_region to Triple(getString(R.string.news_region), News.edition(prefs).name) { pickEdition() },
            R.string.news_topics to Triple(getString(R.string.news_topics),
                News.topicsOn(prefs).joinToString(", ") { News.sectionName(it, prefs) }) { pickTopics() },
            R.string.news_local to Triple(getString(R.string.news_local), News.city(prefs) ?: getString(R.string.news_follows_weather)) { pickCity() },
            R.string.news_morning to Triple(getString(R.string.news_morning), toggle(prefs.getBoolean("news_morning", true))) {
                prefs.edit().putBoolean("news_morning", !prefs.getBoolean("news_morning", true)).apply()
                watchKey = null
                refreshWatchNext()
                renderSettings(R.string.news_morning)
            },
            R.string.news_screensaver to Triple(getString(R.string.news_screensaver), toggle(newsOn())) {
                if (newsOn()) stopGallery() else useNews()
                renderSettings(R.string.news_screensaver)
            },
            R.string.news_open to Triple(getString(R.string.news_open), "") {
                closeLauncherSettings()
                startActivity(Intent(this, NewsActivity::class.java))
            },
            R.string.test_screensaver to Triple(getString(R.string.test_screensaver), "") { testScreensaver() },
            R.string.screensaver_timing to Triple(getString(R.string.screensaver_timing), "") { openScreensaverSettings() },
            R.string.setup_home to Triple(getString(R.string.setup_home), "") { closeLauncherSettings(); startHomeSetup() },
            R.string.home_button to Triple(getString(R.string.home_button), toggle(homeServiceOn())) { toggleHomeButton() },
            R.string.default_launcher to Triple(getString(R.string.default_launcher), if (isDefaultHome()) getString(R.string.plume_press_change) else getString(R.string.press_to_set)) { makeDefaultHome() },
            R.string.updates to Triple(getString(R.string.updates), when {
                updating -> getString(R.string.updating)
                checking -> getString(R.string.checking)
                availableUpdate() != null -> getString(R.string.update_available_row, availableUpdate())
                notesUnseen() -> getString(R.string.whats_new_title, myVersion())
                else -> getString(R.string.up_to_date_row, myVersion())
            }) { showUpdates() },
            R.string.lock_layout to Triple(getString(R.string.lock_layout), toggle(lockOn())) {
                if (!lockOn()) return@Triple setLock()
                prefs.edit().remove("lock_pin").apply()
                renderSettings(R.string.lock_layout)
            },
        )
        for (k in listOf(R.string.sort_apps, R.string.icon_style, R.string.weather_units)) steps[k] = { rows[k]!!.third() }
        val on = { b: Boolean -> getString(if (b) R.string.on else R.string.off) }
        val sections = listOf(
            Triple(R.string.section_appearance, "${rows[R.string.theme]!!.second} · ${wallpaperName(currentWallpaper(dark))}",
                listOf(R.string.theme, R.string.light_wallpaper, R.string.dark_wallpaper, R.string.photo_fade, R.string.folder_photo_changes)),
            Triple(R.string.section_app_row, "${rows[R.string.apps_in_row]!!.second} · ${rows[R.string.row_position]!!.second}",
                listOf(R.string.edit_apps_row, R.string.sort_apps, R.string.apps_in_row, R.string.row_position, R.string.app_names, R.string.icon_style)),
            Triple(R.string.continue_watching, rows[R.string.cw_apps]!!.second, listOf(R.string.cw_apps, R.string.restore_hidden)),
            Triple(R.string.section_weather, if (weather == "off") on(false) else "${rows[R.string.weather_units]!!.second} · ${rows[R.string.weather_location]!!.second}",
                listOf(R.string.weather, R.string.weather_units, R.string.weather_location)),
            Triple(R.string.section_news, News.edition(prefs).name,
                listOf(R.string.news_open, R.string.news_region, R.string.news_topics, R.string.news_local, R.string.news_morning, R.string.news_screensaver)),
            Triple(R.string.section_screensaver, rows[R.string.screensaver]!!.second, listOf(R.string.screensaver, R.string.test_screensaver, R.string.screensaver_timing)),
            Triple(R.string.section_home, on(homeServiceOn() || isDefaultHome()), listOf(R.string.setup_home, R.string.home_button, R.string.default_launcher)),
        )
        val section = sections.firstOrNull { at in it.third }
        if (section != null) {
            lastSettings = section.first
            val keys = section.third
            return showPage(getString(section.first), keys.map { rows[it]!! }, keys.indexOf(at), steps = keys.map { steps[it] }) {
                renderSettings(section.first)
            }
        }
        val top = sections.map { (key, value, keys) -> key to page(getString(key), "$value  ›") { renderSettings(keys[0]) } } +
            listOf(R.string.updates, R.string.lock_layout).map { it to rows[it]!! } +
            listOfNotNull(if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) null
                else R.string.preview_home_setup to page(getString(R.string.preview_home_setup)) { closeLauncherSettings(); previewHomeSetup() })
        showPage(getString(R.string.plume_settings), top.map { it.second }, maxOf(0, top.indexOfFirst { it.first == at }), back = null)
    }

    private var lastSettings = 0 // section used last, focused when settings open again

    private fun <T> List<T>.step(current: T, dir: Int) = this[((indexOf(current) + dir) % size + size) % size]

    // Pages inside the settings panel (wallpaper list, app toggles…) instead of system dialogs, so they
    // match Plume's look. Back goes up one page; from the main list it closes the panel.

    private var pageBack: (() -> Unit)? = null
    private var pageExit: (() -> Unit)? = null // cleanup when the page goes away (stops the upload server)

    private fun page(title: String, value: String = "", action: () -> Unit) = Triple(title, value, action)

    private fun showPage(title: String, rows: List<Triple<String, String, () -> Unit>>, focus: Int = 0, top: View? = null,
                         steps: List<((Int) -> Unit)?> = emptyList(), back: (() -> Unit)?) {
        pageExit?.invoke()
        pageExit = null
        pageBack = back
        findViewById<TextView>(R.id.settingsTitle).text = title
        settingsList.removeAllViews()
        top?.let { settingsList.addView(it) }
        val first = settingsList.childCount
        rows.forEachIndexed { i, (name, value, action) ->
            settingsList.addView(settingsRow(name, value, steps.getOrNull(i), action), LinearLayout.LayoutParams(MATCH_PARENT, dp(44)).apply {
                topMargin = dp(4)
            })
        }
        // Focus wraps: Up from the first row goes to the last, Down from the last to the first.
        (settingsList.parent as View).scrollTo(0, 0)
        if (rows.isEmpty()) return // a page that's all [top], like the PIN boxes, focuses itself
        val firstRow = settingsList.getChildAt(first)
        val lastRow = settingsList.getChildAt(first + rows.size - 1)
        firstRow.nextFocusUpId = lastRow.id
        lastRow.nextFocusDownId = firstRow.id
        settingsList.getChildAt(first + focus.coerceIn(0, rows.size - 1)).requestFocus()
    }

    // ---------- layout lock ----------
    // A PIN in front of Plume settings and everything that changes the home screen; apps still open as usual.
    // It keeps the layout safe from small hands, it doesn't keep apps from kids: voice search and Android
    // settings get past it, so it's called a layout lock, not parental controls.

    private fun lockOn() = prefs.contains("lock_pin")

    private var unlockedUntil = 0L // one PIN covers a few minutes of changes, until the home screen is left
    private var pinFails = 0
    private var pinWaitUntil = 0L // ponytail: in memory, so restarting Plume resets the wait; fine for kids at a remote
    private var pinBox: View? = null

    private fun locked() = lockOn() && SystemClock.elapsedRealtime() > unlockedUntil

    /** Runs [then] at once when unlocked, otherwise after the right PIN. */
    private fun unlock(then: () -> Unit) {
        if (!locked()) return then()
        showPanel()
        pinPage(getString(R.string.enter_pin), back = null) { pin ->
            val now = SystemClock.elapsedRealtime()
            if (now < pinWaitUntil) return@pinPage getString(R.string.too_many_tries, (pinWaitUntil - now) / 1000 + 1)
            if (!pinMatches(prefs.getString("lock_pin", null)!!, pin)) {
                if (++pinFails < PIN_TRIES) return@pinPage getString(R.string.wrong_pin)
                pinFails = 0
                pinWaitUntil = now + 30_000L
                return@pinPage getString(R.string.too_many_tries, 30)
            }
            pinFails = 0
            unlockedUntil = now + UNLOCKED_MS
            closeLauncherSettings()
            then()
            null
        }
    }

    private fun setLock(): Unit = pinPage(getString(R.string.choose_pin), { renderSettings(R.string.lock_layout) }) { first ->
        pinPage(getString(R.string.enter_again), { renderSettings(R.string.lock_layout) }) { again ->
            if (again != first) {
                Toast.makeText(this, getString(R.string.pins_mismatch), Toast.LENGTH_LONG).show()
                setLock()
                return@pinPage null
            }
            prefs.edit().putString("lock_pin", hashPin(first)).apply()
            unlockedUntil = SystemClock.elapsedRealtime() + UNLOCKED_MS // no PIN straight away for the one who set it
            renderSettings(R.string.lock_layout)
            Toast.makeText(this, getString(R.string.layout_locked), Toast.LENGTH_LONG).show()
            null
        }
        null
    }

    /**
     * Four digit boxes: ▲ ▼ change a digit, ◀ ▶ move between boxes, number keys type, OK enters.
     * [done] gets the PIN and returns null when it's accepted, or a message to show before trying again.
     */
    private fun pinPage(title: String, back: (() -> Unit)?, done: (String) -> String?) {
        val digits = arrayOfNulls<Int>(4)
        var at = 0
        val boxes = List(4) { label("", 24f).apply { gravity = Gravity.CENTER } }
        val status = label("", 13f).apply { gravity = Gravity.CENTER; alpha = 0.6f }
        val row = LinearLayout(this).apply {
            id = View.generateViewId()
            gravity = Gravity.CENTER
            isFocusable = true
            boxes.forEach { addView(it, LinearLayout.LayoutParams(dp(48), dp(56)).apply { marginStart = dp(6); marginEnd = dp(6) }) }
        }
        fun draw() = boxes.forEachIndexed { i, b ->
            // Only the box being changed shows its digit.
            b.text = if (digits[i] == null) "" else if (i == at) digits[i].toString() else "•"
            b.background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.WHITE)
                if (i == at) setStroke(dp(2), fg)
            }
            if (dark) b.background = rimCard(dp(8), i == at)
        }
        fun submit() {
            val error = done(digits.joinToString("")) ?: return
            digits.fill(null)
            at = 0
            draw()
            status.text = error
            ObjectAnimator.ofFloat(row, View.TRANSLATION_X, 0f, -dp(12).toFloat(), dp(12).toFloat(), -dp(8).toFloat(), dp(8).toFloat(), 0f).setDuration(300).start()
        }
        row.setOnKeyListener { _, code, e ->
            val digit = when (code) {
                in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> code - KeyEvent.KEYCODE_0
                in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> code - KeyEvent.KEYCODE_NUMPAD_0
                else -> null
            }
            if (digit == null && code !in PIN_KEYS) return@setOnKeyListener false // Back still leaves
            if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener true
            when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> digits[at] = ((digits[at] ?: 0) + 1) % 10
                KeyEvent.KEYCODE_DPAD_DOWN -> digits[at] = ((digits[at] ?: 0) + 9) % 10
                KeyEvent.KEYCODE_DPAD_LEFT -> at = maxOf(0, at - 1)
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (digits[at] != null) at = minOf(3, at + 1)
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                    if (digits.all { it != null }) return@setOnKeyListener true.also { submit() }
                    else if (digits[at] != null) at = minOf(3, at + 1)
                else -> {
                    digits[at] = digit
                    if (at == 3) return@setOnKeyListener true.also { draw(); submit() }
                    at++
                }
            }
            draw()
            true
        }
        draw()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(16), 0, dp(8))
            addView(row)
            addView(status.apply { setPadding(0, dp(12), 0, 0) })
            addView(label(getString(R.string.pin_hint), 12f).apply {
                maxLines = 2
                gravity = Gravity.CENTER
                alpha = 0.6f
                setPadding(0, dp(8), 0, 0)
            })
            if (lockOn()) addView(label(getString(R.string.pin_forgot), 12f).apply {
                gravity = Gravity.CENTER
                alpha = 0.6f
                maxLines = 3
                setPadding(0, dp(24), 0, 0)
            })
        }
        showPage(title, emptyList(), top = box, back = back)
        pinBox = row
        row.requestFocus()
    }

    /** A settings row; with [step], Left and Right change its value in place, and ‹ › show that while focused. */
    private fun settingsRow(title: String, value: String, step: ((Int) -> Unit)? = null, action: () -> Unit): View {
        val bg = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0, 0)).apply { cornerRadius = dp(8).toFloat() }
        return LinearLayout(this).apply {
            id = View.generateViewId()
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), 0)
            background = bg
            isFocusable = true
            isClickable = true
            val name = label(title, 14f).apply { if (dark) setTextColor(DARK_TEXT_2) }
            addView(name, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val shown = if (value == TOGGLE_ON || value == TOGGLE_OFF) null else label(value, 13f).apply { secondary(0.6f) }
            if (shown == null) addView(switch(value == TOGGLE_ON), LinearLayout.LayoutParams(dp(36), dp(20))) else addView(shown)
            setOnClickListener { action() }
            setOnFocusChangeListener { _, focused ->
                // Focus: a lit rimCard in dark (and the title brightens), a white pill in light.
                if (dark) background = if (focused) glassRow() else bg
                else bg.colors = if (focused) intArrayOf(Color.WHITE, Color.WHITE) else intArrayOf(0, 0)
                // The title glides to bright rather than snapping, so moving through the list feels smooth.
                if (dark) ValueAnimator.ofArgb(name.currentTextColor, if (focused) DARK_TEXT else DARK_TEXT_2).apply {
                    duration = 160
                    interpolator = DECELERATE
                    addUpdateListener { name.setTextColor(it.animatedValue as Int) }
                }.start()
                if (step != null) shown?.text = if (focused) "‹  $value  ›" else value
            }
            tag = step // dispatchKeyEvent calls it for Left (-1) and Right (+1)
        }
    }

    private fun toggle(on: Boolean) = if (on) TOGGLE_ON else TOGGLE_OFF

    /** A mono switch for on/off rows: filled track with the knob right when on, outlined and left when off. */
    private fun switch(on: Boolean) = FrameLayout(this).apply {
        val faint = fg and 0xFFFFFF or 0x66000000
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            if (on) setColor(fg) else setStroke(dp(2), faint)
        }
        addView(View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (!on) faint else if (dark) DARK_BG else Color.WHITE)
            }
        }, FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER_VERTICAL or if (on) Gravity.END else Gravity.START).apply {
            marginStart = dp(3)
            marginEnd = dp(3)
        })
    }

    private fun showNet(caps: NetworkCapabilities?) {
        val ethernet = caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        wifi.setImageResource(if (ethernet) R.drawable.ic_ethernet else R.drawable.ic_wifi)
        // Dim only the icon: the focus ring is the background and should stay full strength.
        wifi.imageAlpha = if (caps == null) 90 else 255
    }

    private fun openSettings(action: String, data: Uri? = null) {
        try {
            startActivity(Intent(action, data))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    // ---------- apps row ----------

    /** Labels and icons come from each app's resources, ~200 ms on this TV: done off the UI thread. */
    private fun refreshApps() {
        appsDirty = false
        io.execute {
            val list = try {
                loadApps()
            } catch (e: Exception) {
                return@execute
            }
            ui.post {
                apps = list
                APPS = list
                renderApps()
            }
        }
    }

    private fun loadApps(): List<App> {
        val pm = packageManager
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)
            .map { it.activityInfo }
            .filter { it.packageName != packageName && it.packageName != TV_SETTINGS }
            .distinctBy { it.packageName }
            .map { a ->
                App(
                    a.packageName,
                    a.loadLabel(pm).toString(),
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                        .setComponent(ComponentName(a.packageName, a.name)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    a.loadIcon(pm),
                    banner = a.loadBanner(pm),
                )
            } + hdmiInputs()
    }

    /**
     * One tile per HDMI port, keyed by the port's input id. A device that names itself over CEC (the PS5 shows up
     * as "PlayStation 5") takes over its port's tile; it's listed while asleep too, and opening the tile wakes it.
     * A port without one is a tile named as in TV settings ("HDMI 2"), in the row only while it has a signal.
     */
    private fun hdmiInputs(): List<App> = try {
        val tv = getSystemService(TvInputManager::class.java)
        val inputs = tv.tvInputList.filter { it.type == TvInputInfo.TYPE_HDMI && !it.isHidden(this) }
        val devices = inputs.filter { it.parentId != null }
        val audio = devices.filter { isAudioDevice(it.loadLabel(this).toString()) }.map { it.parentId }.toSet()
        // Devices first, so distinctBy keeps the device over its port.
        (devices + inputs.filter { it.parentId == null }).filter { (it.parentId ?: it.id) !in audio }.map { input ->
            val port = input.parentId ?: input.id
            val named = input.parentId != null
            val label = (if (named) input.loadLabel(this) else input.loadCustomLabel(this) ?: input.loadLabel(this)).toString()
            App(
                HDMI + port,
                label,
                Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(port)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                getDrawable(HDMI_ICONS[hdmiIcon(label, named)]!!)!!,
                offline = !named && tv.getInputState(port) != TvInputManager.INPUT_STATE_CONNECTED,
            )
        }.distinctBy { it.pkg }
    } catch (e: Exception) {
        emptyList()
    }

    private val inputCallback = object : TvInputManager.TvInputCallback() {
        override fun onInputAdded(id: String) = refreshApps()
        override fun onInputRemoved(id: String) = refreshApps()
        override fun onInputStateChanged(id: String, state: Int) = refreshApps()
    }

    /** HDMI ports pinned to the row by hand: shown with or without a signal (some TVs never report one). */
    private fun pinnedInputs() = prefs.getStringSet("inputs_pinned", emptySet())!!

    private fun sortedApps(): List<App> {
        // Streaming apps first by default, then alphabetical; manual order or usage overrides.
        val byDefault = compareBy<App> { DEFAULT_ORDER.indexOf(it.pkg).let { i -> if (i < 0) Int.MAX_VALUE else i } }
            .thenBy { it.label.lowercase() }
        if (prefs.getString("sort", "manual") == "usage") {
            return apps.sortedWith(compareByDescending<App> { prefs.getInt("n_" + it.pkg, 0) }.then(byDefault))
        }
        val order = prefs.getString("order", "")!!.split(',')
        // A console not yet placed by hand goes first, so it's visible without scrolling.
        return apps.sortedWith(compareBy<App> {
            order.indexOf(it.pkg).let { i -> if (i >= 0) i else if (it.pkg.startsWith(HDMI)) -1 else Int.MAX_VALUE }
        }.then(byDefault))
    }

    /**
     * Every app, logo only, on the same 8-column grid as the Continue Watching cards (one card = two
     * tiles), so 8 show at once and the row scrolls for the rest. All apps and Settings are in the top bar.
     */
    private fun rowAlign() = prefs.getString("row_align", "left")!!.takeIf { it in ROW_ALIGN } ?: "left"

    /**
     * A row shorter than the screen sits left, centred or right. A longer row always starts at the left:
     * centred, the scroller would push its first tiles out of reach.
     */
    private fun alignRow() {
        val content = findViewById<View>(R.id.lensLayer) // the scroller's child: rows plus the focus lens
        (content.layoutParams as FrameLayout.LayoutParams).gravity =
            if (appTileCount() > DOCK_FIT) Gravity.START else ROW_ALIGN[rowAlign()]!!.second
        content.requestLayout()
    }

    private fun renderApps() {
        val focusedTag = currentFocus?.tag
        // Rebuilds can happen in the background (an app or HDMI device added); only take focus if the row had it.
        // First fill after a cold start: Android already gave the default focus to the top bar's first icon.
        val firstFill = appRow.childCount == 0 && currentFocus?.parent === wifi.parent
        val takeFocus = currentFocus == null || currentFocus?.parent === appRow || firstFill
        val gap = dp(DOCK_GAP)
        // DOCK_FIT tiles exactly fill the glass panel (content width, plus the dock's 16 dp overhang each side, minus
        // its 24 dp inner padding each side), 16:9 like Android TV app banners.
        val width = tileWidth()
        appRow.removeAllViews()
        val hidden = rowHiddenSet()
        val pinned = pinnedInputs()
        val tiles = sortedApps().filter { inRow(it.pkg, it.offline, hidden, pinned) }.map { dockTile(it) }
        if (tiles.isEmpty()) return showNames()
        tiles.forEachIndexed { i, t ->
            t.id = View.generateViewId()
            appRow.addView(t, LinearLayout.LayoutParams(width, width * 9 / 16).apply { if (i > 0) marginStart = gap })
            val own = t.onFocusChangeListener
            t.setOnFocusChangeListener { v, focused ->
                own.onFocusChange(v, focused)
                if (focused) lastApp = v
                showRowLabel(v, focused)
                moveLens(v, focused)
            }
        }
        // Negative end margins: the lens is drawn bigger than a tile but takes no more room than one, so it never
        // adds height to the dock (it's positioned by translation, see moveLens).
        lens.layoutParams = FrameLayout.LayoutParams(width + dp(LENS_PAD) * 2, width * 9 / 16 + dp(LENS_PAD) * 2).apply {
            rightMargin = -dp(LENS_PAD) * 2
            bottomMargin = -dp(LENS_PAD) * 2
        }
        showNames()
        alignRow()
        if (takeFocus) (tiles.firstOrNull { it.tag == focusedTag } ?: tiles[0]).requestFocus()
        if (!prefs.getBoolean("tip_move", false)) {
            prefs.edit().putBoolean("tip_move", true).apply()
            Toast.makeText(this, getString(R.string.tip_move), Toast.LENGTH_LONG).show()
        }
    }

    /** Home row names: "focus" (under the focused tile only), "always" (in every tile) or "never". */
    private fun rowNames() = prefs.getString("names", "focus")!!

    /** Each app's own shipped icon instead of the mono mark, in the row and All apps. */
    private fun colourIcons() = prefs.getBoolean("colour_icons", false)

    /** The glass dock shows logos only: no name line under the tiles. */
    private fun showNames() {
        findViewById<View>(R.id.nameRow).visibility = View.GONE
        findViewById<View>(R.id.rowLabel).visibility = View.GONE
    }

    /** Kept for the editing flow, which calls it; the dock has no name label to show. */
    @Suppress("UNUSED_PARAMETER")
    private fun showRowLabel(v: View, focused: Boolean) {}

    /** Optional per-theme icons: assets/icons/<package>_dark.png, <package>_light.png, or <package>.png. */
    private fun customIcon(pkg: String): Drawable? =
        listOf("icons/${pkg}_${if (dark) "dark" else "light"}.png", "icons/$pkg.png").firstNotNullOfOrNull { path ->
            try {
                assets.open(path).use { Drawable.createFromStream(it, null) }
            } catch (e: IOException) {
                null
            }
        }

    // ---------- holding OK, and editing the apps row ----------

    /**
     * Hold detection lives here, not in View long-click: this TV's remote doesn't reliably trigger the
     * framework's long-press timer. We treat the first auto-repeat of OK as "held"; the long-click
     * listener on each tile (focusLift) is a fallback for remotes that send no repeats. [holdFired] stops both firing.
     */
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action == KeyEvent.ACTION_DOWN) {
            backdrop.hold() // the slow zoom waits while the remote is in use
            lastKeyAt = SystemClock.uptimeMillis()
            lastDir = when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> -1
                KeyEvent.KEYCODE_DPAD_RIGHT -> 1
                else -> 0
            }
        }
        val confirm = e.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || e.keyCode == KeyEvent.KEYCODE_ENTER ||
            e.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        if (confirm) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) holdFired = false
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount > 0) {
                if (!holdFired) currentFocus?.let { fireHold(it) }
                return true
            }
            if (e.action == KeyEvent.ACTION_UP && holdFired) {
                currentFocus?.isPressed = false
                return true
            }
        }
        // The settings panel is a vertical list: Left/Right step a row's value (its tag, see settingsRow) and
        // never leak focus to the home screen behind it. The PIN boxes and the city search box use them themselves.
        if (settingsScrim.visibility == View.VISIBLE && currentFocus !== pinBox && currentFocus !is EditText &&
            (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || e.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
        ) {
            @Suppress("UNCHECKED_CAST")
            if (e.action == KeyEvent.ACTION_DOWN) (currentFocus?.tag as? (Int) -> Unit)?.invoke(if (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
            return true
        }
        if (editing && editKey(e, confirm)) return true
        if (e.action == KeyEvent.ACTION_DOWN && allApps.visibility == View.VISIBLE && settingsScrim.visibility != View.VISIBLE) {
            drawerNav(e.keyCode)?.let { return it }
        }
        if (e.action == KeyEvent.ACTION_DOWN && settingsScrim.visibility != View.VISIBLE && allApps.visibility != View.VISIBLE) {
            currentFocus?.let { from -> homeNav(from, e.keyCode)?.let { to ->
                // Android only plays the navigation click for focus moves it makes; these are ours.
                if (to !== from || from === shelf && e.keyCode in SHELF_KEYS) navSound(e.keyCode)
                to.requestFocus()
                return true
            } }
        }
        return super.dispatchKeyEvent(e)
    }

    /**
     * The home screen is three rows: top bar, apps, Continue Watching. Left/Right stay inside a row and
     * stop at its ends; Up/Down move between rows and land where you last were in that row, not on
     * whatever happens to sit nearest on screen. Returns the view to focus (itself = stay), or null to
     * let Android handle the key.
     */
    private fun homeNav(v: View, key: Int): View? {
        fun alive(x: View?) = x?.takeIf { it.isAttachedToWindow }
        val apps = alive(lastApp)?.takeIf { it.parent === appRow } ?: appRow.getChildAt(0)
        val top = alive(lastTop) ?: findViewById(R.id.launcherSettings)
        val onShelf = shelf.takeIf { it.visibility == View.VISIBLE }
        return when {
            v.parent === wifi.parent -> when (key) { // top bar
                KeyEvent.KEYCODE_DPAD_DOWN -> onShelf ?: apps ?: v // the apps row can be empty (all hidden)
                KeyEvent.KEYCODE_DPAD_UP -> v
                KeyEvent.KEYCODE_DPAD_LEFT -> if (v.id == R.id.wifi) v else null
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (v.id == R.id.launcherSettings) v else null
                else -> null
            }
            v === shelf -> when (key) { // Top Shelf: Left/Right page through Continue Watching
                KeyEvent.KEYCODE_DPAD_UP -> top
                KeyEvent.KEYCODE_DPAD_DOWN -> apps ?: v
                KeyEvent.KEYCODE_DPAD_LEFT -> v.also { shelfStep(-1) }
                KeyEvent.KEYCODE_DPAD_RIGHT -> v.also { shelfStep(1) }
                else -> null
            }
            v.parent === appRow -> when (key) {
                KeyEvent.KEYCODE_DPAD_UP -> onShelf ?: top
                KeyEvent.KEYCODE_DPAD_DOWN -> v
                // Moved here, not left to the scroller: it moves focus without the navigation click.
                KeyEvent.KEYCODE_DPAD_LEFT -> appRow.getChildAt(appRow.indexOfChild(v) - 1) ?: v
                KeyEvent.KEYCODE_DPAD_RIGHT -> appRow.getChildAt(appRow.indexOfChild(v) + 1) ?: v
                else -> null
            }
            else -> null
        }
    }

    /** Overlays (All apps, launcher settings) sit on top of the home rows; keep focus from slipping behind them. */
    private fun homeFocusable(on: Boolean) {
        findViewById<ViewGroup>(R.id.content).descendantFocusability =
            if (on) ViewGroup.FOCUS_AFTER_DESCENDANTS else ViewGroup.FOCUS_BLOCK_DESCENDANTS
    }

    private fun fireHold(v: View) {
        holdFired = true
        v.cancelLongPress()
        v.isPressed = false
        onHold(v)
    }

    /** What holding OK does depends on what's focused. */
    private fun onHold(v: View) {
        val tag = v.tag as? String
        when {
            allApps.visibility == View.VISIBLE && tag != null -> unlock { // grid: pin to the front of the row (unhiding it)
                val edit = prefs.edit().putStringSet("row_hidden", rowHiddenSet() - tag)
                if (tag.startsWith(HDMI)) edit.putStringSet("inputs_pinned", pinnedInputs() + tag)
                edit.apply()
                saveOrder(listOf(tag) + sortedApps().map { it.pkg }.filter { it != tag })
                closeAllApps()
                appRow.getChildAt(0)?.requestFocus() // show the app just pinned
            }
            v.parent === appRow -> appMenu(v)
            v.getTag(R.id.shelf) is Prog -> watchMenu(v, v.getTag(R.id.shelf) as Prog)
        }
    }

    /** Hold OK on a row app: Move (OK places it), Remove from row (stays in All apps), App info. Locked: Open only. */
    private fun appMenu(v: View) {
        val pkg = v.tag as String
        if (locked()) return holdMenu(v, listOf(
            getString(R.string.open) to { v.performClick(); Unit },
            getString(R.string.unlock_to_edit) to { unlock { v.requestFocus(); appMenu(v) } },
        ))
        holdMenu(v, listOfNotNull(
            getString(R.string.move) to { startEdit(grab = v, once = true) },
            getString(R.string.remove_from_row) to {
                val i = appRow.indexOfChild(v)
                // A port with no signal just unpins: hiding it would keep out whatever gets plugged in there later.
                val offline = apps.any { it.pkg == pkg && it.offline }
                prefs.edit().putStringSet("row_hidden", if (offline) rowHiddenSet() else rowHiddenSet() + pkg)
                    .putStringSet("inputs_pinned", pinnedInputs() - pkg).apply()
                renderApps()
                appRow.getChildAt(minOf(i, appRow.childCount - 1))?.requestFocus()
                Toast.makeText(this, getString(R.string.removed_from_row), Toast.LENGTH_SHORT).show()
            },
            if (pkg.startsWith(HDMI)) null else getString(R.string.app_info) to {
                openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))
            },
        ))
    }

    /** Hold OK on a Continue Watching card: hide the show, or open its app rather than the episode. */
    private fun watchMenu(v: View, p: Prog) {
        holdMenu(v, listOfNotNull(
            if (locked()) null else getString(R.string.remove_from_cw) to { hideWatch(p) },
            if (!locked()) null else getString(R.string.unlock_to_edit) to { unlock { v.requestFocus(); watchMenu(v, p) } },
            p.pkg?.let { pkg ->
                getString(R.string.open_app) to {
                    (packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: packageManager.getLaunchIntentForPackage(pkg))
                        ?.let { startActivity(it) }
                    Unit
                }
            },
        ))
    }

    /** A small Plume-styled menu just above [anchor]; Back closes it. */
    private fun holdMenu(anchor: View, items: List<Pair<String, () -> Unit>>) {
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = if (dark) rimCard(dp(12), false) else GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(0xFFF7F8FA.toInt())
            }
            elevation = if (dark) 0f else dp(8).toFloat()
        }
        val menu = PopupWindow(list, LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, true)
        items.forEachIndexed { i, (name, action) ->
            list.addView(settingsRow(name, "") { menu.dismiss(); action() }, LinearLayout.LayoutParams(MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                if (i > 0) topMargin = dp(2)
            })
            list.getChildAt(i).minimumHeight = dp(40)
        }
        // As wide as the longest item, never narrower than the tile or card it opens from.
        // A very long translation wraps onto two lines instead of stretching across the screen.
        list.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        menu.width = list.measuredWidth.coerceIn(anchor.width, maxOf(anchor.width, dp(360)))
        list.measure(View.MeasureSpec.makeMeasureSpec(menu.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        val at = IntArray(2).also { anchor.getLocationInWindow(it) }
        menu.elevation = if (dark) 0f else dp(8).toFloat()
        // Over the anchor's left edge, but kept inside the row's 48 dp side margin near the right of the screen.
        val x = minOf(at[0], resources.displayMetrics.widthPixels - dp(SCREEN_PAD) - menu.width)
        menu.showAtLocation(anchor, Gravity.NO_GRAVITY, x, maxOf(dp(8), at[1] - list.measuredHeight - dp(8)))
        list.getChildAt(0).requestFocus()
    }

    private fun appTileCount() = appRow.childCount

    private var moveOnce = false // moving one app from the hold menu: OK places it and editing ends

    private fun startEdit(grab: View?, once: Boolean = false) {
        editing = true
        moveOnce = once
        appsTitle.text = if (once) getString(R.string.moving_hint) else getString(R.string.editing_hint)
        appsTitle.visibility = View.VISIBLE
        lens.hide()
        (grab ?: appRow.getChildAt(0)).requestFocus()
        setGrabbed(grab)
    }

    private fun setGrabbed(v: View?) {
        grabbed?.alpha = 1f
        grabbed = v
        v?.alpha = 0.6f
    }

    private fun endEdit() {
        setGrabbed(null)
        editing = false
        moveOnce = false
        appsTitle.visibility = View.GONE
        val row = (0 until appTileCount()).map { appRow.getChildAt(it).tag as String }
        saveOrder(row + sortedApps().map { it.pkg }.filter { it !in row })
        currentFocus?.takeIf { it.parent === appRow }?.let { showRowLabel(it, true) } // the name was hidden while editing
    }

    /** While editing, keys only rearrange the row; nothing launches and focus can't leave it. */
    private fun editKey(e: KeyEvent, confirm: Boolean): Boolean {
        if (e.keyCode == KeyEvent.KEYCODE_BACK) {
            if (e.action == KeyEvent.ACTION_UP) endEdit()
            return true
        }
        val v = currentFocus ?: return false
        val i = appRow.indexOfChild(v)
        if (confirm) {
            if (e.action == KeyEvent.ACTION_UP && i in 0 until appTileCount()) {
                if (moveOnce) endEdit() else setGrabbed(if (grabbed === v) null else v)
            }
            return true
        }
        if (e.action != KeyEvent.ACTION_DOWN) return e.keyCode in EDIT_KEYS
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> return true
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val j = if (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) i - 1 else i + 1
                if (j !in 0 until appTileCount()) return true
                if (grabbed === v) {
                    // Swap layout params too, so the first tile keeps no leading margin.
                    val other = appRow.getChildAt(j)
                    val lp = v.layoutParams
                    v.layoutParams = other.layoutParams
                    other.layoutParams = lp
                    appRow.removeViewAt(i)
                    appRow.addView(v, j)
                    showNames()
                    v.requestFocus()
                    return true
                }
                return false // not holding anything: normal focus movement within the row
            }
        }
        return false
    }

    /** Apps left out of the home row; they stay in All apps. New installs show until hidden. */
    private fun rowHiddenSet() = prefs.getStringSet("row_hidden", emptySet())!!

    private fun pickRowApps() {
        // An HDMI port without a signal can't show in the row, so it's pinned from All apps instead.
        val list = sortedApps().filter { !it.offline || it.pkg in pinnedInputs() }
        toggles(getString(R.string.apps_in_row), list.map { it.label to it.pkg }, "row_hidden", getString(R.string.shown), getString(R.string.hidden)) {
            renderApps()
            renderSettings(R.string.apps_in_row)
        }
    }

    /** A page of on/off rows saved as the set of keys that are off; [done] runs on Back. */
    private fun toggles(title: String, items: List<Pair<String, String>>, pref: String, on: String, off: String, focus: Int = 0, done: () -> Unit) {
        val offSet = prefs.getStringSet(pref, emptySet())!!
        showPage(title, items.mapIndexed { i, (name, key) ->
            page(name, if (key in offSet) off else on) {
                prefs.edit().putStringSet(pref, if (key in offSet) offSet - key else offSet + key).apply()
                toggles(title, items, pref, on, off, i, done)
            }
        }, focus, back = done)
    }

    private fun saveOrder(pkgs: List<String>) {
        prefs.edit().putString("order", pkgs.joinToString(",")).putString("sort", "manual").apply()
    }

    private fun launch(a: App) {
        prefs.edit().putInt("n_" + a.pkg, prefs.getInt("n_" + a.pkg, 0) + 1).putLong("t_" + a.pkg, System.currentTimeMillis()).apply()
        if (prefs.getString("sort", "manual") == "usage") appsDirty = true
        try {
            startActivity(a.launch)
        } catch (e: Exception) {
            packageManager.getLeanbackLaunchIntentForPackage(a.pkg)?.let { startActivity(it) }
        }
    }

    // ---------- all apps ----------

    /**
     * All apps as a frosted glass sheet over the artwork, like the settings sheet: the same squircle banner tiles
     * and light sweep as the dock, six across, each with its name underneath. Holding OK on one pins it to the
     * front of the dock (see onHold).
     */
    private fun openAllApps() {
        allAppsGrid.removeAllViews()
        val gap = dp(26)
        val inner = resources.displayMetrics.widthPixels - dp(SCREEN_PAD) * 2 - dp(DRAWER_PAD) * 2
        val w = inner / ALL_APPS_COLUMNS - gap
        val h = w * 9 / 16
        for (a in sortedApps()) {
            val tile = dockTile(a, h * 0.24f)
            val name = label(a.label, 13f).apply {
                gravity = Gravity.CENTER
                alpha = 0.6f
                setPadding(0, dp(10), 0, 0)
                setShadowLayer(dpf(6f), 0f, dpf(1f), 0x66000000)
            }
            val own = tile.onFocusChangeListener
            tile.setOnFocusChangeListener { v, focused ->
                own.onFocusChange(v, focused)
                name.animate().alpha(if (focused) 1f else 0.6f).setDuration(Motion.FOCUS_MS).setInterpolator(Motion.S).start()
            }
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false
                clipToPadding = false
                addView(tile, LinearLayout.LayoutParams(w, h))
                addView(name, LinearLayout.LayoutParams(w, LinearLayout.LayoutParams.WRAP_CONTENT))
            }
            allAppsGrid.addView(cell, GridLayout.LayoutParams().apply {
                width = w
                setMargins(gap / 2, 0, gap / 2, gap)
            })
        }
        allApps.visibility = View.VISIBLE
        allApps.alpha = 0f
        allApps.scaleX = 0.97f
        allApps.scaleY = 0.97f
        allApps.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(380).setInterpolator(Motion.S).start()
        homeFocusable(false)
        (allAppsGrid.getChildAt(0) as? ViewGroup)?.getChildAt(0)?.requestFocus()
    }

    private fun closeAllApps() {
        val sheet = allApps
        sheet.animate().alpha(0f).scaleX(0.97f).scaleY(0.97f).setDuration(240).setInterpolator(Motion.S)
            .withEndAction { sheet.visibility = View.GONE }.start()
        homeFocusable(true)
        renderApps()
        findViewById<View>(R.id.allAppsButton).requestFocus() // back where it was opened from
    }

    /** Moves the All apps list onto a frosted glass sheet with the dock's rim, inset from the screen edges. */
    private fun setupDrawer() {
        val root = findViewById<FrameLayout>(R.id.root)
        val list = findViewById<View>(R.id.allApps)
        val at = root.indexOfChild(list)
        root.removeView(list)
        val radius = dpf(32f)
        val sheet = FrameLayout(this)
        drawerGlass = GlassView(this).apply { tint = 0x8C101014.toInt(); corner = radius }
        sheet.addView(drawerGlass, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        list.background = null
        list.visibility = View.VISIBLE
        list.setPadding(dp(DRAWER_PAD), dp(28), dp(DRAWER_PAD), dp(28))
        sheet.addView(list, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        sheet.foreground = Glass.rim(radius, resources.displayMetrics.density)
        sheet.visibility = View.GONE
        root.addView(sheet, at, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply {
            setMargins(dp(SCREEN_PAD), dp(SCREEN_PAD), dp(SCREEN_PAD), dp(SCREEN_PAD))
        })
        allApps = sheet
        findViewById<TextView>(R.id.allAppsTitle).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textSize = 24f
            setTextColor(DARK_TEXT)
            setPadding(dp(13), 0, 0, dp(10))
        }
        (allAppsGrid.layoutParams as LinearLayout.LayoutParams).gravity = Gravity.CENTER_HORIZONTAL
    }

    // ---------- screensaver ----------
    // Gallery on: remember whatever screensaver was set (Aerial Views here) and switch to ours.
    // Gallery off: put that one back. Needs the adb-granted WRITE_SECURE_SETTINGS, like the Home service.

    private fun gallery() = ComponentName(this, GalleryDream::class.java).flattenToString()

    private fun currentScreensaver() = Settings.Secure.getString(contentResolver, "screensaver_components").orEmpty()

    private fun galleryOn() = currentScreensaver() == gallery()

    private fun newsDream() = ComponentName(this, NewsDream::class.java).flattenToString()

    private fun newsOn() = currentScreensaver() == newsDream()

    /** One of Plume's own screensavers is set; the one before it is kept in "prev_screensaver" to go back to. */
    private fun oursOn() = galleryOn() || newsOn()

    /** News as the screensaver (needs the adb-granted permission, like Gallery). */
    private fun useNews() {
        if (newsOn()) return
        if (checkSelfPermission(WRITE_SECURE) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, getString(R.string.choose_gallery_hint), Toast.LENGTH_LONG).show()
            return openScreensaverSettings()
        }
        if (!oursOn()) prefs.edit().putString("prev_screensaver", currentScreensaver()).apply()
        Settings.Secure.putString(contentResolver, "screensaver_components", newsDream())
        Settings.Secure.putInt(contentResolver, "screensaver_enabled", 1)
        if (galleryOn()) paintingsDir(this).deleteRecursively()
    }

    private fun screensaverName(component: String = currentScreensaver()): String {
        val pkg = ComponentName.unflattenFromString(component)?.packageName ?: return getString(R.string.off)
        return try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            getString(R.string.system_default)
        }
    }

    /** The folder the Gallery screensaver shows instead of paintings, or null for paintings. */
    private fun photoScreensaver() = prefs.getString("ss_folder", null)

    private fun pickScreensaver(focus: Int = 0) {
        val paintings = galleryOn() && photoScreensaver() == null
        val photos = galleryOn() && photoScreensaver() != null
        val other = if (oursOn()) prefs.getString("prev_screensaver", "").orEmpty() else currentScreensaver()
        showPage(getString(R.string.screensaver), listOf(
            page(getString(R.string.gallery_paintings), if (paintings) CHOSEN else if (downloading) getString(R.string.downloading) else PAINTINGS_MB) {
                useGallery(null)
                pickScreensaver(0)
            },
            page(getString(R.string.your_photos), if (photos) "${wallpaperName(photoScreensaver()!!)} $CHOSEN" else "") {
                pickFolder({ pickScreensaver(1) }) { choice ->
                    useGallery(choice)
                    pickScreensaver(1)
                }
            },
            page(getString(R.string.news), if (newsOn()) CHOSEN else "") {
                useNews()
                pickScreensaver(2)
            },
            page(screensaverName(other), if (oursOn()) "" else CHOSEN) {
                if (oursOn()) stopGallery()
                pickScreensaver(3)
            },
        ), focus) { renderSettings(R.string.screensaver) }
    }

    /** Gallery on, with paintings ([folder] null, downloaded first if needed) or a folder of photos. */
    private fun useGallery(folder: String?) {
        prefs.edit().putString("ss_folder", folder).apply()
        if (folder == null && !paintingsReady(this)) return downloadPaintings { useGallery(null) }
        if (folder != null) paintingsDir(this).deleteRecursively() // not needed for photos: give the space back
        if (galleryOn()) return
        val fromNews = newsOn()
        if (checkSelfPermission(WRITE_SECURE) != PackageManager.PERMISSION_GRANTED) {
            // Without the adb permission Plume can't switch it itself; Android's own list has getString(R.string.gallery).
            Toast.makeText(this, getString(R.string.choose_gallery_hint), Toast.LENGTH_LONG).show()
            return openScreensaverSettings()
        }
        if (!fromNews) prefs.edit().putString("prev_screensaver", currentScreensaver()).apply()
        Settings.Secure.putString(contentResolver, "screensaver_components", gallery())
        Settings.Secure.putInt(contentResolver, "screensaver_enabled", 1)
    }

    /** Back to whatever screensaver was set before Gallery. */
    private fun stopGallery() {
        if (checkSelfPermission(WRITE_SECURE) != PackageManager.PERMISSION_GRANTED) return openScreensaverSettings()
        Settings.Secure.putString(contentResolver, "screensaver_components", prefs.getString("prev_screensaver", "") ?: "")
        paintingsDir(this).deleteRecursively() // another screensaver is in charge: give the space back
    }

    /** Android's screensaver settings (timing, sleep): plain Android TV and Google TV name them differently. */
    private fun openScreensaverSettings() {
        for (action in listOf(Settings.ACTION_DREAM_SETTINGS, "com.google.android.tv.settings.ambient")) {
            try {
                return startActivity(Intent(action))
            } catch (e: Exception) {
            }
        }
        openSettings(Settings.ACTION_SETTINGS)
    }

    @Volatile private var downloading = false

    /**
     * Fetches the Gallery paintings into a temporary folder and only swaps it in once every file arrived,
     * so a dropped connection never leaves a half-filled Gallery behind.
     */
    private fun downloadPaintings(then: () -> Unit) {
        if (downloading) return
        downloading = true
        Toast.makeText(this, getString(R.string.downloading_paintings, PAINTINGS_MB), Toast.LENGTH_SHORT).show()
        Thread {
            val part = File(filesDir, "paintings.part").apply { deleteRecursively(); mkdirs() }
            val ok = try {
                for (f in PAINTING_FILES) openStream(PAINTINGS_URL + f).use { input ->
                    File(part, f).outputStream().use { input.copyTo(it) }
                }
                paintingsDir(this).deleteRecursively()
                part.renameTo(paintingsDir(this))
            } catch (e: Exception) {
                android.util.Log.w("Plume", "paintings download failed ($e)")
                part.deleteRecursively()
                false
            }
            ui.post {
                downloading = false
                if (ok) then() else Toast.makeText(this, getString(R.string.paintings_failed), Toast.LENGTH_LONG).show()
                if (settingsScrim.visibility == View.VISIBLE) renderSettings(R.string.screensaver)
            }
        }.start()
    }

    /** Starts whichever screensaver is set, through SystemUI's "dream now" entry point. */
    private fun testScreensaver() {
        try {
            startActivity(Intent(Intent.ACTION_MAIN).setClassName("com.android.systemui", "com.android.systemui.Somnambulator"))
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.cant_dream), Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- updates (GitHub releases) ----------
    // Checked once a day from the home screen; a new version is announced once, then waits in settings.
    // The APK streams straight into an install session, so there's no temporary file to clean up.

    @Volatile private var checking = false
    @Volatile private var updating = false

    private fun myVersion() = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()

    /** The newer version a check found, if any (null again once it's installed). */
    private fun availableUpdate() = prefs.getString("upd_ver", null)?.takeIf { newerVersion(it, myVersion()) }

    private fun checkUpdate(force: Boolean) {
        // This is a personal fork: upstream releases are signed with another key and would replace the glass layout.
        if (FORK) return
        if (checking || !force && System.currentTimeMillis() - prefs.getLong("upd_time", 0) < 24 * 3600_000L) return
        checking = true
        if (force) renderSettings(R.string.updates)
        io.execute {
            val latest = try {
                getJson("https://api.github.com/repos/$REPO/releases/latest").getString("tag_name").removePrefix("v")
            } catch (e: Exception) {
                android.util.Log.w("Plume", "update check failed ($e)")
                null
            }
            if (latest != null) prefs.edit().putString("upd_ver", latest).putLong("upd_time", System.currentTimeMillis()).apply()
            ui.post {
                checking = false
                val ver = availableUpdate()
                if (ver != null && (force || prefs.getString("upd_told", null) != ver)) {
                    prefs.edit().putString("upd_told", ver).apply()
                    if (!force) Toast.makeText(this, getString(R.string.update_available_toast, ver), Toast.LENGTH_LONG).show()
                } else if (force) {
                    Toast.makeText(this, if (latest == null) getString(R.string.update_check_failed) else getString(R.string.up_to_date_toast), Toast.LENGTH_SHORT).show()
                }
                updateBadge()
                if (settingsScrim.visibility == View.VISIBLE) renderSettings(R.string.updates)
            }
        }
    }

    /** What's new in this version (the CHANGELOG.md section the build put in), with check or install on top. */
    private fun showUpdates() {
        prefs.edit().putString("seen_notes", myVersion()).apply()
        updateBadge()
        val ver = availableUpdate()
        showPage(getString(R.string.whats_new_title, myVersion()), listOf(
            if (ver != null) page(getString(R.string.install_update, ver)) { installUpdate(ver) }
            else page(getString(R.string.check_updates)) { checkUpdate(force = true) },
        ), back = { renderSettings(R.string.updates) })
        // Focusable, lit on focus, so Down walks through notes longer than the panel and it scrolls along.
        getString(R.string.whats_new).split('\n').forEach {
            settingsList.addView(label("", 13f).apply {
                text = SpannableString(it).apply { setSpan(BulletSpan(dp(10), fg), 0, it.length, 0) }
                maxLines = Int.MAX_VALUE
                ellipsize = null
                alpha = 0.7f
                isFocusable = true
                setOnFocusChangeListener { v, focused -> v.alpha = if (focused) 1f else 0.7f }
                setPadding(dp(14), dp(10), dp(14), dp(2))
            })
        }
        settingsList.getChildAt(0).nextFocusDownId = View.NO_ID // showPage stops Down at its last row
        settingsList.getChildAt(settingsList.childCount - 1).let { it.id = View.generateViewId(); it.nextFocusDownId = it.id }
    }

    /** Notes the user hasn't opened since updating. A fresh install has nothing new to tell. */
    private fun notesUnseen(): Boolean {
        val info = packageManager.getPackageInfo(packageName, 0)
        return info.firstInstallTime != info.lastUpdateTime && prefs.getString("seen_notes", null) != info.versionName
    }

    private val badge by lazy { GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(BADGE) } }

    /** A dot on the Plume settings icon while an update waits or its notes are unread. */
    private fun updateBadge() {
        val icon = findViewById<ImageView>(R.id.launcherSettings)
        icon.overlay.clear()
        if (availableUpdate() == null && !notesUnseen()) return
        val size = dp(9)
        badge.setBounds(icon.width - size - dp(3), dp(3), icon.width - dp(3), dp(3) + size)
        icon.overlay.add(badge)
    }

    private fun installUpdate(ver: String) {
        if (updating) return
        if (!packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, getString(R.string.allow_installs), Toast.LENGTH_LONG).show()
            return openSettings(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
        }
        updating = true
        renderSettings(R.string.updates)
        val installer = packageManager.packageInstaller
        io.execute {
            val ok = try {
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setAppPackageName(packageName)
                // Android 12+ can skip the confirm box when we're the app's installer; otherwise Android asks.
                if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                val id = installer.createSession(params)
                installer.openSession(id).use { session ->
                    try {
                        openStream("https://github.com/$REPO/releases/download/v$ver/Plume.apk").use { input ->
                            session.openWrite("Plume.apk", 0, -1).use { out ->
                                input.copyTo(out)
                                session.fsync(out)
                            }
                        }
                    } catch (e: Exception) {
                        session.abandon()
                        throw e
                    }
                    val done = PendingIntent.getActivity(this, 0,
                        Intent(this, MainActivity::class.java).setAction(UPDATE_STATUS),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                    session.commit(done.intentSender)
                }
                true
            } catch (e: Exception) {
                android.util.Log.w("Plume", "update download failed ($e)")
                false
            }
            ui.post {
                if (!ok) {
                    updating = false
                    Toast.makeText(this, getString(R.string.update_download_failed), Toast.LENGTH_LONG).show()
                }
                if (settingsScrim.visibility == View.VISIBLE) renderSettings(R.string.updates)
            }
        }
    }

    /** The install session reports back here: show Android's confirm box, or say why it failed. */
    private fun installStatus(intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)?.let { startActivity(it) }
            return
        }
        updating = false
        if (status != PackageInstaller.STATUS_SUCCESS) {
            val why = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            android.util.Log.w("Plume", "update install failed: $status $why")
            if (status != PackageInstaller.STATUS_FAILURE_ABORTED) {
                Toast.makeText(this, getString(R.string.update_install_failed), Toast.LENGTH_LONG).show()
            }
        }
        if (settingsScrim.visibility == View.VISIBLE) renderSettings(R.string.updates)
    }

    // ---------- weather (under the date) ----------
    // Location comes from the public IP (ipwho.is): a TV has no GPS and Android TV no location service.
    // Weather is Open-Meteo. Both are free and keyless. We always fetch Celsius and convert, so
    // switching units needs no refetch. The last reading is kept in prefs and shows instantly.

    private val weatherTick = object : Runnable {
        override fun run() {
            refreshWeather()
            ui.postDelayed(this, 30 * 60_000L)
        }
    }

    private fun refreshWeather() {
        if (prefs.getString("weather", "off") == "off") return
        if (System.currentTimeMillis() - prefs.getLong("wx_time", 0) < 25 * 60_000L) return
        io.execute {
            try {
                if (!manualCity() && System.currentTimeMillis() - prefs.getLong("geo_time", 0) > 6 * 3600_000L) {
                    val geo = getJson("https://ipwho.is/?fields=success,city,country_code,latitude,longitude")
                    if (geo.optBoolean("success")) prefs.edit()
                        .putString("geo_city", geo.getString("city"))
                        .putString("geo_cc", geo.optString("country_code"))
                        .putFloat("geo_lat", geo.getDouble("latitude").toFloat())
                        .putFloat("geo_lon", geo.getDouble("longitude").toFloat())
                        .putLong("geo_time", System.currentTimeMillis()).apply()
                }
                if (!prefs.contains("geo_lat")) return@execute
                val now = getJson("https://api.open-meteo.com/v1/forecast?latitude=${prefs.getFloat("geo_lat", 0f)}" +
                    "&longitude=${prefs.getFloat("geo_lon", 0f)}&current=temperature_2m,weather_code").getJSONObject("current")
                prefs.edit()
                    .putFloat("wx_temp", now.getDouble("temperature_2m").toFloat())
                    .putInt("wx_code", now.getInt("weather_code"))
                    .putLong("wx_time", System.currentTimeMillis()).apply()
                ui.post { showWeather() }
            } catch (e: Exception) {
                android.util.Log.w("TvHome", "weather failed ($e)") // keep the last reading; next tick retries
            }
        }
    }

    // An IP's location is wherever the provider routes it, sometimes a whole region off (Rome showing as
    // Sicily), so you can pick the city instead. Open-Meteo's geocoding, also free and keyless.

    private fun manualCity() = prefs.getBoolean("geo_manual", false)

    private class City(val name: String, val where: String, val lat: Float, val lon: Float)

    /** Automatic first, then the search box; a search lists what it found under them. */
    private fun pickCity(query: String = "", found: List<City>? = null, status: String = "") {
        val field = EditText(this).apply {
            id = View.generateViewId()
            setText(query)
            setSelection(query.length)
            hint = getString(R.string.city_hint)
            textSize = 14f
            setTextColor(fg)
            setHintTextColor(Color.argb(120, Color.red(fg), Color.green(fg), Color.blue(fg)))
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(14), 0, dp(14), 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), Color.argb(80, Color.red(fg), Color.green(fg), Color.blue(fg)))
            }
            setOnEditorActionListener { v, _, _ -> searchCity(v.text.toString().trim()); true }
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, 0)
            addView(field, LinearLayout.LayoutParams(MATCH_PARENT, dp(44)))
            if (status.isNotEmpty()) addView(label(status, 12f).apply { alpha = 0.6f; setPadding(dp(14), dp(8), 0, 0) })
        }
        val rows = listOf(page(getString(R.string.automatic_ip), if (manualCity()) "" else CHOSEN) {
            prefs.edit().remove("geo_manual").remove("geo_time").remove("wx_time").apply()
            refreshWeather()
            renderSettings(R.string.weather_location)
        }) + found.orEmpty().map { c ->
            page(c.name, c.where) {
                prefs.edit().putBoolean("geo_manual", true).putString("geo_city", c.name)
                    .putFloat("geo_lat", c.lat).putFloat("geo_lon", c.lon).remove("wx_time").apply()
                refreshWeather()
                renderSettings(R.string.weather_location)
            }
        }
        showPage(getString(R.string.weather_location), rows, if (found.isNullOrEmpty()) 0 else 1, top = top) { renderSettings(R.string.weather_location) }
        // showPage pins Up on the first row to itself; here Up goes to the search box.
        settingsList.getChildAt(1).nextFocusUpId = field.id
        if (found.isNullOrEmpty()) field.requestFocus()
        // Results in: put the keyboard away, it would cover them.
        else getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(field.windowToken, 0)
    }

    private fun searchCity(query: String) {
        if (query.isEmpty()) return
        pickCity(query, status = getString(R.string.searching))
        io.execute {
            val found = try {
                val r = getJson("https://geocoding-api.open-meteo.com/v1/search?count=8&format=json" +
                    "&language=${java.util.Locale.getDefault().language}&name=${URLEncoder.encode(query, "UTF-8")}")
                val list = r.optJSONArray("results")
                (0 until (list?.length() ?: 0)).map { i ->
                    val c = list!!.getJSONObject(i)
                    City(c.getString("name"), listOf(c.optString("admin1"), c.optString("country")).filter { it.isNotEmpty() }.joinToString(", "),
                        c.getDouble("latitude").toFloat(), c.getDouble("longitude").toFloat())
                }
            } catch (e: Exception) {
                null
            }
            ui.post {
                // Only if the page is still open: Back may have left it while the search ran.
                if (settingsScrim.visibility != View.VISIBLE || findViewById<TextView>(R.id.settingsTitle).text.toString() != getString(R.string.weather_location)) return@post
                when {
                    found == null -> pickCity(query, status = getString(R.string.search_failed))
                    found.isEmpty() -> pickCity(query, status = getString(R.string.no_places, query))
                    else -> pickCity(query, found)
                }
            }
        }
    }

    private fun showWeather() {
        val view = findViewById<TextView>(R.id.weather)
        val unit = prefs.getString("weather", "off")
        if (unit == "off" || !prefs.contains("wx_temp")) {
            view.visibility = View.GONE
            return
        }
        val c = prefs.getFloat("wx_temp", 0f)
        val temp = Math.round(if (unit == "f") c * 9 / 5 + 32 else c)
        view.text = listOfNotNull("$temp° ${weatherWord(prefs.getInt("wx_code", 0))}".trim(), prefs.getString("geo_city", null))
            .joinToString("  ·  ")
        view.visibility = View.VISIBLE
    }

    /** WMO weather codes, as Open-Meteo reports them. */
    private fun weatherWord(code: Int) = when (code) {
        0 -> getString(R.string.wx_clear)
        1, 2 -> getString(R.string.wx_partly_cloudy)
        3 -> getString(R.string.wx_cloudy)
        45, 48 -> getString(R.string.wx_fog)
        in 51..57 -> getString(R.string.wx_drizzle)
        in 61..67, in 80..82 -> getString(R.string.wx_rain)
        in 71..77, 85, 86 -> getString(R.string.wx_snow)
        in 95..99 -> getString(R.string.wx_thunderstorm)
        else -> ""
    }

    private fun getJson(url: String) = JSONObject(openStream(url).use { it.readBytes().decodeToString() })

    // ---------- continue watching (Android TV Watch Next) ----------

    private fun refreshWatchNext() {
        if (checkSelfPermission(TV_LISTINGS) != PackageManager.PERMISSION_GRANTED) {
            showWatchNext(emptyList())
            return
        }
        io.execute {
            val list = try {
                queryWatchNext()
            } catch (e: Exception) {
                emptyList()
            }
            val all = listOfNotNull(briefing()) + list
            ui.post { showWatchNext(all) }
        }
    }

    /**
     * Two sources, same columns:
     * 1. The system Watch Next list (Netflix etc.), newest engagement first.
     * 2. Any app's own home-screen channel named "Continue Watching". Stremio publishes one (with
     *    progress and an autoPlay resume link) instead of using Watch Next, so signing in to Stremio
     *    on the TV is all it takes. Channel rows carry no timestamps, so they use the time the app was
     *    last opened from Plume, keeping the app's own order among themselves.
     * Newest first across apps, each app's [PER_APP] newest ahead of the rest, so one app with ten shows can't bury the others.
     */
    private fun queryWatchNext(): List<Prog> {
        val hidden = prefs.getStringSet("hidden_watch", emptySet())!!
        val watchNext = readPrograms(TvContract.WatchNextPrograms.CONTENT_URI, hidden)
        val channels = continueChannels().flatMap { id ->
            readPrograms(TvContract.buildPreviewProgramsUriForChannel(id), hidden).map { (_, p) -> watchSortKey(0, prefs.getLong("t_${p.pkg}", 0)) to p }
        }
        val all = watchNext + channels
        publishingPkgs = all.mapNotNull { it.second.pkg }.toSet()
        val off = prefs.getStringSet("cw_off", emptySet())!!
        return newestPerApp(all.filter { it.second.pkg !in off }.distinctBy { it.second.intent ?: it.second.title }, PER_APP) { it.pkg }
            .take(20)
    }

    /** Apps last seen publishing, so the settings list includes them even without the permission check. */
    @Volatile private var publishingPkgs = emptySet<String>()

    /**
     * Apps that can feed Continue Watching: anything allowed to write TV listings, plus anything
     * actually publishing now. Built from installed apps each time, so new apps appear on their own.
     */
    private fun watchSources(): List<App> = apps.filter { a ->
        a.pkg in publishingPkgs || try {
            packageManager.getPackageInfo(a.pkg, PackageManager.GET_PERMISSIONS)
                .requestedPermissions?.contains(WRITE_EPG) == true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }.sortedBy { it.label.lowercase() }

    private fun pickWatchSources() {
        val sources = watchSources()
        if (sources.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_cw_apps), Toast.LENGTH_SHORT).show()
            return
        }
        toggles(getString(R.string.cw_apps), sources.map { it.label to it.pkg }, "cw_off", getString(R.string.on), getString(R.string.off)) {
            watchKey = null
            refreshWatchNext()
            renderSettings(R.string.cw_apps)
        }
    }

    private fun continueChannels(): List<Long> {
        val ids = ArrayList<Long>()
        contentResolver.query(TvContract.Channels.CONTENT_URI, null, null, null, null)?.use { c ->
            val name = c.getColumnIndex("display_name")
            val pkg = c.getColumnIndex("package_name")
            val id = c.getColumnIndex("_id")
            while (c.moveToNext()) {
                if (c.getString(name).equals("Continue Watching", ignoreCase = true) && c.getString(pkg) != packageName) {
                    ids += c.getLong(id)
                }
            }
        }
        return ids
    }

    /** Pairs of (sort key, item), see [watchSortKey]; channel rows have no time of their own and get the type "continue". */
    private fun readPrograms(uri: Uri, hidden: Set<String>): List<Pair<Long, Prog>> {
        val out = ArrayList<Pair<Long, Prog>>()
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            fun str(col: String) = c.getColumnIndex(col).let { if (it < 0 || c.isNull(it)) null else c.getString(it) }
            fun num(col: String) = c.getColumnIndex(col).let { if (it < 0 || c.isNull(it)) 0L else c.getLong(it) }
            while (c.moveToNext()) {
                if (str("browsable") == "0") continue
                val title = str("title") ?: continue
                if (hideKey(str("package_name"), title) in hidden) continue
                val season = str("season_display_number")
                val episode = str("episode_display_number")
                val duration = num("duration_millis")
                val position = num("last_playback_position_millis")
                val sub = listOfNotNull(
                    if (season != null && episode != null) getString(R.string.season_episode, season, episode) else null,
                    // Under a second in means "not really started": no "time left", no bar.
                    if (duration > 0 && position in 1000 until duration) timeLeft(duration - position) else null,
                ).joinToString(" - ")
                val progress = if (duration > 0 && position >= 1000) (position * 100 / duration).toInt().coerceIn(0, 100) else 0
                val poster = str("poster_art_uri")
                val thumb = str("thumbnail_uri")
                // Aspect ratio codes: 0 16:9, 1 3:2, 2 4:3 are landscape; missing (-1) is usually landscape too.
                fun wide(col: String) = c.getColumnIndex(col).let { if (it < 0 || c.isNull(it)) -1 else c.getInt(it) } in -1..2
                val art = when {
                    poster != null && wide("poster_art_aspect_ratio") -> poster
                    thumb != null && wide("thumbnail_aspect_ratio") -> thumb
                    else -> poster ?: thumb
                }
                val prog = Prog(title, sub, progress, thumb ?: poster, str("intent_uri"), str("package_name"), art, if (art == poster) thumb else poster)
                out += watchSortKey(num("watch_next_type"), num("last_engagement_time_utc_millis")) to prog
            }
        }
        return out
    }

    private fun hideKey(pkg: String?, title: String) = "$pkg/$title"

    /** We can't delete another app's Watch Next row, so hiding is a local filter. */
    private fun hideWatch(p: Prog) {
        val hidden = prefs.getStringSet("hidden_watch", emptySet())!! + hideKey(p.pkg, p.title)
        prefs.edit().putStringSet("hidden_watch", hidden).apply()
        Toast.makeText(this, getString(R.string.hidden_from_cw), Toast.LENGTH_SHORT).show()
        refreshWatchNext()
    }

    /** Small source-app mark for the details line: black logo when we have one, else greyscale icon. */
    private fun sourceIcon(pkg: String?): Drawable? {
        if (pkg == null) return null
        LOGOS[pkg]?.takeIf { !it.second }?.let { return getDrawable(it.first)!!.mutate().apply { setTint(fg) } }
        return try {
            packageManager.getApplicationIcon(pkg).mutate().apply { colorFilter = GREYSCALE }
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    private fun timeLeft(ms: Long): String {
        val min = ms / 60_000
        return if (min >= 60) getString(R.string.time_left_hm, min / 60, min % 60) else getString(R.string.time_left_m, min)
    }

    // ---------- Top Shelf (tvOS): Continue Watching full screen, one item at a time ----------

    private var watchList = emptyList<Prog>()

    private fun showWatchNext(list: List<Prog>) {
        val key = list.joinToString("|") { "${it.title}/${it.sub}/${it.progress}/${it.art}" }
        if (key == watchKey) { // unchanged: keep the shelf where it is, just fetch a picture that never arrived
            retryPosters()
            return
        }
        watchKey = key
        val was = watchList.getOrNull(shelfAt)
        watchList = list
        // Stay on the same show if it's still there (its progress may have changed), else start at the newest.
        shelfAt = was?.let { w -> list.indexOfFirst { it.title == w.title && it.pkg == w.pkg } }?.takeIf { it >= 0 } ?: 0
        if (list.isEmpty()) {
            val had = shelf.hasFocus()
            shelf.visibility = View.GONE
            if (had) appRow.getChildAt(0)?.requestFocus()
            ui.removeCallbacks(shelfTick)
            showBackdrop(wallpaperBmp, wallpaperSmall)
            return
        }
        if (shelf.visibility != View.VISIBLE) {
            shelf.visibility = View.VISIBLE
            shelf.alpha = 0f // fade only: its height above the dock is dockSize's (translationY)
            shelf.animate().alpha(1f).setDuration(500).setInterpolator(Motion.S).start()
        }
        bindShelf(0)
        scheduleShelfTick()
    }

    private fun setupGlass() {
        backdrop = findViewById(R.id.backdrop)
        shelf = findViewById(R.id.shelf)
        dock = findViewById(R.id.dock)
        findViewById<View>(R.id.scrim).visibility = View.GONE // no vignette: the picture stays as the glass sees it
        softShadows()
        // The dock: blurred copy of the backdrop at the back, the apps on top, a lit rim in front.
        val radius = dpf(28f)
        glass = GlassView(this)
        dock.addView(glass, 0, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        glass.corner = radius // drawn in shape: no clip on the dock
        dock.foreground = Glass.rim(radius, resources.displayMetrics.density)
        // The top buttons sit on a small glass capsule of their own.
        val cap = findViewById<FrameLayout>(R.id.topGlass)
        topGlass = GlassView(this).apply { capsule = true }
        cap.addView(topGlass, 0, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        cap.foreground = GradientDrawable().apply {
            cornerRadius = dpf(40f)
            setColor(0)
            setStroke(maxOf(1, dp(1)), 0x38FFFFFF)
        }
        setupDrawer()
        // The shelf stands on the resting dock: its Resume row stays put while the title above grows or shrinks.
        (shelf.layoutParams as FrameLayout.LayoutParams).bottomMargin = dockRestHeight() + dp(SHELF_GAP)
        val playGlass = findViewById<FrameLayout>(R.id.playGlass)
        playButtonGlass = GlassView(this).apply { capsule = true }
        playGlass.addView(playButtonGlass, 0, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setupSheet()
        dockLayout()
        dockSize(0f)
        lens = FocusLens(this, tileRadius() + dp(LENS_PAD))
        findViewById<FrameLayout>(R.id.lensLayer).addView(lens, 0, FrameLayout.LayoutParams(0, 0))
        // The shelf is one focusable: OK resumes, holding OK opens the hide / open app menu.
        shelf.setOnClickListener { watchList.getOrNull(shelfAt)?.let { open(it) } }
        shelf.setOnLongClickListener { if (!holdFired) fireHold(shelf); true }
        shelf.setOnFocusChangeListener { _, focused -> styleShelf(focused) }
        findViewById<ProgressBar>(R.id.shelfProgress).progressDrawable = progressBarDrawable()
        styleShelf(false)
    }

    /** A small lift under the progress bar, so it reads on bright pictures too. */
    private fun softShadows() {
        findViewById<View>(R.id.shelfProgress).elevation = dpf(2f)
    }

    /** Resume button: glass while the shelf rests, solid white with dark text once it's focused (tvOS buttons). */
    private fun styleShelf(focused: Boolean) {
        val play = findViewById<TextView>(R.id.shelfPlay)
        val glassBox = findViewById<View>(R.id.playGlass)
        val ink = if (focused) 0xFF0A0A0A.toInt() else DARK_TEXT
        // Frosted glass with a light rim at rest; focused, bright white glass over the frost, dark text (tvOS).
        play.background = GradientDrawable().apply {
            cornerRadius = dpf(18f)
            setColor(if (focused) 0xE6FFFFFF.toInt() else 0x14FFFFFF)
            setStroke(1, if (focused) 0x00FFFFFF else 0x66FFFFFF)
        }
        play.setTextColor(ink)
        play.setCompoundDrawablesRelativeWithIntrinsicBounds(playGlyph(ink), null, null, null)
        play.compoundDrawablePadding = dp(8)
        glassBox.pivotX = 0f
        glassBox.pivotY = glassBox.height / 2f
        glassBox.animate().scaleX(if (focused) 1.1f else 1f).scaleY(if (focused) 1.1f else 1f)
            .setDuration(Motion.FOCUS_MS).setInterpolator(Motion.S).start()
    }

    private fun playGlyph(color: Int) = ShapeDrawable(PathShape(Path().apply {
        moveTo(0f, 0f); lineTo(10f, 6f); lineTo(0f, 12f); close()
    }, 10f, 12f)).apply {
        paint.color = color
        paint.isAntiAlias = true
        intrinsicWidth = dp(9)
        intrinsicHeight = dp(11)
    }

    /**
     * Shows the current item. [dir] is the way the page turned (-1 left, 1 right, 0 no animation): the text
     * slides out that way and the next item's text slides in from the other side, a line at a time, while the
     * backdrop crossfades underneath.
     */
    private fun bindShelf(dir: Int) {
        val p = watchList.getOrNull(shelfAt) ?: return
        val src = findViewById<TextView>(R.id.shelfSource)
        val title = findViewById<TextView>(R.id.shelfTitle)
        val sub = findViewById<TextView>(R.id.shelfSub)
        val bar = findViewById<ProgressBar>(R.id.shelfProgress)
        val parts = listOf(src, title, sub, bar)
        val rest = mapOf<View, Float>(src to 1f, title to 1f, sub to 1f, bar to 1f) // softer lines get it from their colour (TINTED)
        val fill = { glideShelfHeight {
            src.text = if (p.intent == NEWS_INTENT) getString(R.string.news_briefing) else apps.firstOrNull { it.pkg == p.pkg }?.label.orEmpty()
            findViewById<TextView>(R.id.shelfPlay).text = getString(if (p.intent == NEWS_INTENT) R.string.news_read else R.string.resume)
            title.text = p.title
            sub.text = p.sub
            sub.visibility = if (p.sub.isEmpty()) View.GONE else View.VISIBLE
            bar.progress = p.progress
            bar.visibility = if (p.progress > 0) View.VISIBLE else View.GONE
            shelf.contentDescription = p.title
            shelf.setTag(R.id.shelf, p) // holding OK hides it (see onHold)
            showDots()
            ui.post { tintTexts() }
        } }
        val swap = ++shelfSwap
        if (dir == 0) {
            parts.forEach { it.animate().cancel(); it.alpha = rest[it]!!; it.translationX = 0f }
            fill()
        } else {
            parts.forEach {
                it.animate().alpha(0f).translationX(-dir * dpf(24f)).setStartDelay(0).setDuration(140)
                    .setInterpolator(Motion.STANDARD).start()
            }
            ui.postDelayed({
                if (swap != shelfSwap) return@postDelayed // another turn came in meanwhile; it does the text
                fill()
                parts.forEachIndexed { i, v ->
                    v.translationX = dir * dpf(32f)
                    v.animate().alpha(rest[v]!!).translationX(0f).setStartDelay(i * 35L).setDuration(380)
                        .setInterpolator(Motion.EMPHASIZED).start()
                }
            }, 150)
        }
        loadHero(p)
        // Fetch the neighbours now, so turning the page shows their picture straight away.
        watchList.getOrNull(shelfAt + 1)?.let { prefetchHero(it) }
        watchList.getOrNull(shelfAt - 1)?.let { prefetchHero(it) }
    }

    /** iOS page dots: one per item (up to 12), the current one a longer pill. */
    private fun showDots() {
        val row = findViewById<LinearLayout>(R.id.shelfDots)
        row.removeAllViews()
        val n = minOf(watchList.size, 12)
        if (n < 2) return
        for (i in 0 until n) {
            val on = i == shelfAt.coerceAtMost(n - 1)
            row.addView(View(this).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dpf(3f)
                    setColor(if (on) 0xFFFFFFFF.toInt() else 0x8CFFFFFF.toInt())
                    setStroke(1, 0x40000000) // a fine dark edge, so the dots show on white pictures too
                }
                elevation = dpf(2f)
            }, LinearLayout.LayoutParams(dp(if (on) 18 else 6), dp(6)).apply { if (i > 0) marginStart = dp(6) })
        }
    }

    private fun shelfStep(d: Int) {
        val next = shelfAt + d
        if (next !in watchList.indices) { // at the end: a small bump, like tvOS
            shelf.animate().translationX(d * dpf(10f)).setDuration(90).setInterpolator(Motion.STANDARD)
                .withEndAction { shelf.animate().translationX(0f).setDuration(260).setInterpolator(Motion.EMPHASIZED).start() }
                .start()
            return
        }
        shelfAt = next
        bindShelf(d)
    }

    /** While focus is elsewhere (the dock), the shelf turns on its own every few seconds, after the remote goes quiet. */
    private val shelfTick = object : Runnable {
        override fun run() {
            if (watchList.size > 1 && !shelf.hasFocus() && SystemClock.uptimeMillis() - lastKeyAt > 6_000L &&
                settingsScrim.visibility != View.VISIBLE && allApps.visibility != View.VISIBLE
            ) {
                shelfAt = (shelfAt + 1) % watchList.size
                bindShelf(1)
            }
            ui.postDelayed(this, SHELF_EVERY)
        }
    }

    private fun scheduleShelfTick() {
        ui.removeCallbacks(shelfTick)
        if (watchList.size > 1) ui.postDelayed(shelfTick, SHELF_EVERY)
    }

    private fun showBackdrop(big: Bitmap?, small: Bitmap?) {
        backdrop.show(big)
        glass.show(small)
        topGlass.show(small)
        playButtonGlass.show(small)
        sheetGlass.show(small)
        drawerGlass.show(small)
        tintSource = small
        ui.post { tintTexts() } // after layout, so each text knows where it sits
    }

    /**
     * Colours the text over the artwork from the picture behind it (see tintFor): a pale tint of the picture's
     * colour on dark artwork, a deep shade on bright artwork, always at least 4.5:1 contrast. Text that belongs
     * together shares one colour, taken from the picture behind all of it: the clock, date and weather; and the
     * app name, title and time left of the shelf. Eases to a new colour on the S-curve when the artwork changes; a
     * soft shadow (light text) or glow (dark text) keeps edges clean on busy pictures.
     */
    private fun tintTexts() {
        val small = tintSource
        val at = IntArray(2)
        val rootAt = IntArray(2)
        for (group in TINTED) {
            val views = group.mapNotNull { findViewById<TextView>(it) }.filter { it.visibility == View.VISIBLE && it.width > 0 }
            if (views.isEmpty()) continue
            val bg = if (small == null) Color.BLACK else {
                // The group's background: each line's average, weighted by its size.
                var r = 0.0; var g = 0.0; var b = 0.0; var total = 0.0
                for (v in views) {
                    v.getLocationInWindow(at)
                    v.rootView.getLocationInWindow(rootAt)
                    val c = backdropColor(small, v, at, rootAt)
                    val area = v.width.toDouble() * v.height
                    r += Color.red(c) * area; g += Color.green(c) * area; b += Color.blue(c) * area; total += area
                }
                Color.rgb((r / total).toInt(), (g / total).toInt(), (b / total).toInt())
            }
            val ink = tintFor(bg)
            val light = relativeLuminance(ink) > 0.5
            for (v in views) {
                v.setShadowLayer(dpf(8f), 0f, dpf(1f), if (light) 0x59000000 else 0x40FFFFFF)
                tintAnims[v]?.cancel()
                if (v.currentTextColor == ink) continue
                tintAnims[v] = ValueAnimator.ofArgb(v.currentTextColor, ink).apply {
                    duration = 600
                    interpolator = Motion.S
                    addUpdateListener { v.setTextColor(it.animatedValue as Int) }
                    start()
                }
            }
        }
    }

    private fun loadHero(p: Prog) {
        val uri = p.art ?: p.image ?: return showBackdrop(wallpaperBmp, wallpaperSmall)
        heroes.get(uri)?.let { return showBackdrop(it.first, it.second) }
        fetchHero(uri, heroCandidates(p)) { pair -> if (watchList.getOrNull(shelfAt)?.let { it.art ?: it.image } == uri) showBackdrop(pair.first, pair.second) }
    }

    private fun prefetchHero(p: Prog) {
        val uri = p.art ?: p.image ?: return
        if (heroes.get(uri) == null) fetchHero(uri, heroCandidates(p)) {}
    }

    /** One download per picture: later askers (the shelf landing on a prefetching item) wait for the same one. */
    private fun fetchHero(uri: String, candidates: List<String>, then: (Pair<Bitmap, Bitmap>) -> Unit) {
        heroWaiters[uri]?.let { it += then; return }
        heroWaiters[uri] = mutableListOf(then)
        posterIo.execute {
            val pair = try {
                bestHero(candidates)?.let { it to smallCopy(it) }
            } catch (e: Exception) {
                android.util.Log.w("TvHome", "artwork failed: $uri ($e)")
                null
            } catch (e: OutOfMemoryError) {
                null
            }
            ui.post {
                val waiting = heroWaiters.remove(uri) ?: return@post
                if (pair == null) return@post // retried by retryPosters() when the network comes back
                heroes.put(uri, pair)
                waiting.forEach { it(pair) }
            }
        }
    }

    /**
     * Pictures to try for an item, sharpest first: each link asked for at screen size (see [hiResArt]), then as the
     * app gave it, for the app's chosen picture and then its other one.
     */
    private fun heroCandidates(p: Prog): List<String> =
        listOfNotNull(p.art ?: p.image, p.alt).flatMap { hiResArt(it, resources.displayMetrics.widthPixels) + it }.distinct()

    /**
     * The first candidate at least [HERO_MIN_WIDTH] px wide; failing that, the widest one that loaded. Stops after
     * [HERO_TRIES] downloads so a slow server can't hold the shelf up.
     */
    private fun bestHero(candidates: List<String>): Bitmap? {
        var best: Bitmap? = null
        for (u in candidates.take(HERO_TRIES)) {
            val b = try { decodeHero(u) } catch (e: Exception) { null } ?: continue
            android.util.Log.i("TvHome", "artwork ${b.width}x${b.height}: $u")
            if (best == null || b.width > best.width) best = b
            if (b.width >= HERO_MIN_WIDTH || u.startsWith("content:")) break
        }
        return best
    }

    /** Full-screen artwork: full colour (posters band in RGB_565 when this large), no bigger than the screen. */
    private fun decodeHero(uri: String): Bitmap? {
        val bytes = openStream(uri).use { it.readBytes() }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val target = resources.displayMetrics.widthPixels
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= target) sample *= 2
        val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        // Never keep more than the screen needs: a 2862 px poster is 18 MB in memory, at 1920 px it's 8 MB.
        if (b.width <= target) return b
        return Bitmap.createScaledBitmap(b, target, maxOf(1, b.height * target / b.width), true).also { if (it !== b) b.recycle() }
    }

    /** The glass only ever shows a heavy blur of the picture, so a tiny copy looks the same and costs ~200 KB. */
    private fun smallCopy(b: Bitmap): Bitmap {
        val small = Bitmap.createScaledBitmap(b, 320, maxOf(1, 320 * b.height / b.width), true)
        // Blurred once here, as strong as the old live blur (~56 px at screen size), so the glass never blurs live.
        return blurred(small, 8).also { if (it !== small) small.recycle() }
    }

    /**
     * Dock tile, tvOS style: the app's own Android TV banner (full colour, 16:9), or its logo on a glass tile
     * when it has none (HDMI inputs, phone apps). Lifts, tilts in and catches a sheen of light on focus.
     */
    private fun dockTile(a: App, radius: Float = tileRadius()): View {
        val frame = FrameLayout(this)
        val img = ImageView(this)
        val art = customIcon(a.pkg) ?: a.banner?.let { it.constantState?.newDrawable(resources) ?: it }
        if (art != null) {
            // Artwork painted into the squircle itself: no clipping needed (see TileArt).
            frame.background = TileArt(art, radius, 0xFF1C1C1E.toInt())
        } else {
            val logo = LOGOS[a.pkg]
            val mark = when {
                logo != null -> getDrawable(logo.first)!!.mutate().apply { setTint(BRAND_COLOURS[a.pkg] ?: DARK_TEXT) }
                a.pkg.startsWith(HDMI) -> (a.icon.constantState?.newDrawable(resources) ?: a.icon).mutate().apply { setTint(DARK_TEXT) }
                else -> a.icon.constantState?.newDrawable(resources) ?: a.icon
            }
            img.setImageDrawable(mark)
            img.scaleType = ImageView.ScaleType.FIT_CENTER
            frame.background = TileArt(null, radius, 0x40FFFFFF, 0x1AFFFFFF)
            frame.addView(img, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply { setMargins(dp(34), dp(18), dp(34), dp(18)) })
        }
        focusLift(frame, radius)
        frame.tag = a.pkg
        frame.contentDescription = a.label
        frame.setOnClickListener { launch(a) }
        return frame
    }

    /**
     * Focus on a dock tile, iOS glass style: the lens glides in behind it (see moveLens), the tile rises a little
     * with a soft shadow, and a band of light sweeps across it in the direction focus moved. Rested tiles dim slightly.
     */
    private fun focusLift(v: View, radius: Float) {
        val sheen = SheenDrawable(radius, resources.displayMetrics.density)
        v.foreground = sheen // drawn in the tile's shape, like its background: no clip
        v.cameraDistance = 9000 * resources.displayMetrics.density
        v.alpha = REST_ALPHA
        v.isFocusable = true
        v.isClickable = true
        v.setOnLongClickListener { if (!holdFired) fireHold(v); true }
        var anim: ValueAnimator? = null
        v.setOnFocusChangeListener { _, focused ->
            if (focused && lastDir != 0 && !editing) v.rotationY = -lastDir * 6f
            anim?.cancel()
            val from = sheen.level
            val dir = if (lastDir < 0) -1 else 1
            anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = if (focused) 900 else 220
                interpolator = Motion.EMPHASIZED
                addUpdateListener {
                    val f = it.animatedValue as Float
                    sheen.level = if (focused) from + (1f - from) * minOf(1f, f * 3f) else from * (1f - f)
                    if (focused) sheen.progress = if (dir > 0) f else 1f - f
                }
                start()
            }
            v.animate()
                .scaleX(if (focused) FOCUS_SCALE else 1f).scaleY(if (focused) FOCUS_SCALE else 1f)
                .rotationY(0f)
                .alpha(if (focused) 1f else REST_ALPHA)
                .setDuration(Motion.FOCUS_MS + 60).setInterpolator(Motion.EMPHASIZED).start()
        }
    }

    /** The glass lens follows focus along the dock and fades away when focus leaves it. */
    private fun moveLens(v: View, focused: Boolean) {
        if (!focused) {
            ui.post { if (currentFocus?.parent !== appRow) { lens.hide(); openDock(false) } }
            return
        }
        openDock(true)
        if (editing) return lens.hide()
        val place = {
            val row = appRow.parent as View
            lens.moveTo(row.left + appRow.left + v.left - dp(LENS_PAD).toFloat(), (row.top + appRow.top + v.top - dp(LENS_PAD)).toFloat())
        }
        if (v.width > 0) place() else v.post { if (v.isFocused) place() }
    }

    private fun dpf(v: Float) = v * resources.displayMetrics.density

    /**
     * Arrow keys in All apps: the list scrolls by moving focus itself, which Android does without the navigation
     * click, so focus is moved here instead (the list still scrolls to it). True when handled; null for other keys.
     */
    private fun drawerNav(key: Int): Boolean? {
        val dir = when (key) {
            KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
            else -> return null
        }
        val from = currentFocus ?: return null
        val next = android.view.FocusFinder.getInstance().findNextFocus(allAppsGrid, from, dir) ?: return true // edge: stay
        navSound(key)
        next.requestFocus()
        return true
    }

    // ---------- news ----------

    /** Fetches the news in the background if it's stale; the morning briefing on the shelf updates when it lands. */
    @Volatile private var fetchingNews = false

    private fun refreshNews() {
        if (fetchingNews) return
        fetchingNews = true
        // Its own thread: a slow feed must not hold up the shelf, apps or wallpaper on the io thread.
        Thread {
            try {
                val before = prefs.getLong("news_time", 0)
                News.refresh(this, prefs)
                if (prefs.getLong("news_time", 0) != before && briefingHours()) ui.post { watchKey = null; refreshWatchNext() }
            } finally {
                fetchingNews = false
            }
        }.start()
    }

    private fun briefingHours() = Calendar.getInstance().get(Calendar.HOUR_OF_DAY) in BRIEFING_HOURS

    /**
     * In the morning, the first card on the shelf: the top story, with how many are waiting. OK opens the news
     * screen. Turned off in settings (News → Morning briefing). Read from the saved news, so it's instant.
     */
    private fun briefing(): Prog? {
        if (!prefs.getBoolean("news_morning", true) || !briefingHours()) return null
        val sections = News.load(this)
        val top = sections.firstOrNull()?.stories?.firstOrNull() ?: return null
        if (hideKey(packageName, top.title) in prefs.getStringSet("hidden_watch", emptySet())!!) return null
        return Prog(top.title, getString(R.string.news_briefing_sub, sections.sumOf { it.stories.size }), 0, null, NEWS_INTENT, packageName)
    }

    private fun pickEdition() {
        val current = News.edition(prefs).ceid
        showPage(getString(R.string.news_region), News.EDITIONS.map { e ->
            page(e.name, if (e.ceid == current) CHOSEN else "") {
                prefs.edit().putString("news_edition", e.ceid).apply()
                refreshNews()
                renderSettings(R.string.news_region)
            }
        }, News.EDITIONS.indexOfFirst { it.ceid == current }.coerceAtLeast(0)) { renderSettings(R.string.news_region) }
    }

    /** Topics page: the standard sections (on/off), then your own topics (OK removes one), then "Add a topic…". */
    private fun pickTopics(focus: Int = 0) {
        if (!prefs.contains("news_off")) prefs.edit().putStringSet("news_off", News.DEFAULT_OFF).apply()
        val off = prefs.getStringSet("news_off", emptySet())!!
        val standard = News.TOPICS.mapIndexed { i, t ->
            page(News.sectionName(t, prefs), getString(if (t.key in off) R.string.off else R.string.on)) {
                prefs.edit().putStringSet("news_off", if (t.key in off) off - t.key else off + t.key).apply()
                pickTopics(i)
            }
        }
        val mine = News.custom(prefs).mapIndexed { i, t ->
            page(t.label, getString(R.string.news_remove)) {
                News.removeCustom(prefs, t.label)
                pickTopics(standard.size + i)
            }
        }
        val rows = standard + mine + page(getString(R.string.news_add_topic) + "…") { addTopic() }
        showPage(getString(R.string.news_topics), rows, focus) {
            refreshNews()
            renderSettings(R.string.news_topics)
        }
    }

    /** Type a topic of your own: anything Google News can search. */
    private fun addTopic() {
        val field = EditText(this).apply {
            id = View.generateViewId()
            hint = getString(R.string.news_topic_hint)
            textSize = 14f
            setTextColor(fg)
            setHintTextColor(Color.argb(120, Color.red(fg), Color.green(fg), Color.blue(fg)))
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            imeOptions = EditorInfo.IME_ACTION_DONE
            setPadding(dp(14), 0, dp(14), 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), Color.argb(80, Color.red(fg), Color.green(fg), Color.blue(fg)))
            }
            setOnEditorActionListener { v, _, _ ->
                val term = v.text.toString().trim()
                if (term.isNotEmpty()) News.addCustom(prefs, term)
                getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(windowToken, 0)
                pickTopics(News.TOPICS.size + News.custom(prefs).size - 1)
                true
            }
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, 0)
            addView(field, LinearLayout.LayoutParams(MATCH_PARENT, dp(44)))
        }
        showPage(getString(R.string.news_add_topic), emptyList(), top = top) { pickTopics(News.TOPICS.size + News.custom(prefs).size) }
        field.requestFocus()
    }

    private fun navSound(key: Int) = window.decorView.playSoundEffect(when (key) {
        KeyEvent.KEYCODE_DPAD_LEFT -> SoundEffectConstants.NAVIGATION_LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> SoundEffectConstants.NAVIGATION_RIGHT
        KeyEvent.KEYCODE_DPAD_UP -> SoundEffectConstants.NAVIGATION_UP
        else -> SoundEffectConstants.NAVIGATION_DOWN
    })

    /**
     * The dock rests compact, the same glass above and below the tiles. With focus in it, it grows upwards to make
     * room for the lens around the focused tile and the app's name under it, and shrinks back when focus leaves.
     */
    private fun openDock(open: Boolean) {
        val to = if (open) 1f else 0f
        if (dockOpen == to) return
        dockAnim?.cancel()
        dockAnim = ValueAnimator.ofFloat(dockOpen.coerceAtLeast(0f), to).apply {
            duration = 420
            interpolator = Motion.EMPHASIZED
            addUpdateListener { dockSize(it.animatedValue as Float) }
            start()
        }
        dockOpen = to
    }

    /**
     * [f] 0 = resting, 1 = open. Resting, the dock is scaled down from its bottom centre like the macOS Dock;
     * opening grows it back. Only scale and position change, never layout, so the GPU animates it smoothly.
     */
    private fun dockSize(f: Float) {
        if (dockAnim?.isRunning != true) dockOpen = f
        val scale = DOCK_REST_SCALE + (1f - DOCK_REST_SCALE) * f
        dock.pivotX = dock.width / 2f
        dock.pivotY = dock.height.toFloat()
        dock.scaleX = scale
        dock.scaleY = scale
        shelfSize(f)
        // The glass follows the motion frame by frame (its picture stays lined up with the screen while it scales),
        // rather than snapping into place when the motion ends.
        glass.invalidate()
        playButtonGlass.invalidate()
    }

    /** The dock's one fixed layout: logos only, the same glass above and below, room for the lens. */
    private fun dockLayout() {
        findViewById<View>(R.id.appScroll).setPadding(dp(24), dp(DOCK_OPEN_PAD), dp(24), dp(DOCK_OPEN_PAD))
        for (id in intArrayOf(R.id.rowLabel, R.id.nameRow)) {
            (findViewById<View>(id).layoutParams as LinearLayout.LayoutParams).apply { height = 0; topMargin = 0 }
        }
        // Pivots need the laid-out size; set them (and line the glass up) whenever it changes.
        dock.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            dock.pivotX = dock.width / 2f
            dock.pivotY = dock.height.toFloat()
            glass.invalidate()
        }
        shelf.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> shelf.pivotY = shelf.height.toFloat() }
    }

    /**
     * The shelf answers the dock: full size while the dock rests, and as the dock opens it shrinks towards its
     * bottom-left corner and rises by exactly what the dock grows, so the Resume row keeps its distance above the
     * dock. Transform only, like the dock. [f] as in [dockSize].
     */
    private fun shelfSize(f: Float) {
        val s = 1f - (1f - SHELF_OPEN_SCALE) * f
        shelf.pivotX = 0f
        shelf.pivotY = shelf.height.toFloat()
        shelf.scaleX = s
        shelf.scaleY = s
        shelf.translationY = -(dockOpenHeight() - dockRestHeight()) * f
    }

    /** What the dock takes up from the bottom of the content area, resting (small, scaled) and open. */
    private fun dockRestHeight() = (dockOpenHeight() * DOCK_REST_SCALE).toInt()
    private fun dockOpenHeight() = dp(DOCK_OPEN_PAD) * 2 + tileWidth() * 9 / 16

    /**
     * When a new show's title takes a different number of lines, the shelf (standing on its Resume row) changes
     * height and the title and app name jump. Instead they glide there: measure the change after layout and slide
     * them from where they were.
     */
    private fun glideShelfHeight(change: () -> Unit) {
        val before = shelf.height
        change()
        if (before == 0) return
        shelf.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                shelf.viewTreeObserver.removeOnPreDrawListener(this)
                val delta = (shelf.height - before).toFloat()
                if (delta != 0f) for (id in intArrayOf(R.id.shelfSource, R.id.shelfTitle)) {
                    findViewById<View>(id).apply {
                        translationY = delta
                        animate().translationY(0f).setDuration(420).setInterpolator(Motion.S).start()
                    }
                }
                shelf.pivotY = shelf.height.toFloat()
                return true
            }
        })
    }

    /** Settings as a floating frosted sheet on the right, like an iOS sidebar, with the dock's glass and rim. */
    private fun setupSheet() {
        val scrim = settingsScrim as FrameLayout
        val panel = findViewById<View>(R.id.settingsPanel)
        scrim.setBackgroundColor(0x40000000)
        scrim.removeView(panel)
        val sheet = FrameLayout(this)
        sheetGlass = GlassView(this).apply { tint = 0x73141418; corner = dpf(28f) }
        sheet.addView(sheetGlass, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        sheet.addView(panel, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val radius = dpf(28f)
        Glass.round(sheet, radius)
        sheet.foreground = Glass.rim(radius, resources.displayMetrics.density)
        scrim.addView(sheet, FrameLayout.LayoutParams(dp(380), MATCH_PARENT, Gravity.END).apply {
            setMargins(0, dp(20), dp(20), dp(20))
        })
    }

    /** A focused settings row on the glass sheet: brighter glass with a hairline rim, not a dark card. */
    private fun glassRow(): Drawable = GradientDrawable().apply {
        cornerRadius = dpf(12f)
        setColor(0x33FFFFFF)
        setStroke(1, 0x40FFFFFF)
    }

    /** DOCK_FIT 16:9 tiles exactly fill the glass panel (content width, plus the dock's 16 dp overhang each side, minus its 24 dp padding). */
    private fun tileWidth() = (resources.displayMetrics.widthPixels - dp(SCREEN_PAD) * 2 + dp(32) - dp(48) - dp(DOCK_GAP) * (DOCK_FIT - 1)) / DOCK_FIT

    /** Apple TV icon roundness: about a quarter of the tile's height, on continuous corners. */
    private fun tileRadius() = tileWidth() * 9 / 16 * 0.24f

    private fun progressBarDrawable(): Drawable {
        fun bar(color: Int) = GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(color) }
        return LayerDrawable(arrayOf(
            bar(if (dark) 0x40FFFFFF else 0x26000000),
            ClipDrawable(bar(fg), Gravity.START, ClipDrawable.HORIZONTAL),
        )).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
        }
    }

    private fun open(p: Prog) {
        if (p.intent == NEWS_INTENT) return startActivity(Intent(this, NewsActivity::class.java))
        prefs.edit().putLong("t_${p.pkg}", System.currentTimeMillis()).apply()
        try {
            startActivity(Intent.parseUri(p.intent, Intent.URI_INTENT_SCHEME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            p.pkg?.let { packageManager.getLeanbackLaunchIntentForPackage(it) }?.let { startActivity(it) }
        }
    }

    private fun loadPoster(v: ImageView, uri: String?) {
        if (uri == null) return
        posters.get(uri)?.let { v.setImageBitmap(it); return }
        v.tag = uri
        if (!loading.add(uri)) return // already on its way
        posterIo.execute {
            val bmp = try {
                decode(uri)
            } catch (e: Exception) {
                android.util.Log.w("TvHome", "poster failed: $uri ($e)") // retried by retryPosters()
                null
            } finally {
                loading.remove(uri)
            } ?: return@execute
            posters.put(uri, bmp)
            ui.post { if (v.tag == uri) v.setImageBitmap(bmp) }
        }
    }

    /** The shelf's picture never arrived (offline, server timeout): try again. Loaded ones are left alone. */
    private fun retryPosters() {
        watchList.getOrNull(shelfAt)?.let { loadHero(it) }
    }

    /** Downsample to card size in RGB_565 so a dozen posters stay around 1-2 MB. */
    private fun decode(uri: String): Bitmap? {
        val bytes = openStream(uri).use { it.readBytes() }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= dp(220)) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private fun openStream(uri: String): InputStream =
        if (uri.startsWith("http")) {
            (URL(uri).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
            }.inputStream
        } else {
            contentResolver.openInputStream(Uri.parse(uri)) ?: throw IOException("no stream for $uri")
        }

    // ---------- shared view bits ----------

    /**
     * A dark-mode card on true black: near-black glass with a hairline rim, brighter along the top edge as if
     * lit from above. [lit] is the focused look: brighter glass and rim.
     */
    private fun rimCard(radius: Int, lit: Boolean): Drawable {
        val body = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, if (lit) DARK_CARD_LIT else DARK_CARD).apply {
            cornerRadius = radius.toFloat()
            setStroke(1, if (lit) RIM_SIDE_LIT else RIM_SIDE) // 1px, not 1dp: a hairline stays crisp
            setDither(true) // near black the gradient has ~8 steps; dithered, they don't show as bands
        }
        return LayerDrawable(arrayOf(body, ColorDrawable(if (lit) RIM_TOP_LIT else RIM_TOP))).apply {
            setLayerGravity(1, Gravity.TOP or Gravity.FILL_HORIZONTAL)
            setLayerHeight(1, 1)
            setLayerInsetLeft(1, radius)
            setLayerInsetRight(1, radius)
        }
    }

    /** Supporting text: the secondary grey in dark mode, [lightAlpha] of the main colour in light mode. */
    private fun TextView.secondary(lightAlpha: Float) = if (dark) setTextColor(DARK_TEXT_2) else alpha = lightAlpha

    private fun label(text: String, size: Float) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(fg)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val TV_LISTINGS = "android.permission.READ_TV_LISTINGS"
        const val WRITE_SECURE = "android.permission.WRITE_SECURE_SETTINGS"
        const val ROLE_REQUEST = 2
        val ROW_ALIGN = linkedMapOf( // setting -> its name, and the row's gravity in the scroller
            "left" to (R.string.align_left to Gravity.START),
            "centre" to (R.string.align_centre to Gravity.CENTER_HORIZONTAL),
            "right" to (R.string.align_right to Gravity.END),
        )
        const val BADGE = 0xFFFF453A.toInt()
        // Dark mode tokens, for AMOLED: true black, and near-black cards that read as cards because their edge
        // catches light (brightest along the top), the way light-mode cards use a soft shadow.
        const val DARK_BG = 0xFF000000.toInt() // home ground, settings panel, inset boxes
        const val DARK_SURFACE = 0xFF0B0B0B.toInt() // placeholders behind images
        const val DARK_TEXT = 0xFFF5F5F5.toInt()
        const val DARK_TEXT_2 = 0xFF7A7A7A.toInt() // values, unfocused rows, supporting text
        val DARK_CARD = intArrayOf(0xFF0B0B0B.toInt(), 0xFF040404.toInt())
        val DARK_CARD_LIT = intArrayOf(0xFF141414.toInt(), 0xFF070707.toInt())
        const val RIM_TOP = 0x17FFFFFF // white 9%
        const val RIM_SIDE = 0x0AFFFFFF // white 4%
        const val RIM_TOP_LIT = 0x38FFFFFF // white 22%
        const val RIM_SIDE_LIT = 0x17FFFFFF
        const val PANEL_EDGE = 0x0FFFFFFF // white 6%, the settings panel's left edge
        const val HELP_URL = "https://github.com/aradhyas8/Plume_Launcher#home-button-on-android-13-and-newer"
        const val TOGGLE_ON = "\u0000on" // row values drawn as a switch instead of text
        const val TOGGLE_OFF = "\u0000off"
        const val DARK_DIM = 115 // alpha of the black laid over the Bing picture in dark mode (45%)
        const val PIN_TRIES = 5 // wrong PINs before a 30 s wait
        const val UNLOCKED_MS = 5 * 60_000L
        const val MEDIA_REQUEST = 3
        const val PER_APP = 3 // Continue Watching items per app ahead of the rest
        const val CARDS_AHEAD = 8 // Continue Watching cards built past the focused one (see addCardsTo)
        val READ_PHOTOS = if (Build.VERSION.SDK_INT >= 33) "android.permission.READ_MEDIA_IMAGES" else "android.permission.READ_EXTERNAL_STORAGE"
        /** Minutes between folder photos. */
        val PHOTO_EVERY = linkedMapOf(
            1 to R.string.every_1, 5 to R.string.every_5, 10 to R.string.every_10, 15 to R.string.every_15, 30 to R.string.every_30,
            60 to R.string.every_60, 1440 to R.string.every_1440,
        )
        const val CHOSEN = "✓"
        const val REPO = "aradhyas8/Plume_Launcher"
        const val UPDATE_STATUS = "dev.plume.UPDATE_STATUS"
        const val WRITE_EPG ="com.android.providers.tv.permission.WRITE_EPG_DATA"
        const val TV_SETTINGS = "com.android.tv.settings"
        const val HDMI = "hdmi:" // App.pkg prefix for HDMI device tiles; the rest is the TV input id
        const val DOCK_FIT = 6 // dock tiles across the glass panel
        const val DOCK_GAP = 28 // dp; room for the lens between tiles
        const val SHELF_EVERY = 9_000L // Top Shelf turns to the next item this often while it isn't focused
        const val FORK = true
        const val NEWS_INTENT = "plume:news" // the morning briefing's card on the shelf
        val BRIEFING_HOURS = 5..10
        const val ALL_APPS_COLUMNS = 6
        const val DRAWER_PAD = 36 // dp inside the All apps sheet
        /** Text over the artwork coloured from the picture (see tintTexts), in groups that share one colour. */
        val TINTED = listOf(
            listOf(R.id.clock, R.id.date, R.id.weather),
            listOf(R.id.shelfSource, R.id.shelfTitle, R.id.shelfSub),
        )
        const val SCREEN_PAD = 24 // dp margin on every edge of the screen
        const val SHELF_GAP = 24 // dp between the shelf's Resume row and the dock
        const val SHELF_OPEN_SCALE = 0.74f // shelf size while the dock is open
        const val DOCK_OPEN_PAD = 22 // dp around the tiles with the dock open: room for the lens (LENS_PAD) and its glow
        const val DOCK_REST_PAD = 12 // dp of glass above and below the tiles while the dock rests
        const val DOCK_REST_SCALE = 0.55f // resting dock size; with the smaller padding, ~40% of the open height
        val SHELF_KEYS = setOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)
        const val HERO_MIN_WIDTH = 1280
        const val HERO_TRIES = 4
        const val FOCUS_SCALE = 1.06f
        const val LENS_PAD = 13 // dp the glass lens reaches past the focused tile
        const val REST_ALPHA = 0.88f
        /** Shown names for the wallpaper keys saved in prefs (the keys stay English). */
        val WALLPAPER_NAMES = mapOf(
            "Rockies (day)" to R.string.wp_rockies_day, "Rockies (dusk)" to R.string.wp_rockies_dusk,
            "Pure black" to R.string.wp_pure_black, "Soft white" to R.string.wp_soft_white,
        )
        val BUNDLED_WALLPAPERS = linkedMapOf(
            "Rockies (day)" to R.drawable.wallpaper,
            "Rockies (dusk)" to R.drawable.wallpaper_dark,
        )
        val PIN_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
        )
        val EDIT_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        )
        val GREYSCALE = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        val DECELERATE = Motion.S // every animation uses the one S-curve
        @Volatile var APPS: List<App>? = null

        /**
         * Colour mode tints for the bundled marks: Simple Icons' brand hex, except where that's too dark to
         * read on dark cards (PlayStation, Prime Video) or grey (Google Play), where the brand's brighter blue/green is used.
         */
        val BRAND_COLOURS = mapOf(
            "playstation" to 0xFF0070D1.toInt(),
            "xbox" to 0xFF107C10.toInt(),
            "nintendoswitch" to 0xFFE60012.toInt(),
            "com.netflix.ninja" to 0xFFE50914.toInt(),
            "com.stremio.one" to 0xFF8D3DAF.toInt(),
            "io.gh.reisxd.tizentube.cobalt" to 0xFFFF0000.toInt(),
            "com.google.android.youtube.tv" to 0xFFFF0000.toInt(),
            "com.google.android.youtube.tvmusic" to 0xFFFF0000.toInt(),
            "com.amazon.amazonvideo.livingroom" to 0xFF00A8E1.toInt(),
            "com.spotify.tv.android" to 0xFF1DB954.toInt(),
            "org.videolan.vlc" to 0xFFFF8800.toInt(),
            "com.android.vending" to 0xFF01875F.toInt(),
        )

        val DEFAULT_ORDER = listOf(
            "com.netflix.ninja",
            "com.stremio.one",
            "io.gh.reisxd.tizentube.cobalt",
            "com.google.android.youtube.tv",
            "com.amazon.amazonvideo.livingroom",
            "com.spotify.tv.android",
        )

        /** HDMI tile icons by hdmiIcon key: brand marks (Simple Icons, CC0), the rest from Material Symbols (Apache 2.0). */
        val HDMI_ICONS = mapOf(
            "playstation" to R.drawable.logo_playstation,
            "xbox" to R.drawable.logo_xbox,
            "nintendoswitch" to R.drawable.logo_nintendoswitch,
            "appletv" to R.drawable.logo_appletv,
            "settop" to R.drawable.ic_settop,
            "playback" to R.drawable.ic_playback,
            "hdmi" to R.drawable.ic_hdmi,
        )

        /** Monochrome brand marks (Simple Icons, CC0). `true` = wordmark, shown without a label. */
        val LOGOS = mapOf(
            "com.netflix.ninja" to (R.drawable.logo_netflix to false),
            "com.stremio.one" to (R.drawable.logo_stremio to false),
            "io.gh.reisxd.tizentube.cobalt" to (R.drawable.logo_youtube to false),
            "com.google.android.youtube.tv" to (R.drawable.logo_youtube to false),
            "com.google.android.youtube.tvmusic" to (R.drawable.logo_youtubemusic to false),
            "com.amazon.amazonvideo.livingroom" to (R.drawable.logo_primevideo to true),
            "com.spotify.tv.android" to (R.drawable.logo_spotify to false),
            "org.videolan.vlc" to (R.drawable.logo_vlcmediaplayer to false),
            "com.android.vending" to (R.drawable.logo_googleplay to false),
        )
    }
}
