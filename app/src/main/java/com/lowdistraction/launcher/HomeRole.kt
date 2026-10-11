package com.lowdistraction.launcher

import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

/**
 * Helps the user make this app the default HOME app.
 *
 * On Android 10+ we ask through the system RoleManager, which shows the
 * standard "use as default home app?" dialog. On older devices (or when the
 * role is unavailable) we fall back to the system Home settings screen.
 */
object HomeRole {

    const val REQUEST_CODE = 9001

    fun isDefault(context: Context): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        @Suppress("DEPRECATION")
        val resolved = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return resolved?.activityInfo?.packageName == context.packageName
    }

    /**
     * Opens the system screen where the default launcher is chosen — the
     * "Default apps → Home app" picker. Returns false when nothing on this
     * device could be opened, so callers can fall back to [request].
     */
    fun openHomeSettings(activity: Activity): Boolean {
        val candidates = ArrayList<Intent>(2)
        candidates.add(Intent(Settings.ACTION_HOME_SETTINGS))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            candidates.add(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        }
        for (intent in candidates) {
            try {
                activity.startActivity(intent)
                return true
            } catch (_: Exception) {
                // Try the next screen.
            }
        }
        // Last resort: the system's own home chooser.
        return try {
            activity.startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    fun request(activity: Activity) {
        if (isDefault(activity)) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = activity.getSystemService(RoleManager::class.java)
            if (roleManager != null &&
                roleManager.isRoleAvailable(RoleManager.ROLE_HOME) &&
                !roleManager.isRoleHeld(RoleManager.ROLE_HOME)
            ) {
                activity.startActivityForResult(
                    roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME),
                    REQUEST_CODE
                )
                return
            }
        }

        openHomeSettings(activity)
    }
}
