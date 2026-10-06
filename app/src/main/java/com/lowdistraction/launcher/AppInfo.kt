package com.lowdistraction.launcher

import android.content.ComponentName

/** A single launchable app shown in the list. */
data class AppInfo(
    val label: String,
    val packageName: String,
    val component: ComponentName
)
