package com.lowdistraction.launcher.bubble

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.lowdistraction.launcher.AppInfo
import com.lowdistraction.launcher.R
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The radial menu shown beside the bubble — now a **spin wheel**.
 *
 * The apps sit on a ring around the three nested circles. Drag anywhere on the
 * ring to rotate it; let go and it keeps spinning with momentum and slows to a
 * stop (fidget-spinner style). A plain tap on a slot launches that app, and a
 * long-press on a slot removes it from the ring.
 */
class RadialMenuView(
    context: Context,
    screenW: Int,
    screenH: Int,
    anchorX: Int,
    anchorY: Int,
    apps: List<AppInfo>,
    private val onApp: (AppInfo) -> Unit,
    private val onRemoveApp: (AppInfo) -> Unit,
    private val onAddApps: () -> Unit,
    private val onRing: (NestedCircleView.Ring) -> Unit,
    private val onDismiss: () -> Unit
) : FrameLayout(context) {

    private class Slot(val view: View, val app: AppInfo?, val baseAngle: Float)

    private val density = resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).roundToInt()

    private val ringRadius = dp(140).toFloat()
    private val centerSize = dp(120)
    private val slotSize = dp(56)

    private val centerX: Float
    private val centerY: Float

    private val slots = ArrayList<Slot>()
    private var angleOffset = 0f
    private var spinVelocity = 0f
    private var lastFrame = 0L

    private var dragging = false
    private var moved = false
    private var longPressFired = false
    private var lastAngle = 0f
    private var downAngle = 0f
    private var downDist = 0f
    private var lastMoveTime = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val longPressRunnable = Runnable {
        if (!moved) {
            val slot = nearestSlot(downAngle)
            if (slot?.app != null) {
                longPressFired = true
                onRemoveApp(slot.app)
            }
        }
    }

    init {
        setBackgroundColor(0x99000000.toInt())
        val margin = (ringRadius + slotSize / 2f + dp(8)).toInt()
        centerX = anchorX.coerceIn(margin, maxOf(margin, screenW - margin)).toFloat()
        centerY = anchorY.coerceIn(margin, maxOf(margin, screenH - margin)).toFloat()
        build()
    }

    private fun build() {
        // Centre: the three nested circles (Back / Home / Lock).
        val center = NestedCircleView(context).apply { onRingTap = { onRing(it) } }
        addView(center, LayoutParams(centerSize, centerSize).apply {
            leftMargin = (centerX - centerSize / 2f).roundToInt()
            topMargin = (centerY - centerSize / 2f).roundToInt()
        })

        val pinned = apps.take(BubblePrefs.MAX)
        val total = pinned.size + 1 // the extra slot is the "+" add button
        for (i in 0 until total) {
            val baseAngle = (360f / total) * i - 90f
            val view: View
            val app: AppInfo?
            if (i < pinned.size) {
                app = pinned[i]
                view = ImageView(context).apply {
                    setBackgroundResource(R.drawable.bg_slot)
                    val pad = (slotSize * 0.22f).toInt()
                    setPadding(pad, pad, pad, pad)
                    try {
                        setImageDrawable(context.packageManager.getApplicationIcon(app.packageName))
                    } catch (_: Exception) {
                        // leave blank if the icon cannot be loaded
                    }
                }
            } else {
                app = null
                view = TextView(context).apply {
                    text = "+"
                    textSize = 24f
                    setTextColor(0xFF9AE6B4.toInt())
                    gravity = Gravity.CENTER
                    setBackgroundResource(R.drawable.bg_slot)
                }
            }
            addView(view, LayoutParams(slotSize, slotSize))
            slots.add(Slot(view, app, baseAngle))
        }
        updateSlots()
    }

    private fun updateSlots() {
        for (slot in slots) {
            val a = Math.toRadians((slot.baseAngle + angleOffset).toDouble())
            val sx = centerX + (ringRadius * cos(a)).toFloat()
            val sy = centerY + (ringRadius * sin(a)).toFloat()
            slot.view.x = sx - slotSize / 2f
            slot.view.y = sy - slotSize / 2f
        }
    }

    private fun nearestSlot(angleDeg: Float): Slot? {
        var best: Slot? = null
        var bestDiff = Float.MAX_VALUE
        for (slot in slots) {
            val slotAngle = slot.baseAngle + angleOffset
            val diff = abs(((angleDeg - slotAngle + 540f) % 360f) - 180f)
            if (diff < bestDiff) {
                bestDiff = diff
                best = slot
            }
        }
        return best
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val dx = event.x - centerX
        val dy = event.y - centerY
        val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stopSpin()
                dragging = true
                moved = false
                longPressFired = false
                downDist = dist
                downAngle = angle
                lastAngle = angle
                lastMoveTime = SystemClock.uptimeMillis()
                spinVelocity = 0f
                handler.postDelayed(longPressRunnable, 420L)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                var delta = angle - lastAngle
                if (delta > 180f) delta -= 360f
                if (delta < -180f) delta += 360f

                if (abs(delta) > 0.4f) {
                    moved = true
                    handler.removeCallbacks(longPressRunnable)
                }
                if (dragging) {
                    angleOffset += delta
                    lastAngle = angle
                    val now = SystemClock.uptimeMillis()
                    val dt = (now - lastMoveTime) / 1000f
                    if (dt > 0.001f) {
                        spinVelocity = spinVelocity * 0.6f + (delta / dt) * 0.4f
                        lastMoveTime = now
                    }
                    updateSlots()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPressRunnable)
                if (!moved && !longPressFired) {
                    val inRing = downDist >= ringRadius - slotSize * 1.2f &&
                        downDist <= ringRadius + slotSize * 1.2f
                    if (inRing) {
                        val slot = nearestSlot(downAngle)
                        when {
                            slot == null -> onDismiss()
                            slot.app != null -> onApp(slot.app)
                            else -> onAddApps()
                        }
                    } else {
                        onDismiss()
                    }
                } else if (abs(spinVelocity) > 60f) {
                    startSpin()
                }
                dragging = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                dragging = false
                return true
            }
        }
        return true
    }

    private val spinTick = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            val dt = if (lastFrame == 0L) 0.016f
            else ((now - lastFrame) / 1000f).coerceAtMost(0.05f)
            lastFrame = now

            angleOffset += spinVelocity * dt
            spinVelocity *= Math.pow(0.12, dt.toDouble()).toFloat()
            updateSlots()

            if (abs(spinVelocity) > 12f) {
                postOnAnimation(this)
            } else {
                spinVelocity = 0f
                lastFrame = 0L
            }
        }
    }

    private fun startSpin() {
        lastFrame = 0L
        postOnAnimation(spinTick)
    }

    private fun stopSpin() {
        removeCallbacks(spinTick)
        spinVelocity = 0f
        lastFrame = 0L
    }
}
