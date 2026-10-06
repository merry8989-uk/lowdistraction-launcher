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
                AppInfo(
                    label = info.loadLabel(pm).toString().trim().ifEmpty { info.activityInfo.packageName },
                    packageName = info.activityInfo.packageName,
                    component = ComponentName(info.activityInfo.packageName, info.activityInfo.name)
                )
            }
            .distinctBy { it.component.flattenToString() }
            .sortedWith(compareBy(collator) { it.label })
    }
}
