package com.lowdistraction.launcher

import android.content.Context

/**
 * Counts how often, and how recently, the launcher opened each app.
 *
 * This is tracked by the launcher itself — nothing is read from the system and
 * nothing leaves the device — so "most opened" and "recently used" work without
 * asking for any permission at all.
 */
object AppUsage {

    private const val PREFS = "app_usage"
    private const val KEY = "counters"

    @Volatile
    private var cache: HashMap<String, LongArray>? = null

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load(c: Context): HashMap<String, LongArray> {
        cache?.let { return it }
        val map = HashMap<String, LongArray>()
        for (line in (prefs(c).getString(KEY, "") ?: "").split("\n")) {
            if (line.isEmpty()) continue
            val parts = line.split("|")
            if (parts.size != 3) continue
            val count = parts[1].toLongOrNull() ?: continue
            val last = parts[2].toLongOrNull() ?: continue
            map[parts[0]] = longArrayOf(count, last)
        }
        cache = map
        return map
    }

    private fun save(c: Context, map: Map<String, LongArray>) {
        val text = map.entries.joinToString("\n") { "${it.key}|${it.value[0]}|${it.value[1]}" }
        prefs(c).edit().putString(KEY, text).apply()
    }

    fun record(c: Context, component: String) {
        val map = load(c)
        val now = System.currentTimeMillis()
        val current = map[component]
        map[component] =
            if (current == null) longArrayOf(1L, now) else longArrayOf(current[0] + 1L, now)
        save(c, map)
    }

    fun count(c: Context, component: String): Long = load(c)[component]?.get(0) ?: 0L

    fun lastUsed(c: Context, component: String): Long = load(c)[component]?.get(1) ?: 0L
}
