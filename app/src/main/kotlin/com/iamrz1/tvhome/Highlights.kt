package com.iamrz1.tvhome

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * GlowDrawable draws a soft halo around a rounded rect of [tileW] by [tileH] pixels, centered in its bounds.
 * The halo is a blurred copy of the rect, so it fades out smoothly over [blur] pixels. It sits [offsetY] pixels lower
 * than the rect, which makes it a drop shadow.
 */
class GlowDrawable(
    private val color: Int,
    private val radius: Float,
    private val blur: Float,
    private val tileW: Int,
    private val tileH: Int,
    private val offsetY: Float = 0f,
) : Drawable() {
    private var bitmap: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    override fun onBoundsChange(bounds: Rect) {
        bitmap = null
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val bmp = bitmap ?: shared(b.width(), b.height()).also { bitmap = it }
        canvas.drawBitmap(bmp, b.left.toFloat(), b.top.toFloat(), paint)
    }

    /** Draws the halo for bounds of this size now, so the first time a tile is focused does not have to. */
    fun preload(w: Int, h: Int) {
        shared(w, h)
    }

    // Every cell of a grid has the same halo, so they share one bitmap. Drawing a blur is slow, and a cell that drew its
    // own made the focus animation stutter on the first visit of each cell.
    private fun shared(w: Int, h: Int): Bitmap =
        cache.getOrPut("$color|$radius|$blur|$tileW|$tileH|$offsetY|$w|$h") { render(w, h) }

    // drawn once into a bitmap, because a blur mask filter is not available when drawing with the GPU
    private fun render(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = this@GlowDrawable.color
            maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
        }
        val left = (w - tileW) / 2f
        val top = (h - tileH) / 2f + offsetY
        Canvas(bmp).drawRoundRect(RectF(left, top, left + tileW, top + tileH), radius, radius, glow)
        return bmp
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
        val cache = HashMap<String, Bitmap>()
    }
}

/**
 * RingDrawable draws a line of [stroke] pixels around a rounded rect of [tileW] by [tileH] pixels, centered in its
 * bounds. The line sits [gap] pixels outside the rect, so the artwork inside is never covered.
 */
class RingDrawable(
    color: Int,
    private val stroke: Float,
    private val gap: Float,
    private val radius: Float,
    private val tileW: Int,
    private val tileH: Int,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = stroke
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val grow = gap + stroke / 2
        val left = b.exactCenterX() - tileW / 2f - grow
        val top = b.exactCenterY() - tileH / 2f - grow
        val r = if (radius > 0f) radius + grow else 0f
        canvas.drawRoundRect(RectF(left, top, left + tileW + 2 * grow, top + tileH + 2 * grow), r, r, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(filter: ColorFilter?) {
        paint.colorFilter = filter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
