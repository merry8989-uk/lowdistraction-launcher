package com.lowdistraction.launcher.bubble

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.view.accessibility.AccessibilityEvent

/**
 * A no-op accessibility service whose only job is to expose the global
 * navigation actions (Back / Home / Recents / Lock screen) to the bubble.
 * It never reads or stores screen content.
 */
class AssistiveTouchService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: AssistiveTouchService? = null
            private set

        val isReady: Boolean get() = instance != null

        fun back(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_BACK) ?: false

        fun home(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_HOME) ?: false

        fun recents(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_RECENTS) ?: false

        fun lock(): Boolean {
            val service = instance ?: return false
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                service.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            } else {
                false
            }
        }
    }
}
