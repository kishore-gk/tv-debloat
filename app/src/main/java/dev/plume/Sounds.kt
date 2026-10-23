package dev.plume

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.provider.Settings

/**
 * Plume's own interface sounds, in place of Android's stock click: a soft, warm synth blip to move, a blooming fifth to
 * select, and a low double pulse at the end of a row, each with a short dark reverb. Respects the TV's "Sound effects" setting.
 * (The sounds are generated, not recordings: res/raw/nav_*.wav.)
 */
internal object Sounds {
    private var pool: SoundPool? = null
    private var tick = 0
    private var tock = 0
    private var thud = 0
    private var app: Context? = null

    fun init(c: Context) {
        if (pool != null) return
        app = c.applicationContext
        pool = SoundPool.Builder()
            .setMaxStreams(5) // the reverb tails overlap when you move quickly
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build())
            .build().also {
                tick = it.load(c, R.raw.nav_tick, 1)
                tock = it.load(c, R.raw.nav_select, 1)
                thud = it.load(c, R.raw.nav_edge, 1)
            }
    }

    /** Focus moved. */
    fun move() = play(tick, 0.8f)

    /** OK pressed on something. */
    fun select() = play(tock, 0.85f)

    /** Pressed past the end of a row or list. */
    fun edge() = play(thud, 1f)

    private fun play(id: Int, volume: Float) {
        val p = pool ?: return
        val on = app?.let { Settings.System.getInt(it.contentResolver, Settings.System.SOUND_EFFECTS_ENABLED, 1) } ?: 1
        if (on == 0 || id == 0) return
        p.play(id, volume, volume, 1, 0, 1f)
    }
}
