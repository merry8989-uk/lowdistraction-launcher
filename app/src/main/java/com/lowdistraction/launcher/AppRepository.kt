package com.lowdistraction.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import java.text.Collator
import java.util.Locale

/**
 * Enumerates the apps that expose a launcher entry, de-duplicates them and
 * returns them sorted by label using the device's locale collation.
 *
 * Install and update times are collected here too, in the same pass, so the
 * sort options have them ready without touching the package manager again.
 */
object AppRepository {

    fun loadApps(context: Context): List<AppInfo> {
        val pm = context.packageManager

        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val resolved: List<ResolveInfo> = pm.queryIntentActivities(mainIntent, 0)
        val collator = Collator.getInstance(Locale.getDefault())

        return resolved
            .map { info ->
                val pkg = info.activityInfo.packageName
                val times = runCatching {
                    val packageInfo = pm.getPackageInfo(pkg, 0)
                    packageInfo.firstInstallTime to packageInfo.lastUpdateTime
                }.getOrDefault(0L to 0L)
                AppInfo(
                    label = info.loadLabel(pm).toString().trim().ifEmpty { pkg },
                    packageName = pkg,
                    component = ComponentName(pkg, info.activityInfo.name),
                    installedAt = times.first,
                    updatedAt = times.second
                )
            }
            .distinctBy { it.component.flattenToString() }
            .sortedWith(compareBy(collator) { it.label })
    }
}
