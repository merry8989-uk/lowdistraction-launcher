package com.lowdistraction.launcher

import android.content.Context

/**
 * Persists the set of hidden package names in SharedPreferences.
 * Hidden apps are filtered out of the home list; long-press the clock
 * to see and unhide them.
 */
class HiddenApps(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(): Set<String> = prefs.getStringSet(KEY, emptySet())?.toSet() ?: emptySet()

    fun isHidden(pkg: String): Boolean = all().contains(pkg)

    fun hide(pkg: String) {
        val set = all().toMutableSet().apply { add(pkg) }
        prefs.edit().putStringSet(KEY, set).apply()
    }

    fun unhide(pkg: String) {
        val set = all().toMutableSet().apply { remove(pkg) }
        prefs.edit().putStringSet(KEY, set).apply()
    }

    companion object {
        private const val PREFS = "launcher_prefs"
        private const val KEY = "hidden_packages"
    }
}
