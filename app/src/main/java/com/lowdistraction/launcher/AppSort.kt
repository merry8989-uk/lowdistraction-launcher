package com.lowdistraction.launcher

import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.storage.StorageManager
import java.text.Collator
import java.util.Locale

/**
 * Every order the app list can be shown in.
 *
 * All but four of these work with no permission at all. The four that need
 * Usage access are flagged by [needsUsageAccess]; until that is granted they
 * simply fall back to the name order, and the UI says so rather than pretending.
 */
enum class SortMode(val id: Int) {
    NAME(0),
    NAME_DESC(1),
    PACKAGE(2),
    PACKAGE_DESC(3),
    INSTALLED(4),
    INSTALLED_OLDEST(5),
    UPDATED(6),
    MOST_OPENED(7),
    LEAST_OPENED(8),
    RECENTLY_USED(9),
    LEAST_RECENTLY_USED(10),
    MOST_TIME(11),
    LEAST_TIME(12),
    LARGEST(13),
    SMALLEST(14),
    SHORTEST_NAME(15),
    LONGEST_NAME(16);

    val needsUsageAccess: Boolean
        get() = when (this) {
            MOST_TIME, LEAST_TIME, LARGEST, SMALLEST -> true
            else -> false
        }

    companion object {
        fun from(id: Int): SortMode = entries.firstOrNull { it.id == id } ?: NAME
    }
}

/** Remembers the chosen order, and which orders the user wants offered. */
object SortPrefs {

    private const val PREFS = "app_sort"
    private const val KEY_MODE = "mode"
    private const val KEY_DISABLED = "disabled"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun mode(c: Context): SortMode = SortMode.from(prefs(c).getInt(KEY_MODE, SortMode.NAME.id))

    fun setMode(c: Context, mode: SortMode) = prefs(c).edit().putInt(KEY_MODE, mode.id).apply()

    /** Everything is offered unless the user switched it off. */
    private fun disabled(c: Context): Set<Int> = prefs(c)
        .getStringSet(KEY_DISABLED, emptySet())!!
        .mapNotNull { it.toIntOrNull() }
        .toSet()

    fun isEnabled(c: Context, mode: SortMode): Boolean = mode.id !in disabled(c)

    fun setEnabled(c: Context, mode: SortMode, enabled: Boolean) {
        val next = disabled(c).toMutableSet()
        if (enabled) next.remove(mode.id) else next.add(mode.id)
        prefs(c).edit().putStringSet(KEY_DISABLED, next.map { it.toString() }.toSet()).apply()
    }

    /** The orders to offer. The one in use is always included, even if switched off. */
    fun enabledModes(c: Context): List<SortMode> {
        val current = mode(c)
        val list = SortMode.entries.filter { isEnabled(c, it) }
        return if (current in list) list else listOf(current) + list
    }
}

/**
 * Orders the app list.
 *
 * Install and update times come from the package manager, the open counts and
 * last-used times are the launcher's own tally, and the name lengths are just
 * the labels. Only foreground time and app size read the system's figures, and
 * those need Usage access.
 */
object AppSorter {

