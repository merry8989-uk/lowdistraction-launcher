package com.lowdistraction.launcher

import android.content.Context

/**
 * The apps the user pinned to the top of the home screen.
 *
 * **Nothing is ever pinned automatically.** The list starts empty on a fresh
 * install and only ever grows when the user pins something themselves.
 *
 * [MAX] is just the absolute ceiling — the biggest grid Settings allows (10 rows
 * by 4 columns). What actually applies is [Look.pinnedCapacity], which follows
 * the grid the user has chosen.
 */
class PinnedApps(context: Context) {

    private val prefs = context.getSharedPreferences("pinned_apps", Context.MODE_PRIVATE)

    fun all(): List<String> =
        prefs.getString(KEY, "")!!.split("\n").filter { it.isNotEmpty() }

    fun isPinned(component: String): Boolean = component in all()

    /** Returns false when the ceiling is already reached. */
    fun add(component: String): Boolean {
        val current = all()
        if (component in current) return true
        if (current.size >= MAX) return false
        save(current + component)
        return true
    }

    fun remove(component: String) = save(all().filter { it != component })

    fun set(list: List<String>) = save(list.take(MAX))

    private fun save(list: List<String>) =
        prefs.edit().putString(KEY, list.joinToString("\n")).apply()

    companion object {
        /** 10 rows by 4 columns — the largest grid the appearance settings allow. */
        const val MAX = 40
        private const val KEY = "components"
    }
}
