package dev.plume

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/** On the Home intent when HomeOverride only covers Google TV's home (not a Home press). */
internal const val EXTRA_COVER = "dev.plume.cover"

/**
 * Google TV's launcher has to stay enabled (disabling it boot-loops the TV), so we take the Home key before
 * the system sees it and open our home directly; Google TV never flashes up. Holding Home still opens the
 * Google TV dashboard. Covering Google TV whenever it shows stays as the fallback for anything else that
 * opens it (e.g. Back out of the last app).
 */
class HomeOverride : AccessibilityService() {
    private val ui = Handler(Looper.getMainLooper())

    /** The screen coming on (waking from standby): Google TV's home is usually what it wakes to. */
    private val screenOn = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = checkSoon()
    }

    override fun onServiceConnected() {
        running = this
        registerReceiver(screenOn, IntentFilter(Intent.ACTION_SCREEN_ON))
        // At power-on Google TV's home is shown before Android starts this service, so its "window changed" event
        // is already gone: look at what's on screen now instead.
        checkSoon()
    }

    /** Looks a few times as the screen settles (the home screen and its first window arrive in steps). */
    private fun checkSoon() {
        for (ms in longArrayOf(600L, 1800L, 4000L)) ui.postDelayed({ coverGoogleHome() }, ms)
    }

    /**
     * If Google TV's home is in front, Plume goes over it. The front app comes from Android's usage log (the app
     * most recently brought forward), not from the screen: reading window content stops this TV from starting
     * the service at all.
     */
    private fun coverGoogleHome() {
        val front = frontApp()
        when {
            front == GOOGLE_TV -> goHome(cover = true)
            // Nothing readable yet, right after a boot: the TV is starting on its home screen, so start on Plume's.
            front == null && SystemClock.elapsedRealtime() < BOOT_WINDOW_MS -> goHome(cover = true)
        }
    }

    /**
     * Unbound. Only a real "turned off" (the service gone from Android's list, by Plume's switch or Android's
     * settings) is remembered, so Plume won't turn it back on. Shutting the TV down, or an update, also unbinds it
     * and must not count.
     */
    override fun onUnbind(intent: Intent?): Boolean {
        running = null
        ui.removeCallbacksAndMessages(null)
        try { unregisterReceiver(screenOn) } catch (e: Exception) {}
        val me = ComponentName(this, HomeOverride::class.java)
        val listed = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            .split(':').any { ComponentName.unflattenFromString(it) == me }
        if (!listed) getSharedPreferences("home", MODE_PRIVATE).edit().putBoolean("home_off", true).apply()
        return super.onUnbind(intent)
    }

    override fun onKeyEvent(e: KeyEvent): Boolean {
        if (e.keyCode != KeyEvent.KEYCODE_HOME) return false
        // Decided on release, by how long it was held: this TV's remote doesn't reliably send key repeats.
        if (e.action == KeyEvent.ACTION_UP && !e.isCanceled) {
            if (e.eventTime - e.downTime < LONG_PRESS_MS) goHome() else performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        }
        return true
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && e.packageName == GOOGLE_TV &&
            e.className?.contains("dashboard", ignoreCase = true) != true
        ) goHome(cover = true)
    }

    /** The app most recently brought to the front in the last few minutes, or null if the log can't be read. */
    private fun frontApp(): String? = try {
        val usage = getSystemService(android.app.usage.UsageStatsManager::class.java)
        val now = System.currentTimeMillis()
        val events = usage.queryEvents(now - 5 * 60_000L, now)
        val e = android.app.usage.UsageEvents.Event()
        var last: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED) last = e.packageName
        }
        last
    } catch (e: Exception) {
        null
    }

    /**
     * Marked as Home so the setup check can tell it from Plume being opened from the apps list. [cover]: not a Home
     * press, just keeping Google TV's home from showing (after a boot or screen on), so Plume leaves its focus alone.
     */
    private fun goHome(cover: Boolean = false) = startActivity(Intent(this, MainActivity::class.java).addCategory(Intent.CATEGORY_HOME)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply { if (cover) putExtra(EXTRA_COVER, true) })

    override fun onInterrupt() {}

    companion object {
        /** The bound service, so Plume's switch can turn it off without any permission. */
        var running: HomeOverride? = null
            private set
        private const val GOOGLE_TV = "com.google.android.apps.tv.launcherx"
        private const val LONG_PRESS_MS = 500L
        private const val BOOT_WINDOW_MS = 3 * 60_000L
    }
}
