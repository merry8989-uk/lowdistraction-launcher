package com.lowdistraction.launcher

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import kotlin.math.min

/**
 * Wraps an app icon and draws it inside the chosen shape with an optional
 * outline. The icon is never flattened to a bitmap, so adaptive and vector
 * icons keep their detail.
 */
class ShapedIconDrawable(
    private val icon: Drawable,
    private val shape: IconShape,
    private val outlineWidth: Float,
    private val outlineColor: Int
) : Drawable() {

    private val path = Path()
    private val rect = RectF()
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = outlineWidth
        color = outlineColor
    }

    override fun onBoundsChange(bounds: Rect) {
        path.reset()
        val inset = outlineWidth / 2f
        rect.set(
            bounds.left + inset,
            bounds.top + inset,
            bounds.right - inset,
            bounds.bottom - inset
        )
        when (shape) {
            IconShape.ORIGINAL -> path.addRect(rect, Path.Direction.CW)
            IconShape.CIRCLE -> path.addOval(rect, Path.Direction.CW)
            IconShape.ROUNDED -> {
                val r = min(rect.width(), rect.height()) * 0.24f
                path.addRoundRect(rect, r, r, Path.Direction.CW)
            }
            IconShape.SQUARE -> path.addRect(rect, Path.Direction.CW)
        }
        icon.bounds = Rect(
            rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt()
        )
    }

    override fun draw(canvas: Canvas) {
        val save = canvas.save()
        if (shape != IconShape.ORIGINAL) canvas.clipPath(path)
        icon.draw(canvas)
        canvas.restore()
        if (outlineWidth > 0f && shape != IconShape.ORIGINAL) {
            canvas.drawPath(path, outlinePaint)
        }
    }

    override fun setAlpha(alpha: Int) {
        icon.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        icon.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth(): Int = icon.intrinsicWidth

    override fun getIntrinsicHeight(): Int = icon.intrinsicHeight
}
