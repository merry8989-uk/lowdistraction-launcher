package com.lowdistraction.launcher

import android.content.Context

/** Small on/off toggles for the optional shortcut sets. */
object ShortcutPrefs {

    private const val PREFS = "launcher_prefs"

    private const val KEY_DEVCHECK = "devcheck_enabled"
    private const val KEY_GUIDE_SEEN = "guide_seen"
    private const val KEY_LAUNCH_PAUSE = "launch_pause"
    private const val KEY_PAUSE_SECONDS = "pause_seconds"
    private const val KEY_TICK_SOUND = "pause_tick"

    fun isDevCheckEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DEVCHECK, false)

    fun setDevCheckEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DEVCHECK, enabled).apply()
    }

    /** Whether the first-run guide has already been shown. */
    fun isGuideSeen(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_GUIDE_SEEN, false)

    fun setGuideSeen(context: Context, seen: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_GUIDE_SEEN, seen).apply()
    }

    /** The breather shown before an app opens. */
    fun isLaunchPauseEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_LAUNCH_PAUSE, true)

    fun setLaunchPauseEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_LAUNCH_PAUSE, enabled).apply()
    }

    /** How long that breather lasts, in seconds. The user picks this. */
    fun pauseSeconds(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_PAUSE_SECONDS, DEFAULT_PAUSE_SECONDS)
            .coerceIn(1, MAX_PAUSE_SECONDS)

    fun setPauseSeconds(context: Context, seconds: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_PAUSE_SECONDS, seconds.coerceIn(1, MAX_PAUSE_SECONDS)).apply()
    }

    /** The tick each second while the timer runs. */
    fun isTickSoundEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_TICK_SOUND, true)

    fun setTickSoundEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_TICK_SOUND, enabled).apply()
    }

    const val DEFAULT_PAUSE_SECONDS = 7
    const val MAX_PAUSE_SECONDS = 120
}
