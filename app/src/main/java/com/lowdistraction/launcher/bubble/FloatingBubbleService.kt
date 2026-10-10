package com.lowdistraction.launcher.bubble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import com.lowdistraction.launcher.AppInfo
import com.lowdistraction.launcher.AppRepository
import com.lowdistraction.launcher.MainActivity
import com.lowdistraction.launcher.R
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Draws the draggable assistive-touch bubble.
 *
 *  - 1 tap        -> Back
 *  - 2 taps       -> Home
 *  - 3 taps       -> Lock / sleep
 *  - long press   -> the spin-wheel menu (apps + Torch / Volume / DND / Break)
 *  - drag         -> move the bubble
 */
class FloatingBubbleService : Service() {

    private lateinit var wm: WindowManager
    private var bubble: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var overlay: View? = null
    private var bubbleHidden = false

    private val prefs by lazy { BubblePrefs(this) }
    private val handler = Handler(Looper.getMainLooper())

    private var tapCount = 0
    private val tapAction = Runnable {
        when (tapCount) {
            1 -> AssistiveTouchService.back()
            2 -> AssistiveTouchService.home()
            else -> AssistiveTouchService.lock()
        }
        tapCount = 0
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        instance = this
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForeground(NOTIF_ID, buildNotification())
        addBubble()
        // The service can be (re)started while a sensitive app is already open.
        applySensitive(AssistiveTouchService.sensitiveActive)
    }

    override fun onDestroy() {
        running = false
        if (instance === this) instance = null
        handler.removeCallbacksAndMessages(null)
        removeOverlay()
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
        bubbleHidden = false
        super.onDestroy()
    }

    /**
     * Removes the bubble entirely while a payment or government app is in front,
     * and puts it back the moment that app leaves. Called from
     * [AssistiveTouchService], always on the main thread.
     */
    private fun applySensitive(sensitive: Boolean) {
        if (sensitive) {
            removeOverlay()
            if (!bubbleHidden) {
                bubble?.let { runCatching { wm.removeView(it) } }
                bubbleHidden = true
            }
        } else if (bubbleHidden) {
            val view = bubble
            val params = bubbleParams
            if (view != null && params != null) {
                runCatching { wm.addView(view, params) }
            }
            bubbleHidden = false
        }
    }

    // ------------------------------------------------------------- the bubble
    private fun addBubble() {
        val size = dp(56)
        val view = ImageView(this).apply {
            setImageResource(R.drawable.ic_bubble)
            setBackgroundResource(R.drawable.bg_bubble)
            val pad = dp(10)
            setPadding(pad, pad, pad, pad)
        }
        val params = WindowManager.LayoutParams(
            size, size,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(240)
        }

        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var downTime = 0L
        var movedFar = false
        var longFired = false

        val longPress = Runnable {
            longFired = true
            toggleMenu()
        }

        view.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    startX = params.x
                    startY = params.y
                    downTime = SystemClock.uptimeMillis()
                    movedFar = false
                    longFired = false
                    handler.postDelayed(longPress, LONG_PRESS_MS)
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downRawX
                    val dy = ev.rawY - downRawY
                    if (abs(dx) > dp(8) || abs(dy) > dp(8)) {
                        movedFar = true
                        handler.removeCallbacks(longPress)
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        runCatching { wm.updateViewLayout(v, params) }
                    }
                }

                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPress)
                    val elapsed = SystemClock.uptimeMillis() - downTime
                    if (!movedFar && !longFired && elapsed < TAP_MAX_MS) {
                        registerTap()
                    }
                }

                MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(longPress)
            }
            true
        }

        bubble = view
        bubbleParams = params
        wm.addView(view, params)
    }

    /** Counts quick taps and fires Back / Home / Lock accordingly. */
    private fun registerTap() {
        tapCount++
        handler.removeCallbacks(tapAction)
        if (tapCount >= 3) {
            AssistiveTouchService.lock()
            tapCount = 0
        } else {
            handler.postDelayed(tapAction, TAP_WINDOW_MS)
        }
    }

    // --------------------------------------------------------------- the menu
    private fun toggleMenu() {
        if (overlay != null) {
            removeOverlay()
            return
        }
        val (sw, sh) = screenSize()
        val menu = RadialMenuView(
            context = this,
            screenW = sw,
            screenH = sh,
            anchorX = (bubbleParams?.x ?: 0) + dp(28),
            anchorY = (bubbleParams?.y ?: 0) + dp(28),
            apps = loadPinnedApps(),
            onApp = { launch(it); removeOverlay() },
            onRemoveApp = { prefs.remove(it.component.flattenToString()); rebuildMenu() },
            onAddApps = { openAppPicker() },
            onQuickAction = { action ->
                when (action) {
                    RadialMenuView.QuickAction.TORCH -> QuickSettings.toggleTorch(this)
                    RadialMenuView.QuickAction.VOLUME -> QuickSettings.openSound(this)
                    RadialMenuView.QuickAction.DND -> QuickSettings.toggleDnd(this)
                    RadialMenuView.QuickAction.BREAK -> startTeaMode()
                }
                removeOverlay()
            },
            onDismiss = { removeOverlay() }
        )
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        wm.addView(menu, params)
        overlay = menu
    }

    private fun rebuildMenu() {
        removeOverlay()
        toggleMenu()
    }

    private fun openAppPicker() {
        removeOverlay()
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MainActivity.EXTRA_PICK_APPS, true)
        )
    }

    private fun startTeaMode() {
        startActivity(
            Intent(this, TeaModeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    // ---------------------------------------------------------------- helpers
    private fun loadPinnedApps(): List<AppInfo> {
        val byComponent = AppRepository.loadApps(this).associateBy { it.component.flattenToString() }
        return prefs.pinned().mapNotNull { byComponent[it] }
    }

    private fun launch(app: AppInfo) {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            component = app.component
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        }
        runCatching { startActivity(intent) }
    }

    private fun removeOverlay() {
        overlay?.let { runCatching { wm.removeView(it) } }
        overlay = null
    }

    @Suppress("DEPRECATION")
    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun screenSize(): Pair<Int, Int> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val bounds = wm.currentWindowMetrics.bounds
        bounds.width() to bounds.height()
    } else {
        resources.displayMetrics.let { it.widthPixels to it.heightPixels }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.bubble_channel_name),
                    NotificationManager.IMPORTANCE_MIN
                ).apply {
                    description = getString(R.string.bubble_channel_description)
                    setShowBadge(false)
                }
            )
        }
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bubble)
            .setContentTitle(getString(R.string.bubble_notification_title))
            .setContentText(getString(R.string.bubble_notification_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(contentIntent)
            .build()
    }

    companion object {
        @Volatile
        var running = false
            private set

        @Volatile
        private var instance: FloatingBubbleService? = null

        /** Hides / restores the bubble when a sensitive app comes or goes. */
        fun onSensitiveChanged(sensitive: Boolean) {
            val svc = instance ?: return
            svc.handler.post { svc.applySensitive(sensitive) }
        }

        private const val NOTIF_ID = 4211
        private const val CHANNEL_ID = "assistive_bubble"
        private const val LONG_PRESS_MS = 500L
        private const val TAP_MAX_MS = 300L
        private const val TAP_WINDOW_MS = 450L

        fun start(context: Context) {
            val intent = Intent(context, FloatingBubbleService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingBubbleService::class.java))
        }
    }
}