    fun sort(
        context: Context,
        apps: List<AppInfo>,
        mode: SortMode,
        sizes: Map<String, Long> = emptyMap()
    ): List<AppInfo> {
        val byName = byLabel()
        return when (mode) {
            SortMode.NAME -> apps.sortedWith(byName)
            SortMode.NAME_DESC -> apps.sortedWith(byName.reversed())
            SortMode.PACKAGE -> apps.sortedWith(compareBy<AppInfo> { it.packageName }.then(byName))
            SortMode.PACKAGE_DESC ->
                apps.sortedWith(compareByDescending<AppInfo> { it.packageName }.then(byName))
            SortMode.INSTALLED ->
                apps.sortedWith(compareByDescending<AppInfo> { it.installedAt }.then(byName))
            SortMode.INSTALLED_OLDEST ->
                apps.sortedWith(compareBy<AppInfo> { it.installedAt }.then(byName))
            SortMode.UPDATED ->
                apps.sortedWith(compareByDescending<AppInfo> { it.updatedAt }.then(byName))
            SortMode.MOST_OPENED -> apps.sortedWith(
                compareByDescending<AppInfo> { opens(context, it) }.then(byName)
            )
            SortMode.LEAST_OPENED ->
                apps.sortedWith(compareBy<AppInfo> { opens(context, it) }.then(byName))
            SortMode.RECENTLY_USED -> apps.sortedWith(
                compareByDescending<AppInfo> { usedAt(context, it) }.then(byName)
            )
            SortMode.LEAST_RECENTLY_USED ->
                apps.sortedWith(compareBy<AppInfo> { usedAt(context, it) }.then(byName))
            SortMode.MOST_TIME -> {
                val totals = foregroundTotals(context)
                apps.sortedWith(
                    compareByDescending<AppInfo> { totals[it.packageName] ?: 0L }.then(byName)
                )
            }
            SortMode.LEAST_TIME -> {
                val totals = foregroundTotals(context)
                apps.sortedWith(compareBy<AppInfo> { totals[it.packageName] ?: 0L }.then(byName))
            }
            SortMode.LARGEST -> apps.sortedWith(
                compareByDescending<AppInfo> { sizes[it.packageName] ?: 0L }.then(byName)
            )
            SortMode.SMALLEST -> apps.sortedWith(
                compareBy<AppInfo> { sizes[it.packageName] ?: 0L }.then(byName)
            )
            SortMode.SHORTEST_NAME ->
                apps.sortedWith(compareBy<AppInfo> { it.label.length }.then(byName))
            SortMode.LONGEST_NAME ->
                apps.sortedWith(compareByDescending<AppInfo> { it.label.length }.then(byName))
        }
    }

    private fun byLabel(): Comparator<AppInfo> {
        val collator = Collator.getInstance(Locale.getDefault())
        return compareBy(collator) { it.label }
    }

    private fun opens(context: Context, app: AppInfo): Long =
        AppUsage.count(context, app.component.flattenToString())

    private fun usedAt(context: Context, app: AppInfo): Long =
        AppUsage.lastUsed(context, app.component.flattenToString())

    /** True when the user has granted Usage access for the figures that need it. */
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

    /** Foreground time per package over the last month, cached for a minute. */
    @Volatile
    private var totalsCache: Pair<Long, Map<String, Long>>? = null

    private fun foregroundTotals(context: Context): Map<String, Long> {
        val now = System.currentTimeMillis()
        totalsCache?.let { (at, totals) ->
            if (now - at < 60_000L) return totals
        }
        val fresh = runCatching {
            if (!hasUsageAccess(context)) return emptyMap()
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val end = System.currentTimeMillis()
            val start = end - 30L * 24 * 60 * 60 * 1000
            usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end)
                .groupBy { it.packageName }
                .mapValues { (_, stats) -> stats.sumOf { it.totalTimeInForeground } }
        }.getOrDefault(emptyMap())
        totalsCache = now to fresh
        return fresh
    }

    /**
     * Installed size per package, for the given apps.
     *
     * The system asks for this on a worker thread — it may take seconds across a
     * whole phone — so the caller runs it off the UI thread and caches the result.
     * Without Usage access it returns nothing and the size orders fall back to the
     * name order.
     */
    fun loadSizes(context: Context, apps: List<AppInfo>): Map<String, Long> = runCatching {
        if (!hasUsageAccess(context)) return emptyMap()
        val storage = context.getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
        val out = HashMap<String, Long>()
        for (app in apps) {
            val uid = runCatching {
                context.packageManager.getApplicationInfo(app.packageName, 0).uid
            }.getOrNull() ?: continue
            val bytes = runCatching {
                storage.queryStatsForUid(StorageManager.UUID_DEFAULT, uid).appBytes
            }.getOrNull() ?: continue
            out[app.packageName] = bytes
        }
        out
    }.getOrDefault(emptyMap())
}
