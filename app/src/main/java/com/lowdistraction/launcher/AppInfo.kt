package com.lowdistraction.launcher

import android.content.ComponentName

/** A single launchable app shown in the list. */
data class AppInfo(
    val label: String,
    val packageName: String,
    val component: ComponentName,
    /** When the app was first installed, and last updated, in millis. */
    val installedAt: Long = 0L,
    val updatedAt: Long = 0L
)
