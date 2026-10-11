package com.lowdistraction.launcher

import android.content.Context

/** Up to eight apps shown in their own emphasised grid on the home screen. */
class PinnedApps(context: Context) {

    private val prefs = context.getSharedPreferences("pinned_apps", Context.MODE_PRIVATE)

    fun all(): List<String> =
        prefs.getString(KEY, "")!!.split("\n").filter { it.isNotEmpty() }

    fun isPinned(component: String): Boolean = component in all()

    /** Returns false when the four slots are already taken. */
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
        const val MAX = 8
        private const val KEY = "components"
    }
}
