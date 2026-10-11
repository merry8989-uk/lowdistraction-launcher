package com.lowdistraction.launcher

import android.content.Context

/** Small on/off toggles for the optional shortcut sets. */
object ShortcutPrefs {

    private const val PREFS = "launcher_prefs"
    private const val KEY_DEVCHECK = "devcheck_enabled"
    private const val KEY_GUIDE_SEEN = "guide_seen"

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
}
