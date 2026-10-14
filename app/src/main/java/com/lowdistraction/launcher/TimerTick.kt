package com.lowdistraction.launcher

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

/**
 * The soft tick the app-opening timer plays once a second. The last few seconds
 * are played at a slightly higher rate, so one small asset covers both.
 */
object TimerTick {

    private var pool: SoundPool? = null
    private var soundId = 0
    private var loaded = false

    fun play(context: Context, urgent: Boolean) {
        val sound = pool ?: create(context)
        if (!loaded) return
        val rate = if (urgent) 1.35f else 1.0f
        val volume = if (urgent) 0.75f else 0.5f
        sound.play(soundId, volume, volume, 1, 0, rate)
    }

    /** Frees the buffer; the next play builds a fresh pool. */
    fun release() {
        runCatching { pool?.release() }
        pool = null
        soundId = 0
        loaded = false
    }

    private fun create(context: Context): SoundPool {
        val sound = SoundPool.Builder()
            .setMaxStreams(2)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
        sound.setOnLoadCompleteListener { _, _, status ->
            if (status == 0) loaded = true
        }
        soundId = sound.load(context, R.raw.timer_tick, 1)
        pool = sound
        return sound
    }
}
