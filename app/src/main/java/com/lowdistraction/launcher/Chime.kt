package com.lowdistraction.launcher

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

/**
 * Plays the selected wheel chime. Used both by the guide's sound test and by the
 * settings picker, and reloaded whenever the choice changes.
 */
object Chime {

    private var pool: SoundPool? = null
    private var soundId = 0
    private var loadedRes = -1
    private var loaded = false
    private var pending = false

    fun play(context: Context) {
        val sound = pool ?: create(context)
        val res = WheelSound.resource(context)
        if (res != loadedRes) load(context, sound, res)
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
        pool = sound
        return sound
    }

    private fun load(context: Context, sound: SoundPool, res: Int) {
        loaded = false
        loadedRes = res
        soundId = sound.load(context, res, 1)
    }

    private const val VOLUME = 0.6f
}
