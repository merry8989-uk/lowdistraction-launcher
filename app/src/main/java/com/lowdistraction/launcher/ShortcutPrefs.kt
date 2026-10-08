package com.lowdistraction.launcher

import android.content.Context

/** Small on/off toggles for the optional shortcut sets. */
object ShortcutPrefs {

    private const val PREFS = "launcher_prefs"
    private const val KEY_DEVCHECK = "devcheck_enabled"

    fun isDevCheckEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DEVCHECK, false)

    fun setDevCheckEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DEVCHECK, enabled).apply()
    }
}
