package com.iamrz1.tvhome

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * RoundedBanner draws [banner] scaled to fill its bounds, cropped to a rounded rect of [radius] pixels. The banner is
 * drawn once into a bitmap and painted with a shader, so the corners are smooth and do not rely on view clipping,
 * which some devices skip for an image that fills its parent.
 */
class RoundedBanner(private val banner: Drawable, private val radius: Float) : Drawable() {
    private var bitmap: Bitmap? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onBoundsChange(bounds: Rect) {
        bitmap = null
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val bmp = bitmap ?: render(b.width(), b.height()).also { bitmap = it }
        paint.shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        canvas.drawRoundRect(RectF(0f, 0f, b.width().toFloat(), b.height().toFloat()), radius, radius, paint)
        canvas.restore()
    }

    // center crop: the banner covers the bounds, and the overflow is cut off
    private fun render(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val iw = banner.intrinsicWidth.takeIf { it > 0 } ?: w
        val ih = banner.intrinsicHeight.takeIf { it > 0 } ?: h
        val scale = maxOf(w.toFloat() / iw, h.toFloat() / ih)
        val canvas = Canvas(bmp)
        canvas.translate((w - iw * scale) / 2f, (h - ih * scale) / 2f)
        canvas.scale(scale, scale)
        banner.setBounds(0, 0, iw, ih)
        banner.draw(canvas)
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
}
