package com.lowdistraction.launcher.bubble

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
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
 * The menu shown on a long-press of the bubble.
 *
 *  - A **spin wheel** of pinned apps (drag to rotate, momentum, tap to launch,
 *    long-press a slot to remove). A soft chime plays as slots pass by, so the
 *    wheel sounds calm while it turns.
 *  - A row of **four quick actions** along the bottom: Torch, Volume, DND, Break.
 */
class RadialMenuView(
    context: Context,
    screenW: Int,
    screenH: Int,
    anchorX: Int,
    anchorY: Int,
    private val apps: List<AppInfo>,
    private val onApp: (AppInfo) -> Unit,
    private val onRemoveApp: (AppInfo) -> Unit,
    private val onAddApps: () -> Unit,
    private val onQuickAction: (QuickAction) -> Unit,
    private val onDismiss: () -> Unit
) : FrameLayout(context) {

    enum class QuickAction { TORCH, VOLUME, DND, BREAK }

    private class Slot(val view: View, val app: AppInfo?, val baseAngle: Float)

    private val density = resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).roundToInt()

    private val ringRadius = dp(128).toFloat()
    private val slotSize = dp(56)

    private val centerX: Float
    private val centerY: Float

    private val slots = ArrayList<Slot>()
    private var angleOffset = 0f
    private var spinVelocity = 0f
    private var lastFrame = 0L
    private var lastSlotIndex = -1

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

    private val soundPool: SoundPool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()
    private var chimeId = 0

    init {
        setBackgroundColor(0x99000000.toInt())
        chimeId = soundPool.load(context, R.raw.wheel_chime, 1)

        val margin = (ringRadius + slotSize / 2f + dp(8)).toInt()
        val bottomReserve = dp(110)
        centerX = anchorX.coerceIn(margin, maxOf(margin, screenW - margin)).toFloat()
        centerY = anchorY.coerceIn(margin, maxOf(margin, screenH - margin - bottomReserve)).toFloat()
        build()
    }

    private fun build() {
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
        addBottomBar()
    }

    private fun addBottomBar() {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val items = listOf(
            R.string.qs_torch to QuickAction.TORCH,
            R.string.qs_volume to QuickAction.VOLUME,
            R.string.qs_dnd to QuickAction.DND,
            R.string.qs_break to QuickAction.BREAK
        )
        for ((labelRes, action) in items) {
            val chip = TextView(context).apply {
                text = context.getString(labelRes)
                setTextColor(0xFFEDEDED.toInt())
                textSize = 13f
                gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.bg_chip)
                setPadding(dp(10), dp(14), dp(10), dp(14))
                isClickable = true
                setOnClickListener { onQuickAction(action) }
            }
            bar.addView(chip, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(4), 0, dp(4), 0)
            })
        }
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM
            leftMargin = dp(14)
            rightMargin = dp(14)
            bottomMargin = dp(56)
        })
    }

    private fun updateSlots() {
        for (slot in slots) {
            val a = Math.toRadians((slot.baseAngle + angleOffset).toDouble())
            val sx = centerX + (ringRadius * cos(a)).toFloat()
            val sy = centerY + (ringRadius * sin(a)).toFloat()
            slot.view.x = sx - slotSize / 2f
            slot.view.y = sy - slotSize / 2f
        }
        maybeChime()
    }

    /** Plays a soft chime each time a slot passes the top, so the wheel sounds calm. */
    private fun maybeChime() {
        val total = slots.size
        if (total <= 0 || chimeId == 0) return
        val step = 360f / total
        val normalised = ((angleOffset % 360f) + 360f) % 360f
        val index = (normalised / step).toInt()
        if (lastSlotIndex == -1) {
            lastSlotIndex = index
            return
        }
        if (index != lastSlotIndex) {
            lastSlotIndex = index
            val volume = (abs(spinVelocity) / 1200f).coerceIn(0.18f, 0.6f)
            soundPool.play(chimeId, volume, volume, 1, 0, 1f)
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

    override fun onDetachedFromWindow() {
        removeCallbacks(spinTick)
        handler.removeCallbacksAndMessages(null)
        soundPool.release()
        super.onDetachedFromWindow()
    }
}
