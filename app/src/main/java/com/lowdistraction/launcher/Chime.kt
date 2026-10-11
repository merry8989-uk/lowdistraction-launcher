package com.lowdistraction.launcher

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

/**
 * Plays the same soft chime the assistive wheel uses, so the guide can let the
 * user hear it before they ever open the wheel.
 */
object Chime {

    private var pool: SoundPool? = null
    private var soundId = 0
    private var loaded = false
    private var pending = false

    fun play(context: Context) {
        val sound = pool ?: create(context)
        if (loaded) {
            sound.play(soundId, VOLUME, VOLUME, 1, 0, 1f)
        } else {
            pending = true
        }
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
        sound.setOnLoadCompleteListener { sp, _, status ->
            if (status == 0) {
                loaded = true
                if (pending) {
                    pending = false
                    sp.play(soundId, VOLUME, VOLUME, 1, 0, 1f)
                }
            }
        }
        soundId = sound.load(context, R.raw.wheel_chime, 1)
        pool = sound
        return sound
    }

    private const val VOLUME = 0.6f
}
