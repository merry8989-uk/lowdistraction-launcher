package com.lowdistraction.launcher.bubble

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/**
 * Three concentric rings, one inside the other. Tapping a ring reports which
 * one was hit:
 *   INNER  -> one step back
 *   MIDDLE -> go home (minimise the current app)
 *   OUTER  -> lock / sleep the screen
 */
class NestedCircleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Ring { INNER, MIDDLE, OUTER }

    var onRingTap: ((Ring) -> Unit)? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xE617181A.toInt()
        style = Paint.Style.FILL
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x669AE6B4.toInt()
        style = Paint.Style.STROKE
    }

    private fun radiusOuter(): Float = minOf(width / 2f, height / 2f)

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val rOuter = radiusOuter()
        val rMiddle = rOuter * 0.66f
        val rInner = rOuter * 0.36f

        stroke.strokeWidth = rOuter * 0.10f
        val rEdge = rOuter - stroke.strokeWidth / 2f

        canvas.drawCircle(cx, cy, rEdge, fill)
        canvas.drawCircle(cx, cy, rEdge, stroke)
        canvas.drawCircle(cx, cy, rMiddle, fill)
        canvas.drawCircle(cx, cy, rMiddle, stroke)
        canvas.drawCircle(cx, cy, rInner, fill)
        canvas.drawCircle(cx, cy, rInner, stroke)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val cx = width / 2f
            val cy = height / 2f
            val rOuter = radiusOuter()
            val d = hypot((event.x - cx).toDouble(), (event.y - cy).toDouble()).toFloat()
            val ring = when {
                d <= rOuter * 0.36f -> Ring.INNER
                d <= rOuter * 0.66f -> Ring.MIDDLE
                d <= rOuter -> Ring.OUTER
                else -> null
            }
            if (ring != null) {
                onRingTap?.invoke(ring)
                return true
            }
        }
        return true
    }
}
