package com.iamrz1.tvhome

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/**
 * GlowDrawable draws a soft halo in the [depth] pixels around a rounded rect of [radius] pixels.
 * It stacks translucent rounded rects, so the halo fades out without a blur filter.
 */
class GlowDrawable(private val color: Int, private val radius: Float, private val depth: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun draw(canvas: Canvas) {
        val b = bounds
        paint.color = Color.argb(LAYER_ALPHA, Color.red(color), Color.green(color), Color.blue(color))
        for (i in 0 until STEPS) {
            val inset = depth * i / STEPS
            val r = radius + depth - inset
            canvas.drawRoundRect(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset, r, r, paint)
        }
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(filter: ColorFilter?) {
        paint.colorFilter = filter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    private companion object {
        const val STEPS = 8
        const val LAYER_ALPHA = 30
    }
}
