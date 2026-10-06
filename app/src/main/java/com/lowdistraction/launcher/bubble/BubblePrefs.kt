package com.lowdistraction.launcher.bubble

import android.content.Context

/** Stores the up-to-8 apps pinned to the bubble ring. */
class BubblePrefs(context: Context) {

    private val prefs = context.getSharedPreferences("bubble_prefs", Context.MODE_PRIVATE)

    fun pinned(): List<String> =
        prefs.getString(KEY_PINNED, "")!!.split("\n").filter { it.isNotEmpty() }

    fun setPinned(list: List<String>) {
        prefs.edit().putString(KEY_PINNED, list.take(MAX).joinToString("\n")).apply()
    }

    fun add(component: String) {
        val current = pinned().toMutableList()
        if (component !in current && current.size < MAX) {
            current.add(component)
            setPinned(current)
        }
    }

    fun remove(component: String) {
        setPinned(pinned().filter { it != component })
    }

    companion object {
        const val MAX = 8
        private const val KEY_PINNED = "pinned_components"
    }
}
