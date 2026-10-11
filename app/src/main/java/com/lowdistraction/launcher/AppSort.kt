package com.lowdistraction.launcher

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import java.text.Collator
import java.util.Locale

/** The order the app list is shown in. */
enum class SortMode(val id: Int) {
    NAME(0),
    NAME_DESC(1),
    INSTALLED(2),
    UPDATED(3),
    MOST_OPENED(4),
    RECENTLY_USED(5),
    MOST_TIME(6);

    companion object {
        fun from(id: Int): SortMode = entries.firstOrNull { it.id == id } ?: NAME
    }
}

/** Remembers the chosen order. */
object SortPrefs {

    private const val PREFS = "app_sort"
    private const val KEY = "mode"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun mode(c: Context): SortMode = SortMode.from(prefs(c).getInt(KEY, SortMode.NAME.id))

    fun setMode(c: Context, mode: SortMode) = prefs(c).edit().putInt(KEY, mode.id).apply()
}

/**
 * Orders the app list.
 *
 * Everything except "most time used" works with no permission at all: install
 * and update times come from the package manager, and the open counts are the
 * launcher's own tally. "Most time used" reads the system's usage figures, which
 * need Usage access — granted by hand from a system screen — and simply sorts
 * nothing while that is missing.
 */
object AppSorter {

    fun sort(context: Context, apps: List<AppInfo>, mode: SortMode): List<AppInfo> = when (mode) {
        SortMode.NAME -> apps.sortedWith(byLabel())
        SortMode.NAME_DESC -> apps.sortedWith(byLabel().reversed())
        SortMode.INSTALLED -> apps.sortedByDescending { it.installedAt }
        SortMode.UPDATED -> apps.sortedByDescending { it.updatedAt }
        SortMode.MOST_OPENED ->
            apps.sortedByDescending { AppUsage.count(context, it.component.flattenToString()) }
        SortMode.RECENTLY_USED ->
            apps.sortedByDescending { AppUsage.lastUsed(context, it.component.flattenToString()) }
        SortMode.MOST_TIME -> {
            val totals = foregroundTotals(context)
            apps.sortedByDescending { totals[it.packageName] ?: 0L }
        }
    }

    private fun byLabel(): Comparator<AppInfo> {
        val collator = Collator.getInstance(Locale.getDefault())
        return compareBy(collator) { it.label }
    }

    /** True when the user has granted Usage access for the time-used sort. */
    fun hasUsageAccess(context: Context): Boolean = runCatching {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
            )
        }
        mode == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    /** Foreground time per package over the last month, or empty without access. */
    private fun foregroundTotals(context: Context): Map<String, Long> = runCatching {
        if (!hasUsageAccess(context)) return emptyMap()
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val start = end - 30L * 24 * 60 * 60 * 1000
        usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end)
            .groupBy { it.packageName }
            .mapValues { (_, stats) -> stats.sumOf { it.totalTimeInForeground } }
    }.getOrDefault(emptyMap())
}
