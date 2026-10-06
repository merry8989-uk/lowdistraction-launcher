package com.lowdistraction.launcher.bubble

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.lowdistraction.launcher.AppInfo
import com.lowdistraction.launcher.R
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Builds the full-screen radial menu that appears beside the bubble.
 * Centre = the three nested circles; around it, up to 8 app slots plus an
 * "add" slot. The whole overlay dismisses when you tap the empty background.
 */
class BubbleMenu(
    private val context: Context,
    private val screenW: Int,
    private val screenH: Int,
    private val anchorX: Int,
    private val anchorY: Int,
    private val apps: List<AppInfo>,
    private val onApp: (AppInfo) -> Unit,
    private val onRemoveApp: (AppInfo) -> Unit,
    private val onAddApps: () -> Unit,
    private val onRing: (NestedCircleView.Ring) -> Unit,
    private val onDismiss: () -> Unit
) {

    fun build(): View {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).roundToInt()

        val ringRadius = dp(140)
        val centerSize = dp(120)
        val slotSize = dp(56)

        val root = FrameLayout(context).apply {
            setBackgroundColor(0x99000000.toInt())
            isClickable = true
        }
        root.setOnClickListener { onDismiss() }

        // Keep the whole ring on screen by clamping the menu centre.
        val margin = ringRadius + slotSize / 2 + dp(8)
        val cx = anchorX.coerceIn(margin, maxOf(margin, screenW - margin))
        val cy = anchorY.coerceIn(margin, maxOf(margin, screenH - margin))

        // Centre: nested circles
        val center = NestedCircleView(context).apply { onRingTap = { onRing(it) } }
        root.addView(center, FrameLayout.LayoutParams(centerSize, centerSize).apply {
            leftMargin = cx - centerSize / 2
            topMargin = cy - centerSize / 2
        })

        // Ring slots: pinned apps + one "add" slot
        val slots = apps.take(BubblePrefs.MAX)
        val total = slots.size + 1
        for (i in 0 until total) {
            val angle = Math.toRadians((360.0 / total) * i - 90.0)
            val sx = cx + (ringRadius * cos(angle)).roundToInt()
            val sy = cy + (ringRadius * sin(angle)).roundToInt()
            val child: View = if (i < slots.size) appSlot(slots[i], slotSize) else addSlot()
            root.addView(child, FrameLayout.LayoutParams(slotSize, slotSize).apply {
                leftMargin = sx - slotSize / 2
                topMargin = sy - slotSize / 2
            })
        }
        return root
    }

    private fun appSlot(app: AppInfo, size: Int): View {
        val view = ImageView(context).apply {
            setBackgroundResource(R.drawable.bg_slot)
            val pad = (size * 0.22f).toInt()
            setPadding(pad, pad, pad, pad)
            setOnClickListener { onApp(app) }
            setOnLongClickListener { onRemoveApp(app); true }
        }
        try {
            view.setImageDrawable(context.packageManager.getApplicationIcon(app.packageName))
        } catch (_: Exception) {
            // leave blank if the icon cannot be loaded
        }
        return view
    }

    private fun addSlot(): View = TextView(context).apply {
        text = "+"
        textSize = 24f
        setTextColor(0xFF9AE6B4.toInt())
        gravity = Gravity.CENTER
        setBackgroundResource(R.drawable.bg_slot)
        setOnClickListener { onAddApps() }
    }
}
