package com.lowdistraction.launcher.bubble

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import com.lowdistraction.launcher.SensitiveApps

/**
 * A no-op accessibility service whose only job is to expose the global
 * navigation actions (Back / Home / Recents / Lock screen) to the bubble.
 * It never reads or stores screen content.
 *
 * It also watches which app comes to the front: over a payment or government
 * app the bubble is taken off the screen and every gesture is ignored, so the
 * launcher never sits on top of anything sensitive.
 */
class AssistiveTouchService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        SensitiveApps.setStrictPaused(this, false)
        applySensitive(false)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        applySensitive(SensitiveApps.isSensitive(this, pkg))
    }

    /** Hides the bubble and mutes gestures while a sensitive app is in front. */
    private fun applySensitive(sensitive: Boolean) {
        if (sensitive == sensitiveActive) return
        sensitiveActive = sensitive
        FloatingBubbleService.onSensitiveChanged(sensitive)

        if (sensitive && SensitiveApps.isStrict(this)) {
            // Opt-in strict mode: switch the service off completely, which is the
            // only thing some apps will accept. Android gives no way to switch it
            // back on for us, so we leave a note for the launcher to offer that.
            SensitiveApps.setStrictPaused(this, true)
            runCatching { disableSelf() }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: AssistiveTouchService? = null
            private set

        /** True while a sensitive app is in front; gestures are ignored then. */
        @Volatile
        var sensitiveActive = false
            private set

        val isReady: Boolean get() = instance != null

        fun back(): Boolean =
            if (sensitiveActive) false
            else instance?.performGlobalAction(GLOBAL_ACTION_BACK) ?: false

        fun home(): Boolean =
            if (sensitiveActive) false
            else instance?.performGlobalAction(GLOBAL_ACTION_HOME) ?: false

        fun recents(): Boolean =
            if (sensitiveActive) false
            else instance?.performGlobalAction(GLOBAL_ACTION_RECENTS) ?: false

        fun lock(): Boolean {
            if (sensitiveActive) return false
            val service = instance ?: return false
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                service.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            } else {
                false
            }
        }

        /** Called when the launcher itself comes back to the front. */
        fun clearSensitive() {
            if (!sensitiveActive) return
            sensitiveActive = false
            FloatingBubbleService.onSensitiveChanged(false)
        }
    }
}
