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
import android.os.IBinder
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.lowdistraction.launcher.AppInfo
import com.lowdistraction.launcher.AppRepository
import com.lowdistraction.launcher.MainActivity
import com.lowdistraction.launcher.R
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Draws the draggable assistive-touch bubble and owns the menu / panel overlays.
 *
 *  - single tap  -> open the radial menu (8 app slots + nested circles)
 *  - double tap  -> same radial menu (quick access)
 *  - long press  -> device settings shortcuts panel
 */
class FloatingBubbleService : Service() {

    private lateinit var wm: WindowManager
    private var bubble: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var overlay: View? = null
    private var panel: View? = null

    private val prefs by lazy { BubblePrefs(this) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForeground(NOTIF_ID, buildNotification())
        addBubble()
    }

    override fun onDestroy() {
        running = false
        removeOverlay()
        removePanel()
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
        super.onDestroy()
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

        val gesture = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                toggleMenu()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                toggleMenu()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                togglePanel()
            }
        })

        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0

        view.setOnTouchListener { v, ev ->
            gesture.onTouchEvent(ev)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    startX = params.x
                    startY = params.y
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downRawX
                    val dy = ev.rawY - downRawY
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        runCatching { wm.updateViewLayout(v, params) }
                    }
                }
            }
            true
        }

        bubble = view
        bubbleParams = params
        wm.addView(view, params)
    }

    // --------------------------------------------------------------- the menu
    private fun toggleMenu() {
        if (overlay != null) {
            removeOverlay()
            return
        }
        val (sw, sh) = screenSize()
        val menu = BubbleMenu(
            context = this,
            screenW = sw,
            screenH = sh,
            anchorX = (bubbleParams?.x ?: 0) + dp(28),
            anchorY = (bubbleParams?.y ?: 0) + dp(28),
            apps = loadPinnedApps(),
            onApp = { launch(it); removeOverlay() },
            onRemoveApp = { prefs.remove(it.component.flattenToString()); rebuildMenu() },
            onAddApps = { openAppPicker() },
            onRing = { ring ->
                when (ring) {
                    NestedCircleView.Ring.INNER -> AssistiveTouchService.back()
                    NestedCircleView.Ring.MIDDLE -> AssistiveTouchService.home()
                    NestedCircleView.Ring.OUTER -> AssistiveTouchService.lock()
                }
                removeOverlay()
            },
            onDismiss = { removeOverlay() }
        )
        val view = menu.build()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        wm.addView(view, params)
        overlay = view
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

    // -------------------------------------------------------------- the panel
    private fun togglePanel() {
        if (panel != null) {
            removePanel()
            return
        }

        val items: List<Pair<String, () -> Unit>> = listOf(
            getString(R.string.qs_torch) to { QuickSettings.toggleTorch(this) },
            getString(R.string.qs_sound) to { QuickSettings.openSound(this) },
            getString(R.string.qs_brightness) to { QuickSettings.cycleBrightness(this) },
            getString(R.string.qs_focus) to { QuickSettings.openFocusMode(this) },
            getString(R.string.qs_dnd) to { QuickSettings.toggleDnd(this) },
            getString(R.string.qs_bedtime) to { QuickSettings.openBedtimeMode(this) },
            getString(R.string.qs_tea) to { startTeaMode() }
        )

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_panel)
            val p = dp(6)
            setPadding(p, p, p, p)
        }
        items.forEach { (label, action) ->
            column.addView(TextView(this).apply {
                text = label
                setTextColor(0xFFEDEDED.toInt())
                textSize = 15f
                setPadding(dp(16), dp(12), dp(16), dp(12))
                isClickable = true
                setOnClickListener { action(); removePanel() }
            })
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bubbleParams?.x ?: dp(12)
            y = (bubbleParams?.y ?: dp(240)) + dp(64)
        }
        wm.addView(column, params)
        panel = column
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

    private fun removePanel() {
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
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

        private const val NOTIF_ID = 4211
        private const val CHANNEL_ID = "assistive_bubble"

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
